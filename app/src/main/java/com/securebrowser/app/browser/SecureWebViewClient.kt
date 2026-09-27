package com.securebrowser.app.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * عميل WebView المؤمَّن — نقطة الاعتراض الأولى لكل تنقل من محرك Chromium.
 *
 * طبقات الدفاع:
 * 1. shouldOverrideUrlLoading (روابط + إعادة توجيه HTTP) → SecurityEngine.
 * 2. doUpdateVisitedHistory (تنقلات JS location / سجل / إعادة توجيه متأخرة) → شبكة أمان.
 * 3. onPageStarted → تحقق إضافي.
 * 4. onReceivedSslError → إلغاء دائم (fail-closed، لا استثناءات).
 */
class SecureWebViewClient(
    private val tabId: Long,
    private val controller: BrowserController
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val raw = request.url?.toString() ?: return true
        if (!request.isForMainFrame) {
            // موارد وإطارات الصفحة المسموحة تُحمَّل بحرية — لكن أي مخطط لا يستطيع
            // Chromium عرضه (tg:// intent:// vlc:// javascript: …) داخل إطار فرعي
            // كان يُترك للمحرك فتبقى الصفحة "تحمّل" للأبد (تسجيل دخول تلجرام عبر iframe
            // مثلًا). الآن: يُوجَّه إلى قرار الأمان ثم يُلغى التحميل دائمًا (return true).
            val scheme = request.url?.scheme?.lowercase() ?: return true
            val renderable = scheme == "http" || scheme == "https" ||
                scheme == "about" || scheme == "blob" || scheme == "data" ||
                scheme == "ws" || scheme == "wss"
            if (!renderable) {
                controller.handleSubframeExternal(raw)
                return true
            }
            return false
        }
        val type =
            if (request.isRedirect) com.securebrowser.app.security.model.NavigationType.REDIRECT
            else com.securebrowser.app.security.model.NavigationType.LINK
        val decision = controller.securityEngine().validate(raw, type)
        return controller.handleMainFrameDecision(view, decision, raw, type)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        // شبكة الأمان: يشمل JS location.*, history.pushState، إعادة توجيه متأخرة
        controller.verifyCommittedPage(view, url)
        controller.onHistoryStateChanged(view, url, isReload)
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        controller.onPageStarted(view, url)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        controller.onPageFinished(view, url, view.title)
        super.onPageFinished(view, url)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // fail-closed: لا تجاوز لشهادة غير سليمة مهما كان السبب
        handler.cancel()
        controller.onSecurityError(error.url ?: "", "SSL_ERROR")
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            controller.onPageError(request.url?.toString(), error.description?.toString())
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?
    ) {
        // المرحلة الأولى: لا نوثيق HTTP أساسي (قرار سياسة)
        handler.cancel()
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }
}
