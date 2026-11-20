package com.securebrowser.app.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.youtube.YoutubeUrlParser
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * الحارس المدمج داخل WebView لوضع قنوات يوتيوب المسموحة (v1.15.0):
 *
 * 1) سكربت DocumentStart (يعمل قبل أي سكربت من يوتيوب نفسه) يغلف `window.fetch` و `XMLHttpRequest`
 *    لاعتراض طلبات POST إلى `/youtubei/v1/player` و `/youtubei/v1/reel/reel_item_watch` و `/youtubei/v1/browse`
 *    (لأن Android `WebResourceRequest` لا يوفّر قراءة POST body) والتحقق المتزامن عبر `@JavascriptInterface`.
 * 2) اعتراض الشبكة (`shouldInterceptRequest`) لقفل بث `googlevideo.com/videoplayback` و `/embed/ID`
 *    وطلبات البحث `/youtubei/v1/search` على مستوى Chromium.
 * 3) تحصين DOM (CSS + MutationObserver) لإخفاء شريط البحث، تبويبات الرئيسية/Explore/Shorts،
 *    الفيديوهات المقترحة، شاشة النهاية، التشغيل التلقائي، والتعليقات (حسب إعداد الوالد).
 */
object YoutubeWebViewGuard {

    private const val JS_BRIDGE_NAME = "SecureYouTubeGate"

    @Volatile
    private var consentSeeded = false

