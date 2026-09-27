package com.securebrowser.app.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import com.securebrowser.app.security.model.NavigationType

/**
 * قشرة نافذة منبثقة (v1.4.0) — إصلاح جذري لمعمارية الـ popup.
 *
 * **لماذا كان v1.3.0 يفشل؟** كانت النافذة العابرة تُدمَّر فور أول حدث:
 *  - نافذة `about:blank` (نمط التسجيل/OAuth الأشهر: window.open ثم
 *    document.write أو POST نموذج أو إعادة توجيه) → تُدمَّر النافذة →
 *    الموقع يكتب في نافذة ميتة → صمت تام + مؤشر تحميل الموقع يدور للأبد.
 *  - نافذة `blob:` (ملفات مولّدة/معاينات طباعة) → تُسقط بلا عرض.
 *  - النافذة العابرة كانت WebView افتراضيًا (JS معطل) فلا تعمل حتى لو اعتُمدت.
 *
 * **المعمارية الجديدة — اعتماد النافذة:**
 * النافذة تُنشأ بالإعدادات المؤمَّنة الكاملة، وعند أول تنقل إطار رئيسي يُتخذ
 * القرار **متزامنًا** عبر SecurityEngine:
 *  - سماح → **تُعتمد نفس النافذة** تبويبًا حقيقيًا (handlePopupFirstNavigation)
 *    وينطلق التحميل الأصلي دون إلغاء ودون إعادة تحميل — يحفظ POST، وتبقى
 *    مرجعية window عند الموقع حية (document.write/التوجيه/postMessage تعمل)،
 *    ويعرض blob محتواه داخل التبويب المعتمد.
 *  - فتح تطبيق خارجي → بوابة التطبيقات الخارجية ثم تدمير النافذة.
 *  - حظر → تسجيل + شاشة حظر فورية (متزامنة، لا coroutine) ثم تدمير النافذة.
 *
 * بعد الاعتماد يستبدل BrowserActivity العملاء المؤقتين بالعملاء الحقيقيين
 * (SecureWebViewClient/SecureWebChromeClient بمعرف التبويب الجديد) — كل تنقل
 * لاحق داخل التبويب يمر عبر SecurityEngine كأي تبويب.
 */
