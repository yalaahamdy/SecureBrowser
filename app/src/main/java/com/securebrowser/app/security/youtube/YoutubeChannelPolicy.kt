package com.securebrowser.app.security.youtube

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.ParsedUrl
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision

/**
 * محرك قرارات قنوات يوتيوب (Pure Kotlin — Fail-Closed).
 *
 * يعتمد على لقطات القنوات المسموحة وكاش الذاكرة المتزامن فقط:
 * - مسموح فورًا (`Allow`): قناة `/channel/UC...` مفعّلة، أو `@handle` مسجّل، أو فيديو `videoId`
 *   مُطابق في الكاش مع قناة مفعّلة (مع مراعاة خيار حظر Shorts).
 * - انتظار تحقق (`PendingVerification`): فيديو `videoId` أو `@handle` أو `/c/...` غير موجود بالكاش بعد
 *   (لا يُحمَّل في WebView إلا بعد التحقق على الخيط الخلفي).
 * - محظور فورًا (`Block`): الصفحة الرئيسية/البحث/Shorts غير المسموحة/فيديو أو قناة أو إعادة توجيه غير مسموحة.
 */
class YoutubeChannelPolicy(
    private val modeEnabled: () -> Boolean,
    private val blockShorts: () -> Boolean,
    private val enabledChannelIds: () -> Set<String>,
    private val channelIdForHandle: (String) -> String?,
    private val cachedChannelForVideo: (String) -> String?
) {

    fun isActive(): Boolean = modeEnabled()

    fun evaluate(
        parsed: ParsedUrl,
        depth: Int = 0,
        nonYoutubeRedirectValidator: ((String) -> NavigationDecision)? = null
    ): NavigationDecision? {
        if (!modeEnabled()) return null
        val target = YoutubeUrlParser.classify(parsed)
        if (target is YoutubeUrlParser.Target.NonYoutube) return null

        val allowedIds = enabledChannelIds()
        if (allowedIds.isEmpty()) {
            // وضع القنوات مفعّل لكن لم يضف الوالد أي قناة بعد → حظر يوتيوب بالكامل fail-closed
            return block("youtube_no_channels_configured", parsed.raw)
        }

        return when (target) {
            is YoutubeUrlParser.Target.NonYoutube -> null

            is YoutubeUrlParser.Target.ForbiddenSurface ->
                block(target.reason, parsed.raw)

            is YoutubeUrlParser.Target.ConsentPage -> {
                val cont = target.continueUrl
                if (cont.isNullOrBlank()) {
                    NavigationDecision.Allow(parsed)
                } else {
                    val norm = UrlNormalizer.normalize(cont)
                    if (norm is NormalizeResult.Success && YoutubeUrlParser.isYoutubeHost(norm.url.host)) {
                        NavigationDecision.Allow(parsed)
                    } else {
                        block("youtube_consent_invalid_continue", parsed.raw)
                    }
                }
            }

            is YoutubeUrlParser.Target.RedirectWrapper -> {
                if (depth >= MAX_REDIRECT_DEPTH || target.targetUrl.isNullOrBlank()) {
                    return block("youtube_redirect_blocked", parsed.raw)
                }
                val norm = UrlNormalizer.normalize(target.targetUrl)
                if (norm !is NormalizeResult.Success) {
                    return block("youtube_redirect_malformed", parsed.raw)
                }
                // إن كانت الوجهة داخل يوتيوب تُقيّم مجددًا ضمن YoutubeChannelPolicy؛
                // وإن كانت خارج يوتيوب تمر وجوبًا عبر SecurityEngine الكامل (fail-closed إن غاب).
                evaluate(norm.url, depth + 1, nonYoutubeRedirectValidator)
                    ?: nonYoutubeRedirectValidator?.invoke(norm.url.canonical())
                    ?: block("youtube_redirect_unvalidated", parsed.raw)
            }

            is YoutubeUrlParser.Target.ChannelById -> {
                if (target.channelId !in allowedIds) {
                    return block("channel:${target.channelId}", parsed.raw)
                }
                if (blockShorts() && parsed.path.lowercase().contains("/shorts")) {
                    return block("youtube_shorts_blocked", parsed.raw)
                }
                NavigationDecision.Allow(parsed)
            }

            is YoutubeUrlParser.Target.ChannelByHandle -> {
                val mappedId = channelIdForHandle(target.handle)
                if (mappedId != null && mappedId in allowedIds) {
                    if (blockShorts() && parsed.path.lowercase().contains("/shorts")) {
                        return block("youtube_shorts_blocked", parsed.raw)
                    }
                    NavigationDecision.Allow(parsed)
                } else {
                    NavigationDecision.PendingVerification(
                        url = parsed,
                        handle = target.handle
                    )
                }
            }

            is YoutubeUrlParser.Target.LegacyChannelPath -> {
                NavigationDecision.PendingVerification(
                    url = parsed,
                    legacyChannelPath = target.path
                )
            }

            is YoutubeUrlParser.Target.Video -> {
                if (target.isShort && blockShorts()) {
                    return block("youtube_shorts_blocked", parsed.raw)
                }
                val cachedChannel = cachedChannelForVideo(target.videoId)
                if (cachedChannel != null) {
                    if (cachedChannel in allowedIds) {
                        NavigationDecision.Allow(parsed)
                    } else {
                        block("video:${target.videoId}:channel:$cachedChannel", parsed.raw)
                    }
                } else {
                    NavigationDecision.PendingVerification(
                        url = parsed,
                        videoId = target.videoId
                    )
                }
            }
        }
    }

    private fun block(detail: String, rawUrl: String?): NavigationDecision.Block =
        NavigationDecision.Block(
            reason = BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED,
            detail = detail,
            rawUrl = rawUrl
        )

    companion object {
        private const val MAX_REDIRECT_DEPTH = 3
    }
}
