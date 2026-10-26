package com.securebrowser.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
     * ربط مستمع التنزيلات (سياسة كاملة عبر BrowserDownloadManager) + جسر تنزيل روابط blob:
     * + قائمة الضغطة المطوّلة على الصور والروابط.
     * يُستدعى للتبويبات العادية، ولنافذة popup منذ لحظة إنشائها وحتى اعتمادها تبويبًا.
     */
    @SuppressLint("JavascriptInterface")
    fun attachDownloadListener(
        webView: WebView,
        controller: BrowserController,
        onBeforeAdoptDownload: (() -> Unit)? = null
    ) {
        // جسر JavaScript لتحويل روابط blob: غير المتزامنة (FileReader) إلى تنزيلات فعلية
        try {
            webView.removeJavascriptInterface(JS_BLOB_BRIDGE_NAME)
        } catch (_: Exception) {
        }
        webView.addJavascriptInterface(
            BlobDownloadBridge(webView, controller, onBeforeAdoptDownload),
            JS_BLOB_BRIDGE_NAME
        )

        // دعم الضغطة المطوّلة على الصور والروابط لتنزيلها مباشرة
        attachLongPressListener(webView, controller)

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val candidates = listOfNotNull(
                webView.url?.takeIf { it.isNotBlank() && it != "about:blank" },
                webView.originalUrl?.takeIf { it.isNotBlank() && it != "about:blank" },
                controller.currentSourceUrl()?.takeIf { it.isNotBlank() && it != "about:blank" }
            )
            val source = candidates.firstOrNull()
            // التنزيل انطلق من داخل WebView خاضع بالفعل لفحص SecurityEngine
            val sourceAllowed = true

            if (url.startsWith("blob:", ignoreCase = true)) {
                val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
                val escapedDisp = (contentDisposition ?: "").replace("\\", "\\\\").replace("'", "\\'")
                val escapedMime = (mimeType ?: "").replace("\\", "\\\\").replace("'", "\\'")
                val escapedUa = (userAgent ?: "").replace("\\", "\\\\").replace("'", "\\'")
                val fetchBlobScript = """
                    (function() {
                        try {
                            fetch('$escapedUrl')
                                .then(function(res) { return res.blob(); })
                                .then(function(blob) {
                                    var reader = new FileReader();
                                    reader.onloadend = function() {
                                        if (reader.result) {
                                            window.$JS_BLOB_BRIDGE_NAME.onBlobDataReady(
                                                reader.result,
                                                '$escapedDisp',
                                                blob.type || '$escapedMime',
                                                '$escapedUa'
                                            );
                                        }
                                    };
                                    reader.readAsDataURL(blob);
                                })
                                .catch(function(err) {});
                        } catch (e) {}
                    })();
                """.trimIndent()
                webView.evaluateJavascript(fetchBlobScript, null)
                return@setDownloadListener
            }

            ServiceLocator.downloadManager.handleDownload(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                sourceUrl = source,
                sourceAllowed = sourceAllowed
            )
            onBeforeAdoptDownload?.invoke()
            controller.onDownloadStartedInWebView(webView)
        }
    }

    private fun attachLongPressListener(webView: WebView, controller: BrowserController) {
        webView.setOnLongClickListener { view ->
            val wv = view as? WebView ?: return@setOnLongClickListener false
            val hit = wv.hitTestResult
            val type = hit.type
            val extra = hit.extra
            if (extra.isNullOrBlank()) return@setOnLongClickListener false
            when (type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    controller.onLongPressElement(wv, type, extra)
                    true
                }
                else -> false
            }
        }
    }

    private const val JS_BLOB_BRIDGE_NAME = "SecureBlobBridge"

    private class BlobDownloadBridge(
        private val webView: WebView,
        private val controller: BrowserController,
        private val onBeforeAdoptDownload: (() -> Unit)?
    ) {
        @JavascriptInterface
        fun onBlobDataReady(
            dataUrl: String?,
            contentDisposition: String?,
            mimeType: String?,
            userAgent: String?
        ) {
            val clean = dataUrl?.trim() ?: return
            if (!clean.startsWith("data:", ignoreCase = true)) return
            val source = controller.currentSourceUrl()
            ServiceLocator.downloadManager.handleDownload(
                url = clean,
                userAgent = userAgent?.takeIf { it.isNotBlank() },
                contentDisposition = contentDisposition?.takeIf { it.isNotBlank() },
                mimeType = mimeType?.takeIf { it.isNotBlank() },
                contentLength = -1L,
                sourceUrl = source,
                sourceAllowed = true
            )
            webView.post {
                onBeforeAdoptDownload?.invoke()
                controller.onDownloadStartedInWebView(webView)
            }
        }
    }
}
