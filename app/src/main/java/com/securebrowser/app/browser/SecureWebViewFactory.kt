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

        // كوكيز: بدون أطراف ثالثة
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

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
            // السماح لـ JS بطلب النوافذ — الاعتراض الأمني وحده هو الحاجز:
            // كل نافذة (بإيماءة أو بلاها) تمر عبر onCreateWindow → SecurityEngine.
            // تعطيل هذا يسقط window.open غير الإيمائي (فتح ملفات/طباعة/تسجيل
            // OAuth بعد استجابة شبكة) بصمت قبل أن يصل إلى الاعتراض إطلاقًا.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            // أمان
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT

            // تكبير النص من إعدادات التطبيق (إتاحة)
            textZoom = ServiceLocator.settingsRepository.zoom
        }
    }

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