    private val attachedDocStartViews =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())

    fun attach(
        webView: WebView,
        tabProvider: () -> TabSession?,
        controller: BrowserController
    ) {
        ensureConsentCookie()

        try {
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        } catch (_: Exception) {
        }
        webView.addJavascriptInterface(
            YoutubeGateBridge(webView, tabProvider, controller),
            JS_BRIDGE_NAME
        )

        val needsDocStart = synchronized(attachedDocStartViews) {
            attachedDocStartViews.add(webView)
        }
        if (needsDocStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    DOCUMENT_START_SCRIPT,
                    setOf("https://*.youtube.com", "https://*.youtube-nocookie.com")
                )
            } catch (_: Exception) {
            }
        }
    }

    /** حقن احتياطي + تحديث قواعد CSS حسب إعدادات الوالد عند بدء/انتهاء الصفحة. */
    fun injectHardeningIfYoutube(webView: WebView, url: String?) {
        val policy = ServiceLocator.youtubeChannelPolicy
        if (!policy.isActive() || ServiceLocator.temporaryAccess.isActive()) return
        val host = url?.let {
            (UrlNormalizer.normalize(it) as? NormalizeResult.Success)?.url?.host
        } ?: return
        if (!YoutubeUrlParser.isYoutubeHost(host)) return

        val blockShorts = ServiceLocator.settingsRepository.youtubeBlockShorts
        val hideComments = ServiceLocator.settingsRepository.youtubeHideComments
        webView.evaluateJavascript(
            """
            $DOCUMENT_START_SCRIPT
            (function() {
                if (window.__sbApplyYoutubeCss) {
                    window.__sbApplyYoutubeCss($blockShorts, $hideComments);
                }
            })();
            """.trimIndent(),
            null
        )
    }

    /** إيقاف فوري لأي عنصر صوت/فيديو حي عند اكتشاف محاولة تخطي SPA. */
    fun killMediaPlayback(webView: WebView) {
        webView.post {
            try {
                webView.evaluateJavascript(
                    """
                    (function() {
                        try {
                            document.querySelectorAll('video, audio').forEach(function(m) {
                                try { m.pause(); m.removeAttribute('src'); m.srcObject = null; m.load(); } catch(e) {}
                            });
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * اعتراض شبكة Chromium (يعمل على الخيط الخلفي — لا تلمس WebView هنا إطلاقًا!).
     */
    fun interceptRequest(
        request: WebResourceRequest,
        tab: TabSession?
    ): WebResourceResponse? {
        if (!ServiceLocator.settingsRepository.youtubeChannelMode ||
            ServiceLocator.temporaryAccess.isActive()
        ) {
            return null
        }
        val uri = request.url ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val path = uri.path?.lowercase().orEmpty()

        // 1) حظر بث الفيديو المباشر (googlevideo.com/videoplayback) ما لم يصرّح Kotlin بفيديو حالي أو embed مصدّق
        if ((host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            path.contains("videoplayback")
        ) {
            val hasAuthorizedMain = tab?.authorizedYoutubeVideoId != null
            val hasAuthorizedEmbed = tab?.authorizedEmbedVideoIds?.isNotEmpty() == true
            if (!hasAuthorizedMain && !hasAuthorizedEmbed) {
                return blockedJsonResponse()
            }
            return null
        }

        if (!YoutubeUrlParser.isYoutubeHost(host)) return null

        // 2) حظر endpoints البحث والقائمة الجانبية على مستوى الشبكة
        if (path.startsWith("/youtubei/v1/search") ||
            path.startsWith("/youtubei/v1/guide")
        ) {
            return blockedJsonResponse()
        }

        // 3) اعتراض جميع إطارات التضمين (/embed/ID, /v/ID, /e/ID) على أي موقع (حتى داخل المواقع التعليمية المسموحة!)
        val parsed = (UrlNormalizer.normalize(uri.toString()) as? NormalizeResult.Success)?.url
        if (parsed != null) {
            when (val target = YoutubeUrlParser.classify(parsed)) {
                is YoutubeUrlParser.Target.Video -> {
                    if (target.isEmbed) {
                        if (target.isShort && ServiceLocator.settingsRepository.youtubeBlockShorts) {
                            return blockedEmbedHtmlResponse()
                        }
                        val ok = ServiceLocator.youtubeChannelVerifier.verifyVideoAllowedBlocking(target.videoId)
                        if (ok) {
                            tab?.authorizedEmbedVideoIds?.add(target.videoId)
                            return null
                        } else {
                            recordNetworkBlock(uri.toString())
                            return blockedEmbedHtmlResponse()
                        }
                    }
                }
                else -> Unit
            }
        }

        return null
    }

    private fun recordNetworkBlock(url: String) {
        ServiceLocator.applicationScope.launch {
            ServiceLocator.blockedActivityRepository.record(
                url = url.take(2048),
                host = "youtube.com",
                reason = BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED.name,
                navigationType = NavigationType.LINK.name
            )
        }
    }

    private fun ensureConsentCookie() {
        if (consentSeeded) return
        consentSeeded = true
        try {
            val cm = CookieManager.getInstance()
            cm.setCookie("https://www.youtube.com", "CONSENT=YES+cb.20210328-17-p0.en+FX+667; Path=/; Secure")
            cm.setCookie("https://m.youtube.com", "CONSENT=YES+cb.20210328-17-p0.en+FX+667; Path=/; Secure")
            cm.setCookie("https://www.youtube.com", "SOCS=CAI; Path=/; Secure")
            cm.setCookie("https://m.youtube.com", "SOCS=CAI; Path=/; Secure")
        } catch (_: Exception) {
        }
    }

    private fun blockedJsonResponse(): WebResourceResponse = WebResourceResponse(
        "application/json",
        "UTF-8",
        403,
        "Forbidden",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))
    )

    private fun blockedEmbedHtmlResponse(): WebResourceResponse {
        val html = """
            <!doctype html>
            <html dir="auto"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#1c1b1f;color:#f4eff4;font-family:sans-serif;text-align:center;padding:16px;box-sizing:border-box;}
              .box{max-width:340px;padding:18px;border-radius:14px;background:#2b2930;border:1px solid #49454f;}
              .t{font-weight:700;font-size:15px;margin-bottom:6px;color:#ffb4ab;}
              .s{font-size:13px;opacity:.85;line-height:1.5;}
            </style></head>
            <body><div class="box"><div class="t">فيديو يوتيوب محظور</div><div class="s">هذا الفيديو تابع لقناة غير مضافة في قائمة القنوات المسموحة.</div></div></body></html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }

    @SuppressLint("JavascriptInterface")
    private class YoutubeGateBridge(
        private val webView: WebView,
        private val tabProvider: () -> TabSession?,
        private val controller: BrowserController
    ) {

        @JavascriptInterface
        fun isStrictModeActive(): Boolean =
            ServiceLocator.settingsRepository.youtubeChannelMode &&
                !ServiceLocator.temporaryAccess.isActive()

        @JavascriptInterface
        fun shouldBlockShorts(): Boolean =
            ServiceLocator.settingsRepository.youtubeBlockShorts

        @JavascriptInterface
        fun shouldHideComments(): Boolean =
            ServiceLocator.settingsRepository.youtubeHideComments

        /**
         * يتحقق بشكل متزامن (على Binder thread الخلفي) قبل خروج طلب `/youtubei/v1/player`
         * أو `/youtubei/v1/reel/reel_item_watch` ليحصل WebView أو يرفض قبل جلب البث نهائيًا.
         */
        @JavascriptInterface
        fun verifyPlayerSync(videoId: String?, isReel: Boolean): Boolean {
            if (!isStrictModeActive()) return true
            if (videoId.isNullOrBlank()) return false
            if (isReel && ServiceLocator.settingsRepository.youtubeBlockShorts) {
                clearTabAuthorizationAndKill()
                return false
            }
            val allowed = ServiceLocator.youtubeChannelVerifier.verifyVideoAllowedBlocking(videoId)
            val tab = tabProvider()
            if (allowed) {
                tab?.authorizedYoutubeVideoId = videoId
            } else {
                tab?.authorizedYoutubeVideoId = null
                killMediaPlayback(webView)
                ServiceLocator.applicationScope.launch {
                    ServiceLocator.blockedActivityRepository.record(
                        url = "https://www.youtube.com/watch?v=$videoId",
                        host = "youtube.com",
                        reason = BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED.name,
                        navigationType = NavigationType.JS.name
                    )
                }
            }
            return allowed
        }

        /**
         * يتحقق من طلبات `/youtubei/v1/browse` داخل SPA لمنع تحميل الرئيسية/Explore/Shorts/قنوات أخرى.
         */
        @JavascriptInterface
        fun isBrowseAllowedSync(browseId: String?): Boolean {
            if (!isStrictModeActive()) return true
            val id = browseId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (id.startsWith("FE")) {
                // FEwhat_to_watch, FEexplore, FEshorts, FEtrending, FEsubscriptions...
                return false
            }
            if (YoutubeUrlParser.isValidChannelId(id)) {
                return id in ServiceLocator.youtubeChannelRepository.enabledChannelIds()
            }
            // قوائم تشغيل (VL/PL) داخل قناة مسموحة مسموحة كواجهة، وكل فيديو يُفتح منها يمر عبر verifyPlayerSync
            return id.startsWith("VL") || id.startsWith("PL")
        }

        @JavascriptInterface
        fun onSurfaceBlocked(url: String?, reason: String?) {
            if (!isStrictModeActive()) return
            clearTabAuthorizationAndKill()
            val targetUrl = url?.takeIf { it.isNotBlank() } ?: "https://m.youtube.com/"
            controller.recordBlockedNavigation(
                targetUrl,
                BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED,
                NavigationType.JS
            )
            controller.showBlockScreen(
                targetUrl,
                reason ?: "youtube_blocked",
                BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED,
                NavigationType.JS
            )
        }

        private fun clearTabAuthorizationAndKill() {
            tabProvider()?.authorizedYoutubeVideoId = null
            killMediaPlayback(webView)
        }
    }

    private val DOCUMENT_START_SCRIPT = """
        (function() {
            if (window.__sbYoutubeGuardInstalled) return;
            window.__sbYoutubeGuardInstalled = true;

            function gate() { return window.$JS_BRIDGE_NAME; }
            function isStrict() {
                try { var g = gate(); return !!(g && g.isStrictModeActive()); } catch (e) { return false; }
            }

            function safeUrl(input) {
                try {
                    if (typeof input === 'string') return input;
                    if (input && typeof input.url === 'string') return input.url;
                } catch (e) {}
                return '';
            }

            function inspectApiBody(url, bodyStr) {
                try {
                    if (!isStrict()) return true;
                    var u = String(url || '');
                    if (u.indexOf('/youtubei/v1/search') !== -1 || u.indexOf('/youtubei/v1/guide') !== -1) {
                        return false;
                    }
                    if (u.indexOf('/youtubei/v1/player') !== -1 || u.indexOf('/youtubei/v1/reel/reel_item_watch') !== -1) {
                        var isReel = u.indexOf('reel_item_watch') !== -1;
                        var vid = null;
                        if (typeof bodyStr === 'string' && bodyStr.length > 0) {
                            try {
                                var json = JSON.parse(bodyStr);
                                vid = json.videoId || (json.playerRequest && json.playerRequest.videoId) || null;
                            } catch (e) {
                                var m = /"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"/.exec(bodyStr);
                                if (m) vid = m[1];
                            }
                        }
                        if (!vid) return false;
                        return !!gate().verifyPlayerSync(String(vid), isReel);
                    }
                    if (u.indexOf('/youtubei/v1/browse') !== -1) {
                        if (typeof bodyStr === 'string' && bodyStr.length > 0) {
                            try {
                                var bj = JSON.parse(bodyStr);
                                if (bj && bj.browseId) {
                                    return !!gate().isBrowseAllowedSync(String(bj.browseId));
                                }
                            } catch (e) {}
                        }
                    }
                } catch (err) {
                    return false;
                }
                return true;
            }

            function bodyToText(body) {
                try {
                    if (!body) return '';
                    if (typeof body === 'string') return body;
                    if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
                        return new TextDecoder('utf-8').decode(body);
                    }
                } catch (e) {}
                return '';
            }

            function blockedFetchResponse() {
                return Promise.resolve(new Response('{}', {
                    status: 403,
                    statusText: 'Blocked by Parental Policy',
                    headers: { 'Content-Type': 'application/json' }
                }));
            }

            var origFetch = window.fetch;
            if (typeof origFetch === 'function') {
                window.fetch = function(input, init) {
                    var u = safeUrl(input);
                    if (isStrict() && u.indexOf('/youtubei/v1/') !== -1) {
                        var self = this;
                        var args = arguments;
                        if (init && init.body) {
                            var bodyText = bodyToText(init.body);
                            if (!inspectApiBody(u, bodyText)) {
                                return blockedFetchResponse();
                            }
                            return origFetch.apply(self, args);
                        }
                        if (typeof Request !== 'undefined' && input instanceof Request) {
                            return input.clone().text().then(function(txt) {
                                if (!inspectApiBody(u, txt || '')) {
                                    return blockedFetchResponse();
                                }
                                return origFetch.apply(self, args);
                            }, function() {
                                return blockedFetchResponse();
                            });
                        }
                        if (!inspectApiBody(u, '')) {
                            return blockedFetchResponse();
                        }
                    }
                    return origFetch.apply(this, arguments);
                };
            }

            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__sbUrl = String(url || '');
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(body) {
                var u = this.__sbUrl || '';
                if (isStrict() && u.indexOf('/youtubei/v1/') !== -1) {
                    var bodyStr = bodyToText(body);
                    if (!inspectApiBody(u, bodyStr)) {
                        try { this.abort(); } catch (e) {}
                        return;
                    }
                }
                return origSend.apply(this, arguments);
            };

            window.__sbApplyYoutubeCss = function(blockShorts, hideComments) {
                try {
                    if (!isStrict()) return;
                    var id = '__sb_yt_hardening_css';
                    var el = document.getElementById(id);
                    if (!el) {
                        el = document.createElement('style');
                        el.id = id;
                        (document.head || document.documentElement).appendChild(el);
                    }
                    var css = [
                        'ytm-pivot-bar-renderer, ytm-pivot-bar-item-renderer { display: none !important; }',
                        'ytm-searchbox, button[aria-label*="Search"], button[aria-label*="بحث"], .mobile-topbar-header-endpoint[href="/"], a#logo, ytm-home-logo { display: none !important; }',
                        '#secondary, #related, ytm-item-section-renderer[section-identifier="related-items"], ytm-watch-next-secondary-results-renderer { display: none !important; }',
                        '.ytp-endscreen-content, .html5-endscreen, .ytp-ce-element, .ytp-autonav-toggle-button-container, button[data-tooltip-target-id="ytp-autonav-toggle-button"] { display: none !important; }',
                        'ytm-reel-shelf-renderer { ' + (blockShorts ? 'display: none !important;' : '') + ' }',
                        'ytm-comments-entry-point-header-renderer, ytm-comment-section-renderer, #comments { ' + (hideComments ? 'display: none !important;' : '') + ' }'
                    ].join('\n');
                    el.textContent = css;
                } catch (e) {}
            };

            document.addEventListener('DOMContentLoaded', function() {
                try {
                    var g = gate();
                    if (g && g.isStrictModeActive()) {
                        window.__sbApplyYoutubeCss(g.shouldBlockShorts(), g.shouldHideComments());
                    }
                } catch (e) {}
            });
        })();
    """.trimIndent()
}
