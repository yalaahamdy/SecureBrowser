package com.securebrowser.app.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * واجهة تخزين انتهاء الوصول المؤقت — تُنفَّذ فوق SecureStorage (مشفر Keystore) في التطبيق،
 * وبذاكرة فقط في الاختبارات. يفصل منطق القرار عن Android ليُختبر آليًا على JVM.
 */
interface TempAccessStore {
    fun putExpiry(expiryEpochMs: Long)
    fun getExpiry(): Long?
    fun clear()
}

/** تنفيذ بالذاكرة للاختبارات والحالات الافتراضية. */
class InMemoryTempAccessStore : TempAccessStore {
    @Volatile
    private var expiry: Long? = null

    override fun putExpiry(expiryEpochMs: Long) { expiry = expiryEpochMs }
    override fun getExpiry(): Long? = expiry
    override fun clear() { expiry = null }
}

/** حالة الوصول المؤقت للواجهة (مؤشر الشريط + العد التنازلي). */
data class TempAccessUiState(
    val active: Boolean = false,
    val remainingMs: Long = 0
)

/**
 * إدارة الوصول المؤقت — المرحلة الثانية (تستبدل نموذج المنح لكل نطاق).
 *
 * النموذج المعتمد (مواصفة المرحلة 2 §7-§8):
 * نافذة زمنية **شاملة** يفتحها الوالد بعد المصادقة ليتمكن من زيارة المواقع التي
 * يريد إضافتها للقائمة البيضاء، ثم يضيفها من السجل عبر "Add to WhiteList".
 *
 * الضمانات الأمنية:
 * - وقت الانتهاء مخزَّن (مشفر) — إغلاق التطبيق أثناء الوصول المؤقت لا يحوله
 *   إلى تجاوز دائم: عند إعادة التشغيل يستأنف العد من نفس لحظة الانتهاء أو ينتهي فورًا.
 * - عند الانتهاء يعود فرض القائمة البيضاء تلقائيًا بلا أي تدخل.
 * - التمديد يتم فقط من مسار موثق بالوالدين (لوحة التحكم) — لا يوجد أي مسار آخر.
 * - ساعة قابلة للحقن للاختبار الدقيق.
 */
class TemporaryAccessManager(
    private val store: TempAccessStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val _uiState = MutableStateFlow(computeUiState())

    /** حالة للواجهة: نشط/ليس + المتبقي — كل قراءة تعيد الحساب من الساعة (لا قيمة قديمة أبدًا). */
    val uiState: StateFlow<TempAccessUiState>
        get() = _uiState.also { it.value = computeUiState() }

    /** يُستدعى عند تشغيل التطبيق: يقرأ الانتهاء المخزن وينظف النوافذ المنتهية. */
    fun restoreFromStore() {
        // لو انتهى أثناء إغلاق التطبيق → تنظيف صريح حتى لا يبقى أثر
        if (!isActive()) store.clear()
        _uiState.value = computeUiState()
    }

    /** نبضة كل ثانية من ServiceLocator — تحدّث العد وتنهي النافذة عند موعدها. */
    fun tick() {
        val s = computeUiState()
        if (_uiState.value != s) _uiState.value = s
    }

    /** بدء نافذة وصول مؤقت (يتطلب مصادقة والدين في مسار الواجهة). */
    fun start(durationMs: Long): Boolean {
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) return false
        store.putExpiry(clock() + durationMs)
        _uiState.value = computeUiState()
        return true
    }

    /** تمديد النافذة — من لوحة الوالدين فقط بعد إعادة المصادقة. */
    fun extend(durationMs: Long): Boolean = start(durationMs)

    /** القرار الذي يعتمده SecurityEngine — شامل لكل النطاقات أثناء النافذة. */
    fun isActive(): Boolean {
        val expiry = store.getExpiry() ?: return false
        return clock() < expiry
    }

    fun remainingMs(): Long {
        val expiry = store.getExpiry() ?: return 0
        return (expiry - clock()).coerceAtLeast(0)
    }

    /** "End now" — إنهاء فوري (زيادة أمان، متاح من المؤشر نفسه). */
    fun endNow() {
        store.clear()
        _uiState.value = computeUiState()
    }

    private fun computeUiState(): TempAccessUiState {
        val expiry = store.getExpiry() ?: return TempAccessUiState(false, 0)
        val now = clock()
        return if (now < expiry) TempAccessUiState(true, expiry - now)
        else TempAccessUiState(false, 0)
    }

    companion object {
        val PRESET_MINUTES = listOf(5, 10, 15, 30, 60)
        const val MAX_MINUTES = 120
        const val MAX_DURATION_MS: Long = MAX_MINUTES * 60_000L
    }
}
