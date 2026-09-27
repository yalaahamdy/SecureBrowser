package com.securebrowser.app.data.repository

import com.securebrowser.app.data.db.dao.AppSettingsDao
import com.securebrowser.app.data.db.entity.AppSettingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * مستودع الإعدادات (مفتاح/قيمة) مع كاش في الذاكرة يُحدَّث من القاعدة.
 *
 * مفاتيح المستخدم العادي (لا تلمس الأمن):
 * theme, language, search_engine, homepage, zoom, media_autoplay
 *
 * مفاتيح الوالدين (تُعدَّل من لوحة التحكم بعد المصادقة فقط):
 * restore_session, external_apps, external_confirm, video_players, apk_policy,
 * archive_policy, unknown_policy, lock_timeout_minutes, policy_schema
 *
 * ممنوعات مصممة في البنية (لا توجد مفاتيح لها إطلاقًا):
 * تعطيل القائمة البيضاء، تعطيل السجل، تعطيل القفل الأبوي، تجاوز سياسة التنزيلات.
 */
class SettingsRepository(
    private val dao: AppSettingsDao,
    scope: CoroutineScope
) {

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    @Volatile
    private var warmedUp = false

    init {
        scope.launch {
            dao.observeAll().collect { list ->
                cache = list.associate { it.key to it.value }
                warmedUp = true
            }
        }
    }

    /** تحميل متزامن مرة واحدة عند بدء التطبيق (لتطبيق السمة/اللغة قبل أول إطار). */
    suspend fun warmUp() {
        if (warmedUp) return
        cache = dao.getAll().associate { it.key to it.value }
        warmedUp = true
    }

    fun int(key: String, default: Int): Int = cache[key]?.toIntOrNull() ?: default

    fun string(key: String, default: String): String = cache[key] ?: default

    fun bool(key: String, default: Boolean): Boolean = when (cache[key]) {
        null -> default
        else -> cache[key] == "1"
    }

    suspend fun put(key: String, value: String) {
        cache = cache + (key to value)
        dao.upsert(AppSettingEntity(key, value))
    }

    suspend fun putBool(key: String, value: Boolean) = put(key, if (value) "1" else "0")

    suspend fun putInt(key: String, value: Int) = put(key, value.toString())

    // ————— المستخدم العادي —————

    val zoom: Int get() = int("zoom", 100).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val theme: String get() = string("theme", "system")          // system | light | dark
    val language: String get() = string("language", "system")    // system | ar | en
    val searchEngine: String get() = string("search_engine", "duckduckgo") // google | bing | duckduckgo
    val homepage: String get() = string("homepage", "start")     // start | <url>
    val mediaAutoplay: Boolean get() = bool("media_autoplay", true)

    // ————— الوالدين —————

    val restoreSession: Boolean get() = bool("restore_session", true)
    val externalApps: Boolean get() = bool("external_apps", true)
    val externalConfirm: Boolean get() = bool("external_confirm", true)

    /**
     * مشغلات الفيديو الخارجية (v1.2.0) — تفعيل فتح مشغلات الفيديو/الصوت الخارجية
     * (VLC/MX Player/nPlayer...) من المنصات التعليمية عبر intent:/مخططات المشغلات.
     * الافتراضي مفعّل (طلب المستخدم الصريح) ويظل التأكيد النظامي بوابة أمام كل فتح.
     */
    val videoPlayers: Boolean get() = bool("video_players", true)

    /**
     * فتح بوابة الوالدين بالبصمة (v1.4.0) — اختياري للوالد، الافتراضي معطل:
     * جهاز الطفل غالبًا يحمل بصمات متعددة، وأي بصمة مسجلة تستطيع فتح البوابة
     * إن فُعّل. يُفعّل فقط إذا كانت بصمة الوالد هي الوحيدة المسجلة على الجهاز.
     */
    val biometricUnlock: Boolean get() = bool("biometric_unlock", false)

    // v1.2.0: الافتراضي "السماح مع التسجيل" لكل فئات التنزيلات — لا حظر افتراضي لأي تنزيل.
    val apkPolicy: String get() = string("apk_policy", "allow")             // allow | approval | block
    val archivePolicy: String get() = string("archive_policy", "allow")     // allow | approval | block
    val unknownPolicy: String get() = string("unknown_policy", "allow")     // allow | approval | block
    val lockTimeoutMinutes: Int get() = int("lock_timeout_minutes", 5).coerceIn(1, 60)

    companion object {
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 200

        /** محركات البحث المدعومة: قالب البحث — يخضع نفسه للقائمة البيضاء بلا استثناء. */
        val SEARCH_ENGINES: Map<String, String> = mapOf(
            "duckduckgo" to "https://duckduckgo.com/?q=",
            "google" to "https://www.google.com/search?q=",
            "bing" to "https://www.bing.com/search?q="
        )

        fun searchUrlFor(engine: String, query: String): String? {
            val base = SEARCH_ENGINES[engine] ?: SEARCH_ENGINES["duckduckgo"]!!
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            return base + encoded
        }
    }
}
