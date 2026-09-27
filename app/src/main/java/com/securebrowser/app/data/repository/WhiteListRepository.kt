package com.securebrowser.app.data.repository

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.db.dao.WhiteListRuleDao
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.whitelist.WhiteListRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * خيارات الإضافة الثلاثة من المواصفة (§7) — "ما الذي تريد السماح به؟"
 * - SITE_ONLY: الصفحة الرئيسية للنطاق فقط.
 * - ALL_PATHS: النطاق نفسه بكل صفحاته.
 * - WITH_SUBDOMAINS: النطاق + كل نطاقاته الفرعية.
 */
enum class AddOption { SITE_ONLY, ALL_PATHS, WITH_SUBDOMAINS }

/**
 * مستودع القائمة البيضاء — يعزل Room عن المحرك ويوحّد القواعد عند الإدخال.
 *
 * استنتاج نوع القاعدة من العنوان:
 * - "example.com" أو "https://example.com" → ALLOW_DOMAIN
 * - "https://example.com/news" → ALLOW_PATH_PREFIX
 */
class WhiteListRepository(private val dao: WhiteListRuleDao) {

    fun observeAll(): Flow<List<WhiteListRule>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun currentRules(): List<WhiteListRule> = dao.getAll().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    /**
     * إضافة قاعدة من نص حر (يطبّع: punycode، lowercase، path).
     * @return معرف القاعدة أو خطأ وصفي.
     */
    suspend fun addFromUrl(
        input: String,
        type: RuleType? = null,
        subdomainPolicy: SubdomainPolicy = SubdomainPolicy.INCLUDE_SUBDOMAINS
    ): Result<Long> {
        val parsed = when (val r = UrlNormalizer.normalize(input)) {
            is NormalizeResult.Success -> r.url
            is NormalizeResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
        }
        if (!parsed.isWeb || parsed.host.isEmpty()) {
            return Result.failure(IllegalArgumentException("not_a_web_url"))
        }
        val ruleType = type
            ?: if (parsed.path.isBlank() || parsed.path == "/") RuleType.ALLOW_DOMAIN
            else RuleType.ALLOW_PATH_PREFIX
        val now = System.currentTimeMillis()
        val id = dao.insert(
            WhiteListRuleEntity(
                scheme = null, // المضيف هو البوابة — أي http/https لنفس المضيف
                host = parsed.host,
                path = if (ruleType == RuleType.ALLOW_DOMAIN) null else parsed.path,
                port = parsed.port,
                ruleType = ruleType.name,
                subdomainPolicy = subdomainPolicy.name,
                enabled = true,
                createdAt = now,
                updatedAt = now
            )
        )
        return Result.success(id)
    }

    /**
     * إضافة قاعدة لموقع من خيارات "ما الذي تريد السماح به؟" (مرحلة 2 §7).
     * SITE_ONLY → الصفحة الرئيسية فقط؛ ALL_PATHS → النطاق كاملًا؛ WITH_SUBDOMAINS → + النطاقات الفرعية.
     * تتجاهل التكرار: إن وُجدت نفس القاعدة تُعاد بنجاح دون إدراج جديد.
     */
    suspend fun addSiteRule(hostInput: String, option: AddOption): Result<Long> {
        val normalizedHost = runCatching {
            val parsed = when (val r = UrlNormalizer.normalize("https://$hostInput")) {
                is NormalizeResult.Success -> r.url
                is NormalizeResult.Invalid -> return Result.failure(IllegalArgumentException(r.reason))
            }
            parsed.host
        }.getOrElse { return Result.failure(it) }
        if (normalizedHost.isBlank()) return Result.failure(IllegalArgumentException("empty_host"))

        val (type, subdomains) = when (option) {
            AddOption.SITE_ONLY -> RuleType.ALLOW_EXACT to SubdomainPolicy.EXACT_HOST_ONLY
            AddOption.ALL_PATHS -> RuleType.ALLOW_DOMAIN to SubdomainPolicy.EXACT_HOST_ONLY
            AddOption.WITH_SUBDOMAINS -> RuleType.ALLOW_DOMAIN to SubdomainPolicy.INCLUDE_SUBDOMAINS
        }

        val existing = dao.getAll()
        val duplicate = existing.any {
            it.host == normalizedHost && it.ruleType == type.name && it.subdomainPolicy == subdomains.name
        }
        if (duplicate) return Result.success(-1L)

        val now = System.currentTimeMillis()
        val id = dao.insert(
            WhiteListRuleEntity(
                scheme = null,
                host = normalizedHost,
                path = if (type == RuleType.ALLOW_EXACT) "/" else null,
                port = null,
                ruleType = type.name,
                subdomainPolicy = subdomains.name,
                enabled = true,
                createdAt = now,
                updatedAt = now
            )
        )
        return Result.success(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) =
        dao.setEnabled(id, enabled, System.currentTimeMillis())

    /** تعديل نطاق السماح لقاعدة قائمة (§22 Edit) — من لوحة الوالدين بعد المصادقة. */
    suspend fun updateScope(id: Long, type: RuleType, subdomains: SubdomainPolicy) =
        dao.updateScope(id, type.name, subdomains.name, System.currentTimeMillis())

    suspend fun delete(rule: WhiteListRule) {
        dao.delete(rule.toEntity())
    }

    private fun WhiteListRuleEntity.toDomain(): WhiteListRule = WhiteListRule(
        id = id,
        host = host,
        path = path,
        scheme = scheme,
        port = port,
        type = runCatching { RuleType.valueOf(ruleType) }.getOrElse { RuleType.ALLOW_DOMAIN },
        subdomainPolicy = runCatching { SubdomainPolicy.valueOf(subdomainPolicy) }
            .getOrElse { SubdomainPolicy.EXACT_HOST_ONLY },
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun WhiteListRule.toEntity(): WhiteListRuleEntity = WhiteListRuleEntity(
        id = id,
        scheme = scheme,
        host = host,
        path = path,
        port = port,
        ruleType = type.name,
        subdomainPolicy = subdomainPolicy.name,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
