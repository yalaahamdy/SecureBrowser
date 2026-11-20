package com.securebrowser.app.security.youtube

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.ParsedUrl
import com.securebrowser.app.core.url.UrlNormalizer
import java.net.URLDecoder

/**
 * محلل روابط يوتيوب النقي (Pure Kotlin — Android-free).
 *
 * يُغطي جميع النطاقات الرسمية والمختصرة والمضمنة، وجميع صيغ الفيديو والقنوات،
 * ويكشف مسارات التحويل المفتوحة (/redirect, /attribution_link) والأسطح المحظورة
 * (الصفحة الرئيسية، البحث، التغذيات، Shorts العامة).
 */
object YoutubeUrlParser {

    val YOUTUBE_HOSTS: Set<String> = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
        "youtube.googleapis.com",
        "youtu.be",
        "www.youtu.be"
    )

    /** نطاق الموافقة التقنية للكوكيز فقط (يُقيّد بمسارات الموافقة). */
    const val CONSENT_HOST = "consent.youtube.com"

    private val CHANNEL_ID_REGEX = Regex("""^UC[A-Za-z0-9_-]{22}$""")
    private val VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11}$""")

    sealed class Target {
        /** فيديو محدد (watch / shorts / live / embed / v / e / youtu.be). */
        data class Video(
            val videoId: String,
            val isShort: Boolean = false,
            val isEmbed: Boolean = false,
            val isLive: Boolean = false
        ) : Target()

        /** قناة بمعرّفها الثابت (/channel/UC...). */
        data class ChannelById(val channelId: String) : Target()

        /** قناة باسمها المستعار (/@handle). */
        data class ChannelByHandle(val handle: String) : Target()

        /** معرف قناة قديم أو مخصص (/c/name أو /user/name) — يُحلّ إلى UC... عبر الشبكة. */
        data class LegacyChannelPath(val path: String) : Target()

        /** إعادة توجيه داخل يوتيوب (/redirect?q=... أو /attribution_link?u=...). */
        data class RedirectWrapper(val targetUrl: String?) : Target()

        /** صفحة موافقة الكوكيز التقنية على consent.youtube.com. */
        data class ConsentPage(val continueUrl: String?) : Target()

        /** سطح محظور دائمًا في وضع القنوات (الرئيسية، البحث، Explore، Trending، Shorts عامة). */
        data class ForbiddenSurface(val reason: String) : Target()

        /** رابط لا ينتمي ليوتيوب. */
        data object NonYoutube : Target()
    }

    fun isYoutubeHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h in YOUTUBE_HOSTS || h == CONSENT_HOST || h.endsWith(".youtube.com") || h.endsWith(".youtube-nocookie.com")
    }

    fun isValidChannelId(id: String?): Boolean = !id.isNullOrBlank() && CHANNEL_ID_REGEX.matches(id.trim())

    fun isValidVideoId(id: String?): Boolean = !id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id.trim())

    fun classify(parsed: ParsedUrl): Target {
        if (!parsed.isWeb) return Target.NonYoutube
        val host = parsed.host.lowercase().trimEnd('.')
        if (!isYoutubeHost(host)) return Target.NonYoutube

        val path = parsed.path
        val pathLower = path.lowercase()
        val query = parsed.query.orEmpty()

        if (host == CONSENT_HOST) {
            val cont = queryParam(query, "continue")
            return Target.ConsentPage(cont)
        }

        // نطاق youtu.be/VIDEO_ID المختصر
        if (host == "youtu.be" || host == "www.youtu.be") {
            val seg = path.trim('/').substringBefore('/').trim()
            return if (isValidVideoId(seg)) {
                Target.Video(videoId = seg)
            } else {
                Target.ForbiddenSurface("youtu_be_home_or_invalid")
            }
        }

        // معالجة إعادة التوجيه (/redirect و /attribution_link)
        if (pathLower == "/redirect" || pathLower.startsWith("/redirect/")) {
            val q = queryParam(query, "q")
            return Target.RedirectWrapper(q)
        }
        if (pathLower == "/attribution_link" || pathLower.startsWith("/attribution_link/")) {
            val u = queryParam(query, "u")
            val resolved = if (u != null && u.startsWith("/")) "https://www.youtube.com$u" else u
            return Target.RedirectWrapper(resolved)
        }

        // مسارات الفيديو المختلفة
        val vParam = queryParam(query, "v")
        if (pathLower == "/watch" || pathLower.startsWith("/watch/")) {
            return if (isValidVideoId(vParam)) {
                Target.Video(videoId = vParam!!)
            } else {
                Target.ForbiddenSurface("watch_without_valid_v")
            }
        }
        if (pathLower.startsWith("/shorts/")) {
            val id = path.removePrefix("/shorts/").removePrefix("/Shorts/")
                .substringBefore('/').substringBefore('?').trim()
            return if (isValidVideoId(id)) {
                Target.Video(videoId = id, isShort = true)
            } else {
                Target.ForbiddenSurface("shorts_feed")
            }
        }
        if (pathLower.startsWith("/live/")) {
            val id = path.substringAfter("/live/", "")
                .substringBefore('/').substringBefore('?').trim()
            return if (isValidVideoId(id)) {
                Target.Video(videoId = id, isLive = true)
            } else {
                Target.ForbiddenSurface("live_feed")
            }
        }
        if (pathLower.startsWith("/embed/")) {
            val id = path.substringAfter("/embed/", "")
                .substringBefore('/').substringBefore('?').trim()
            return if (isValidVideoId(id)) {
                Target.Video(videoId = id, isEmbed = true)
            } else {
                Target.ForbiddenSurface("embed_invalid")
            }
        }
        if (pathLower.startsWith("/v/") || pathLower.startsWith("/e/")) {
            val id = path.drop(3).substringBefore('/').substringBefore('?').trim()
            return if (isValidVideoId(id)) {
                Target.Video(videoId = id, isEmbed = true)
            } else {
                Target.ForbiddenSurface("legacy_video_invalid")
            }
        }

        // مسارات القنوات المباشرة (/channel/UC...)
        if (pathLower.startsWith("/channel/")) {
            val rest = path.substringAfter("/channel/", "")
            val id = rest.substringBefore('/').substringBefore('?').trim()
            val sub = rest.substringAfter('/', "").lowercase()
            // منع استغلال تبويب channels داخل صفحة القناة إن وُجد للخروج لقنوات غير مسموحة?
            // نسمح بصفحات القناة نفسها (/videos, /streams, /playlists, /about, /shorts, /community, /featured)
            if (isValidChannelId(id)) {
                if (sub == "shorts" || sub.startsWith("shorts/")) {
                    // يُسمح بمراقبتها عبر YoutubeChannelPolicy حسب إعداد youtubeBlockShorts
                }
                return Target.ChannelById(id)
            }
            return Target.ForbiddenSurface("invalid_channel_id")
        }

        // مسار المعرف المستعار /@handle (أو /@handle/videos ...)
        if (path.startsWith("/@")) {
            val handleSeg = path.substring(1).substringBefore('/').substringBefore('?').trim()
            if (handleSeg.length >= 3) {
                return Target.ChannelByHandle(handleSeg.lowercase())
            }
            return Target.ForbiddenSurface("invalid_handle")
        }

        // مسارات القنوات القديمة (/c/name أو /user/name)
        if (pathLower.startsWith("/c/") || pathLower.startsWith("/user/")) {
            val seg = path.trim('/').substringBefore('/').lowercase()
            val name = path.trim('/').substringAfter('/').substringBefore('/').trim()
            if (name.isNotBlank()) {
                return Target.LegacyChannelPath("/$seg/$name")
            }
            return Target.ForbiddenSurface("invalid_legacy_channel")
        }

        // الأسطح المحظورة صراحة
        if (pathLower.isEmpty() || pathLower == "/") {
            return Target.ForbiddenSurface("youtube_home")
        }
        if (pathLower == "/shorts" || pathLower == "/results" || pathLower == "/search" ||
            pathLower.startsWith("/feed") || pathLower.startsWith("/explore") ||
            pathLower.startsWith("/gaming") || pathLower.startsWith("/trending") ||
            pathLower.startsWith("/playlist") || pathLower.startsWith("/hashtag") ||
            pathLower.startsWith("/podcasts") || pathLower.startsWith("/live")
        ) {
            return Target.ForbiddenSurface("youtube_surface:${pathLower.trim('/').substringBefore('/')}")
        }

        // Fail-Closed: أي مسار آخر غير مصنّف (باستثناء موارد تقنية لا تصل عادة كصفحة رئيسية)
        return Target.ForbiddenSurface("youtube_unclassified_path:${pathLower.take(40)}")
    }

    fun classifyRaw(rawUrl: String?): Target {
        val r = UrlNormalizer.normalize(rawUrl) as? NormalizeResult.Success ?: return Target.NonYoutube
        return classify(r.url)
    }

    fun queryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k == key) {
                val rawVal = pair.substring(eq + 1)
                return try {
                    URLDecoder.decode(rawVal, "UTF-8").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    rawVal.takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }
}
