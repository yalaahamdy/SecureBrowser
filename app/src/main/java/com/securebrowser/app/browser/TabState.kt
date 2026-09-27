package com.securebrowser.app.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView

/** حالة واجهة التبويب. */
data class TabUiState(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isStartPage: Boolean = true
)

/** جلسة تبويب — كل تبويب يملك WebView (Chromium) خاصًا به + معاينة للوحة التبويبات. */
class TabSession(
    val id: Long,
    val webView: WebView,
    val createdAt: Long = System.currentTimeMillis()
) {
    var uiState: TabUiState = TabUiState()

    /** رابط مُستعاد من الجلسة لم يُحمَّل بعد (يُحمَّل عند أول تفعيل). */
    var pendingRestoreUrl: String? = null

    /** معاينة الصفحة (تُلتقط عند التبديل/فتح اللوحة/اكتمال تحميل الصفحة). */
    var preview: Bitmap? = null
        private set

    /**
     * يلتقط معاينة للتبويب (على الموضوع الرئيسي).
     *
     * v1.5.0: المعاينة القديمة **لا تُستدعى عليها recycle** — تُستبدل المرجعية
     * فقط وتتولى GC تحريرها. السبب: قد تكون البطاقة القديمة لا تزال معروضة في
     * لوحة التبويبات (أنيميشن إغلاق/تثبيت) والرسم بbitmap مستدعى عليه recycle
     * يسبب انهيار "trying to use a recycled bitmap". الحد الأقصى 20 تبويبًا
     * بمعاينة ~320×N يجعل الذاكرة محدودة (~5MB أسوأ حالة) وتُحرَّر عند الإغلاق.
     */
    fun capturePreview() {
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(full))
            val targetW = 320
            val targetH = (320.0 * h / w).toInt().coerceAtLeast(160)
            val scaled = Bitmap.createScaledBitmap(full, targetW, targetH, true)
            if (scaled != full) full.recycle()
            preview = scaled
        } catch (e: Exception) {
            // المعاينة اختيارية — لا تؤثر على التصفح
        }
    }

    /** إفراغ مرجعية المعاينة (بدون recycle — قد تكون معروضة في بطاقة). */
    fun releasePreview() {
        preview = null
    }

    fun update(transform: (TabUiState) -> TabUiState) {
        uiState = transform(uiState)
    }
}
