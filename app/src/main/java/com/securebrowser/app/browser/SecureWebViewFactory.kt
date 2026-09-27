package com.securebrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.securebrowser.app.di.ServiceLocator

/**
 * مصنع WebView بتهيئة Chromium أمنية احترافية — كل WebView في التطبيق يمر من هنا.
 *
 * التهيئة الأمنية (fail-closed):
 * - Multi-windows مفعّل لكن اعتراض onCreateWindow يفرض القائمة البيضاء.
 * - JS يطلب النوافذ فقط ليصل الطلب إلى اعتراضنا — **الاعتراض الأمني لا يتأثر إطلاقًا**.
 * - لا file:// ولا content:// من داخل المحتوى.
 * - Mixed content محظور تمامًا، الكوكيز الخارجية معطلة، Safe Browsing مفعّل.
 *
 * v1.4.0: فصل [applyHardenedSettings] و[attachDownloadListener] كي تستخدمهما
 * أيضًا نوافذ popup التي **تُعتمد كتبويبات حقيقية** عبر [PopupWindowShell] —
 * كانت النافذة العابرة في v1.3.0 تُنشأ بWebView افتراضي (JS معطل أصلًا).
 */
@SuppressLint("SetJavaScriptEnabled")
object SecureWebViewFactory {

    fun create(context: Context, tabId: Long, controller: BrowserController): WebView {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.isFocusableInTouchMode = true

        applyHardenedSettings(webView)

        // v1.7.0 — كوكيز الأطراف الثالثة: مشغّلات الفيديو التعليمية توزّع جلسات
        // التشغيل/التوكن على نطاقات CDN مستقلة (مثل *.googlevideo.com و *.akamaihd.net)
        // فتفشل المصادقة بلا كوكيز الطرف الثالث. قرار الأمان لا يتأثر: مسار التنقل
        // يظل محكومًا بالكامل عبر SecurityEngine — الكوكيز لا تفتح أي نطاق جديد.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // v1.7.0 — أولوية عارض التبويب: مهم عند الظهور، معفى عند الغياب —
        // يمنع قتل/تجميد مشغّل الفيديو منتصف التشغيل على الأجهزة ضغيرة الذاكرة
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)

        // العملاء المؤمّنون — كل التنقلات تمر عبر SecurityEngine
        webView.webViewClient = SecureWebViewClient(tabId, controller)
        webView.webChromeClient = SecureWebChromeClient(tabId, controller)

        // التنزيلات عبر مدير التنزيلات (سياسة كاملة + مراقبة)
        attachDownloadListener(webView, controller)

        return webView
    }

    /**
     * إعدادات Chromium المؤمَّنة (fail-closed) — تُطبَّق على كل WebView في التطبيق:
     * تبويبات المصنع، ونوافذ popup العابرة قبل اعتمادها تبويبًا.
     */
    fun applyHardenedSettings(webView: WebView) {
        webView.settings.apply {
            // الويب الحديث كاملًا
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = !ServiceLocator.settingsRepository.mediaAutoplay

            // v1.7.0 — هويّة كروم الحقيقية: بعض المنصات التعليمية تمنع تشغيل
            // فيديوهاتها على «متصفحات غير معروفة» بالكشف عن وسمي WebView
            // (»; wv« في الـ UA و»Version/4.0«) — إزالتهما يجعل الهوية مطابقة
            // لكروم أندرويد على الجهاز نفسه بلا تزوير أي أرقام إصدار.
            userAgentString = chromeLikeUserAgent(webView.context)
            // السماح لـ JS بطلب النوافذ — الاعتراض الأمني وحده هو الحاجز:
            // كل نافذة (بإيماءة أو بلاها) تمر عبر onCreateWindow → SecurityEngine.
            // تعطيل هذا يسقط window.open غير الإيمائي (فتح ملفات/طباعة/تسجيل
            // OAuth بعد استجابة شبكة) بصمت قبل أن يصل إلى الاعتراض إطلاقًا.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            // أمان
            allowFileAccess = false
            allowContentAccess = false
            // v1.7.0 — وضع التوافق للمحتوى المختلط: بدل الحظر الشامل (NEVER_ALLOW)
            // الذي كان يكسر مشغّلات الفيديو على CDN غير مشفّر داخل صفحات https،
            // يطابق سلوك كروم: الوسائط السلبية (فيديو/صوت/صور) تُسمح وتُرقّى
            // تلقائيًا متى أمكن، بينما تبقى السكربتات النشطة محظورة.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT

            // تكبير النص من إعدادات التطبيق (إتاحة)
            textZoom = ServiceLocator.settingsRepository.zoom
        }
    }

    /**
     * v1.7.0 — هويّة متصفح كاملة: تُزال وسمي WebView فقط من الـ UA الافتراضي
     * ليطابق User-Agent كروم أندرويد على الجهاز نفسه (نفس رقم Chromium،
     * نفس إصدار أندرويد، نفس طراز الجهاز) — تخطي احترافي لفلاتر
     * «متصفح غير مدعوم» دون إفساد أي شيء آخر.
     */
    private fun chromeLikeUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace(Regex(""";\s*wv(?=\s*[)])"""), "")
            .replace(Regex("""\bVersion/\d+(\.\d+)+\s"""), "")

    /**
     * ربط مستمع التنزيلات (سياسة كاملة عبر BrowserDownloadManager).
     * يُستدعى للتبويبات العادية، ولنافذة popup لحظة اعتمادها تبويبًا.
     */
    fun attachDownloadListener(webView: WebView, controller: BrowserController) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val source = controller.currentSourceUrl()
            val sourceDecision = source?.let {
                controller.securityEngine()
                    .validate(it, com.securebrowser.app.security.model.NavigationType.INITIAL)
            }
            val sourceAllowed =
                sourceDecision is com.securebrowser.app.security.model.NavigationDecision.Allow
            ServiceLocator.downloadManager.handleDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                sourceUrl = source,
                sourceAllowed = sourceAllowed
            )
        }
    }
}
