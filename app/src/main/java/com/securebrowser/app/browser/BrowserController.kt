package com.securebrowser.app.browser

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.securebrowser.app.security.SecurityEngine
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType

/**
 * العقد بين محرك الويب (WebView/Chromium) وبقية التطبيق.
 *
 * **القاعدة المعمارية:** الواجهة لا تقرر الأمن — تتعامل فقط مع عرض قرارات
 * [com.securebrowser.app.security.SecurityEngine].
 */
interface BrowserController {

    fun securityEngine(): SecurityEngine

    /** قرار تنقل الإطار الرئيسي. @return true لإلغاء تنقل WebView الأصلي. */
    fun handleMainFrameDecision(
        view: WebView,
        decision: NavigationDecision,
        rawUrl: String,
        type: NavigationType
    ): Boolean

    /** شبكة أمان لكل صفحة تُثبَّت في السجل (يشمل تنقلات JavaScript/السجل). */
    fun verifyCommittedPage(view: WebView, url: String?)

    /**
     * تنقل خارجي/غير قابل للعرض صدر من إطار فرعي (iframe) — v1.2.0.
     * يمر عبر SecurityEngine ثم بوابة التطبيقات الخارجية نفسها؛
     * يُسجَّل الحظر دون مقاطعة الصفحة بشاشة حظر.
     */
    fun handleSubframeExternal(rawUrl: String)

    fun onHistoryStateChanged(view: WebView, url: String?, isReload: Boolean)

    fun onPageStarted(view: WebView, url: String?)

    fun onPageFinished(view: WebView, url: String?, title: String?)

    fun onProgressChanged(tabId: Long, progress: Int)

    fun onSecurityError(url: String, reason: String)

    fun onPageError(url: String?, description: String?)

    /** window.open / target=_blank — اعتراض النوافذ المنبثقة. */
    fun handleCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean

    /**
     * أول تنقل لنافذة منبثقة (v1.4.0 — معمارية اعتماد النافذة):
     * قرار SecurityEngine متزامن — سماح → **نفس WebView النافذة** تُعتمد
     * تبويبًا حقيقيًا ويستمر تحميلها الأصلي (يحفظ POST ومرجع window الحي
     * لتسجيل الدخول/document.write/blob)؛ خارجي → بوابة التطبيقات؛
     * حظر → تسجيل + شاشة حظر.
     * @return true لإلغاء تحميل النافذة (حظر/خارجي/حد تبويبات) — ويعني ذلك
     *         مسؤولية المتصل عن تدميرها؛ false يعني الاعتماد (التبويب يملكها).
     */
    fun handlePopupFirstNavigation(
        webView: WebView,
        rawUrl: String,
        type: NavigationType,
        isUserGesture: Boolean
    ): Boolean

    fun closeTabIfPossible(tabId: Long)

    fun onShowFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean

    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)

    fun exitFullscreen()

    fun showBlockScreen(url: String?, host: String?, reason: BlockReason, type: NavigationType)

    fun recordBlockedNavigation(url: String?, reason: BlockReason, type: NavigationType)

    fun currentSourceUrl(): String?
}
