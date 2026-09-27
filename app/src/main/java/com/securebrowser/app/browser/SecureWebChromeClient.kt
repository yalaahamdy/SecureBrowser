package com.securebrowser.app.browser

import android.app.AlertDialog
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.securebrowser.app.R

/**
 * عميل Chrome المؤمَّن — اعتراض النوافذ المنبثقة، ملء الشاشة، اختيار الملفات،
 * وصلاحيات الوسائط/الموقع (كلها مرفوضة افتراضيًا إلا ما تسمح به السياسة).
 */
class SecureWebChromeClient(
    private val tabId: Long,
    private val controller: BrowserController
) : WebChromeClient() {

    /**
     * اعتراض النوافذ المنبثقة (window.open / target=_blank / new-window JS).
     *
     * v1.4.0 — معمارية **اعتماد النافذة** عبر [PopupWindowShell]:
     * النافذة الجديدة تُنشأ بالإعدادات المؤمَّنة الكاملة، وعند أول تنقل إطار
     * رئيسي يُتخذ القرار متزامنًا عبر SecurityEngine: سماح → **نفس النافذة**
     * تُعتمد تبويبًا حقيقيًا ويستمر تحميلها الأصلي دون إلغاء (يحفظ POST،
     * وتبقى مرجع window حية لتسجيل الدخول/OAuth/document.write، ويعرض blob
     * الملفات المولّدة)، حظر → شاشة حظر فورية، خارجي → بوابة التطبيقات.
     *
     * كانت v1.3.0 تدمّر النافذة العابرة بعد أول حدث — فتنكسر نوافذ
     * about:blank/blob (تسجيل، ملفات مولّدة) ويدور مؤشر الموقع للأبد.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport
            ?: return true // لا يمكن تتبع النافذة → إسكاتها

        val shell = PopupWindowShell(view.context, controller, isUserGesture)
        transport.webView = shell.webView
        resultMsg.sendToTarget()

        // إسكات أي نافذة معلقة تمامًا (لا تنقل ولا محتوى) بعد المهلة
        shell.scheduleIdleDestroy()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        controller.closeTabIfPossible(tabId)
        super.onCloseWindow(window)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        controller.onProgressChanged(tabId, newProgress)
        super.onProgressChanged(view, newProgress)
    }

    // ——— رفع الملفات (HTML5 <input type=file>) ———
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        return controller.onShowFileChooser(webView, filePathCallback, fileChooserParams)
    }

    // ——— ملء الشاشة (الفيديو HTML5) ———
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        controller.enterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        controller.exitFullscreen()
        super.onHideCustomView()
    }

    // ——— صلاحيات حساسة: رفض افتراضي (سياسة المرحلة الأولى) ———
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    // ——— حوارات JavaScript بأسلوب أصلي ———
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        val context = view?.context
        if (context != null) {
            AlertDialog.Builder(context)
                .setMessage(message ?: "")
                .setPositiveButton(R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
        } else {
            result?.cancel()
        }
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        val context = view?.context
        if (context != null) {
            AlertDialog.Builder(context)
                .setMessage(message ?: "")
                .setPositiveButton(R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
        } else {
            result?.cancel()
        }
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        val context = view?.context
        if (context != null) {
            val input = android.widget.EditText(context).apply {
                setText(defaultValue ?: "")
            }
            AlertDialog.Builder(context)
                .setMessage(message ?: "")
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                .setNegativeButton(R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
        } else {
            result?.cancel()
        }
        return true
    }

}