class PopupWindowShell(
    context: Context,
    private val controller: BrowserController,
    private val isUserGesture: Boolean
) {

    private val adopted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var idleDestroyPosted = false

    // ————————————————— العميل المؤقت (قبل الاعتماد) —————————————————
    // تُعرَّف قبل webView لأن مُهيّئه يستخدمهما (ترتيب التهيئة في Kotlin).

    private val provisionalClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val raw = request.url?.toString() ?: return true
            if (!request.isForMainFrame) {
                // إطارات فرعية قبل الاعتماد: المخططات غير القابلة للعرض تُلغى دائمًا
                // (قرار الأمان إن حدث لاحقًا سيأتي من العميل الحقيقي بعد الاعتماد).
                val scheme = request.url?.scheme?.lowercase() ?: return true
                val renderable = scheme == "http" || scheme == "https" ||
                    scheme == "about" || scheme == "blob" || scheme == "data" ||
                    scheme == "ws" || scheme == "wss"
                return !renderable
            }
            val type = when {
                request.isRedirect -> NavigationType.REDIRECT
                isUserGesture -> NavigationType.POPUP
                else -> NavigationType.NEW_WINDOW
            }
            if (adopted.compareAndSet(false, true)) {
                // أول تنقل إطار رئيسي — القرار المصيري (متزامن)
                return decideFirstNavigation(raw, type)
            }
            // اعتماد سبق القرار له (نظريًا لا يحدث — التبديل متزامن على UI thread):
            // نفس قواعد العميل الحقيقي حتى لا يمر أي تنقل بلا فحص.
            val decision = controller.securityEngine().validate(raw, type)
            return controller.handleMainFrameDecision(view, decision, raw, type)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (adopted.get()) controller.verifyCommittedPage(view, url)
            // قبل الاعتماد: about:blank/blob لا يخفيان خطرًا — لا قرار مطلوب
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            val raw = url ?: return
            if (adopted.get()) {
                // تبديل العملاء يحدث داخل decideFirstNavigation على نفس الخيط —
                // هذا الحدث ينتمي للعميل الحقيقي إن وصلنا هنا بعد الاعتماد.
                controller.onPageStarted(view, raw)
                return
            }
            // window.open بدون رابط: أول ظهور يكون about:blank عبر onPageStarted
            // فقط (لا يمر بـ shouldOverrideUrlLoading) — اعتماد فوري كي يبقى
            // مرجع window حيًا للموقع (تسجيل/OAuth/document.write).
            val type = if (isUserGesture) NavigationType.POPUP else NavigationType.NEW_WINDOW
            if (adopted.compareAndSet(false, true)) {
                decideFirstNavigation(raw, type)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // fail-closed دائمًا
            handler.cancel()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // قبل الاعتماد: أخطاء النافذة العابرة لا تعني شيئًا للمستخدم بعد
            if (adopted.get() && request.isForMainFrame) {
                controller.onPageError(request.url?.toString(), error.description?.toString())
            }
        }
    }

    private val provisionalChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            // شبكة أمان: أي محتوى بدأ التحميل قبل onPageStarted → اعتماد فوري
            if (!adopted.get() && newProgress > 0) {
                val raw = view?.url ?: return
                if (raw.isNotBlank() &&
                    adopted.compareAndSet(false, true)
                ) {
                    val type = if (isUserGesture) NavigationType.POPUP else NavigationType.NEW_WINDOW
                    decideFirstNavigation(raw, type)
                }
            }
        }

        override fun onCloseWindow(window: WebView?) {
            // window.close() قبل الاعتماد → إسكات النافذة
            destroy()
        }
    }

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFocusableInTouchMode = true
        SecureWebViewFactory.applyHardenedSettings(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = provisionalChromeClient
        webViewClient = provisionalClient
    }

    // ————————————————— القرار المصيري —————————————————

    private fun decideFirstNavigation(raw: String, type: NavigationType): Boolean {
        cancelIdleDestroy()
        // قرار متزامن — بلا coroutine ولا refresh: النتيجة فورية دائمًا
        val decision = controller.securityEngine().validate(raw, type)
        val cancel = controller.handlePopupFirstNavigation(webView, raw, type, isUserGesture)
        if (cancel) destroy() // لم تُعتمد → تدمير فوري (المالك الجديد هو التبويب عند الاعتماد)
        return cancel
    }

    // ————————————————— دورة الحياة —————————————————

    /**
     * مؤقت إسكات: نافذة لم تُطلق أي تنقل/محتوى خلال المهلة (window.open
     * معلق تمامًا) تُدمَّر بلا أثر. تُلغى لحظة الاعتماد أو أول قرار.
     */
    fun scheduleIdleDestroy(timeoutMs: Long = IDLE_DESTROY_TIMEOUT_MS) {
        if (idleDestroyPosted || destroyed) return
        idleDestroyPosted = true
        mainHandler.postDelayed({
            idleDestroyPosted = false
            if (!destroyed && !adopted.get()) destroy()
        }, timeoutMs)
    }

    private fun cancelIdleDestroy() {
        if (idleDestroyPosted) {
            idleDestroyPosted = false
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancelIdleDestroy()
        try {
            webView.stopLoading()
        } catch (e: Exception) {
            // تجاهل
        }
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
        } catch (e: Exception) {
            // تجاهل
        }
        try {
            webView.destroy()
        } catch (e: Exception) {
            // تجاهل
        }
    }

    companion object {
        /** 25 ثانية: نافذة معلقة تمامًا (لا تنقل ولا محتوى) تُإسكات بعد المهلة. */
        const val IDLE_DESTROY_TIMEOUT_MS = 25_000L
    }
}
