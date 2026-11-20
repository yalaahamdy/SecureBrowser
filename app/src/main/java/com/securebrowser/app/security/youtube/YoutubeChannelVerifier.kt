package com.securebrowser.app.security.youtube

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.data.repository.YoutubeChannelRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * مُحلِّل ومتحقق قنوات يوتيوب متعدد المراحل (Fail-Closed).
 *
 * المراحل للتحقق من `videoId -> channelId`:
 *  0) كاش الذاكرة ثم Room (`youtube_video_cache`، TTL = 7 أيام).
 *  1) استعلام `youtube.com/oembed` الخفيف — سريع (~150ms)؛ يُستخدم لتسريع القنوات التي يطابق
 *     `author_url` فيها handle محفوظًا، ثم يُؤكَّد أو يُتخطى عند 401/403 (فيديوهات منع التضمين).
 *  2) قراءة HTML متدفقة محدودة السقف (<= 350 KB، تتوقف فورًا عند أول تطابق) لاستخراج المعرّف الثابت `UC...`.
 *  3) Fail-Closed: أي فشل شبكة أو انتهاء مهلة أو تعذر استخراج `UC...` = حظر فوري.
 */
class YoutubeChannelVerifier(
    private val repository: YoutubeChannelRepository,
    private val settings: SettingsRepository
) {

    data class YoutubeChannelInfo(
        val channelId: String,
        val handle: String?,
        val title: String,
        val avatarUrl: String?
    )

    sealed class VideoVerifyOutcome {
        data class Allowed(val videoId: String, val channelId: String) : VideoVerifyOutcome()
        data class Blocked(val videoId: String, val channelId: String?, val reason: String) : VideoVerifyOutcome()
    }

    sealed class ChannelResolveResult {
        data class Success(val info: YoutubeChannelInfo) : ChannelResolveResult()
        data class Failure(val reason: String) : ChannelResolveResult()
    }

    sealed class HandleVerifyOutcome {
        data class Allowed(val channelId: String) : HandleVerifyOutcome()
        data class Blocked(val channelId: String?, val reason: String) : HandleVerifyOutcome()
    }

    /** توحيد الطلبات المتزامنة لنفس `videoId` منعًا لتكرار الشبكة. */
    private val inFlightVideos = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    suspend fun verifyVideoAllowed(videoId: String): VideoVerifyOutcome {
        if (!YoutubeUrlParser.isValidVideoId(videoId)) {
            return VideoVerifyOutcome.Blocked(videoId, null, "invalid_video_id")
        }
        val allowedIds = repository.enabledChannelIds()
        if (allowedIds.isEmpty()) {
            return VideoVerifyOutcome.Blocked(videoId, null, "no_channels_configured")
        }
        val channelId = resolveChannelIdForVideo(videoId)
            ?: return VideoVerifyOutcome.Blocked(videoId, null, "channel_lookup_failed")

        return if (channelId in allowedIds) {
            VideoVerifyOutcome.Allowed(videoId, channelId)
        } else {
            VideoVerifyOutcome.Blocked(videoId, channelId, "channel:$channelId")
        }
    }

    /**
     * يُستدعى فقط من خيوط خلفية (خيط Binder لـ @JavascriptInterface أو خيط IO لـ shouldInterceptRequest).
     */
    fun verifyVideoAllowedBlocking(videoId: String): Boolean {
        if (!YoutubeUrlParser.isValidVideoId(videoId)) return false
        val allowedIds = repository.enabledChannelIds()
        if (allowedIds.isEmpty()) return false
        repository.cachedChannelIdForVideoInMemory(videoId)?.let { cached ->
            return cached in allowedIds
        }
        return runBlocking(Dispatchers.IO) {
            verifyVideoAllowed(videoId) is VideoVerifyOutcome.Allowed
        }
    }

    suspend fun verifyHandleOrLegacyChannelAllowed(handleOrPath: String): HandleVerifyOutcome {
        val allowedIds = repository.enabledChannelIds()
        if (allowedIds.isEmpty()) {
            return HandleVerifyOutcome.Blocked(null, "no_channels_configured")
        }
        val normalized = if (handleOrPath.startsWith("/@") || handleOrPath.startsWith("@")) {
            YoutubeChannelRepository.normalizeHandle(handleOrPath.removePrefix("/"))
        } else null

        if (normalized != null) {
            repository.channelIdForHandle(normalized)?.let { mapped ->
                if (mapped in allowedIds) return HandleVerifyOutcome.Allowed(mapped)
            }
        }

        val url = when {
            normalized != null -> "https://www.youtube.com/$normalized"
            handleOrPath.startsWith("/") -> "https://www.youtube.com$handleOrPath"
            else -> "https://www.youtube.com/$handleOrPath"
        }
        val info = fetchChannelInfoByUrl(url)
            ?: return HandleVerifyOutcome.Blocked(null, "channel_resolve_failed")

        return if (info.channelId in allowedIds) {
            // حدّث الـ handle واسم القناة إذا تغيّر
            if (normalized != null) {
                repository.upsertChannel(
                    channelId = info.channelId,
                    handle = info.handle ?: normalized,
                    title = info.title,
                    avatarUrl = info.avatarUrl,
                    enabled = true
                )
            }
            HandleVerifyOutcome.Allowed(info.channelId)
        } else {
            HandleVerifyOutcome.Blocked(info.channelId, "channel:${info.channelId}")
        }
    }

    /**
     * حل قناة يوتيوب من أي مدخل يضعه الوالد:
     * `@handle`, `UC...`, رابط قناة، أو رابط فيديو من القناة (watch / shorts / youtu.be / live).
     */
    suspend fun resolveChannelFromInput(rawInput: String): ChannelResolveResult = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return@withContext ChannelResolveResult.Failure("empty_input")

        if (YoutubeUrlParser.isValidChannelId(trimmed)) {
            val info = fetchChannelInfoByUrl("https://www.youtube.com/channel/$trimmed")
                ?: YoutubeChannelInfo(trimmed, null, trimmed, null)
            return@withContext ChannelResolveResult.Success(info)
        }
        if (trimmed.startsWith("@") && !trimmed.contains('/') && !trimmed.contains(' ')) {
            val handle = YoutubeChannelRepository.normalizeHandle(trimmed)
                ?: return@withContext ChannelResolveResult.Failure("invalid_handle")
            val info = fetchChannelInfoByUrl("https://www.youtube.com/$handle")
                ?: return@withContext ChannelResolveResult.Failure("lookup_failed")
            return@withContext ChannelResolveResult.Success(info)
        }

        val candidateUrl = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = (UrlNormalizer.normalize(candidateUrl) as? NormalizeResult.Success)?.url
            ?: return@withContext ChannelResolveResult.Failure("invalid_url")

        when (val target = YoutubeUrlParser.classify(parsed)) {
            is YoutubeUrlParser.Target.ChannelById -> {
                val info = fetchChannelInfoByUrl("https://www.youtube.com/channel/${target.channelId}")
                    ?: YoutubeChannelInfo(target.channelId, null, target.channelId, null)
                ChannelResolveResult.Success(info)
            }
            is YoutubeUrlParser.Target.ChannelByHandle -> {
                val info = fetchChannelInfoByUrl("https://www.youtube.com/${target.handle}")
                    ?: return@withContext ChannelResolveResult.Failure("lookup_failed")
                ChannelResolveResult.Success(info)
            }
            is YoutubeUrlParser.Target.LegacyChannelPath -> {
                val info = fetchChannelInfoByUrl("https://www.youtube.com${target.path}")
                    ?: return@withContext ChannelResolveResult.Failure("lookup_failed")
                ChannelResolveResult.Success(info)
            }
            is YoutubeUrlParser.Target.Video -> {
                val channelId = resolveChannelIdForVideo(target.videoId)
                    ?: return@withContext ChannelResolveResult.Failure("video_channel_not_found")
                val info = fetchChannelInfoByUrl("https://www.youtube.com/channel/$channelId")
                    ?: YoutubeChannelInfo(channelId, null, channelId, null)
                ChannelResolveResult.Success(info)
            }
            else -> ChannelResolveResult.Failure("not_a_youtube_channel_or_video")
        }
    }

    suspend fun resolveChannelIdForVideo(videoId: String): String? {
        repository.getCachedChannelIdForVideo(videoId)?.let { return it }

        val existing = inFlightVideos[videoId]
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<String?>()
        val prev = inFlightVideos.putIfAbsent(videoId, deferred)
        if (prev != null) return prev.await()

        return try {
            val resolved = withContext(Dispatchers.IO) { fetchChannelIdForVideoFromNetwork(videoId) }
            if (resolved != null && YoutubeUrlParser.isValidChannelId(resolved)) {
                repository.putVideoChannelCache(videoId, resolved)
            }
            deferred.complete(resolved)
            resolved
        } catch (e: Exception) {
            deferred.complete(null)
            null
        } finally {
            inFlightVideos.remove(videoId, deferred)
        }
    }

    private suspend fun fetchChannelIdForVideoFromNetwork(videoId: String): String? {
        // 1) محاولة oEmbed السريعة إن طابق author_url مع قناة مخزنة بـ @handle أو /channel/UC...
        fetchOEmbedAuthorUrl(videoId)?.let { authorUrl ->
            when (val target = YoutubeUrlParser.classifyRaw(authorUrl)) {
                is YoutubeUrlParser.Target.ChannelById -> return target.channelId
                is YoutubeUrlParser.Target.ChannelByHandle -> {
                    repository.channelIdForHandle(target.handle)?.let { return it }
                }
                else -> Unit
            }
        }

        // 2) الحسم النهائي عبر watch HTML (قراءة متدفقة محدودة السقف) — يعمل حتى عند تعطيل التضمين (oEmbed 401)
        val watchUrl = "https://www.youtube.com/watch?v=${URLEncoder.encode(videoId, "UTF-8")}&hl=en"
        val htmlChunk = downloadPrefix(watchUrl, MAX_WATCH_HTML_BYTES) { chunk ->
            extractChannelIdForVideoHtml(chunk, videoId) != null
        } ?: return null

        return extractChannelIdForVideoHtml(htmlChunk, videoId)
    }

    private fun fetchOEmbedAuthorUrl(videoId: String): String? {
        val target = "https://www.youtube.com/watch?v=$videoId"
        val api = "https://www.youtube.com/oembed?url=${URLEncoder.encode(target, "UTF-8")}&format=json"
        return try {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                extractOEmbedAuthorUrl(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchChannelInfoByUrl(url: String): YoutubeChannelInfo? {
        val html = downloadPrefix(url, MAX_CHANNEL_HTML_BYTES) { chunk ->
            // نتوقف فقط بعد التأكد من توفر معرف القناة والعنوان
            extractChannelIdFromHtml(chunk) != null &&
                (chunk.contains("</head>", ignoreCase = true) || chunk.length > 160_000)
        } ?: return null
        return extractChannelInfoFromHtml(html)
    }

    private fun downloadPrefix(
        url: String,
        maxBytes: Int,
        earlyStop: (String) -> Boolean
    ): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DESKTOP_UA)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
                setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+667; SOCS=CAI")
            }
            try {
                if (conn.responseCode !in 200..399) return null
                val input = BufferedInputStream(conn.inputStream, 8192)
                val out = StringBuilder(minOf(maxBytes, 65536))
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                    if (total >= 16384 && earlyStop(out.toString())) {
                        break
                    }
                }
                out.toString()
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4500
        private const val READ_TIMEOUT_MS = 4500
        private const val MAX_WATCH_HTML_BYTES = 350 * 1024
        private const val MAX_CHANNEL_HTML_BYTES = 350 * 1024
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        private val CHANNEL_PAGE_PATTERNS = listOf(
            Regex("""<meta\s+itemprop="identifier"\s+content="(UC[A-Za-z0-9_-]{22})""""),
            Regex(""""externalId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""""),
            Regex(""""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""""),
            Regex("""youtube\.com/channel/(UC[A-Za-z0-9_-]{22})"""),
            Regex(""""browseId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""")
        )

        private val WATCH_CHANNEL_PATTERNS = listOf(
            Regex("""<meta\s+itemprop="identifier"\s+content="(UC[A-Za-z0-9_-]{22})""""),
            Regex("""<meta\s+itemprop="channelId"\s+content="(UC[A-Za-z0-9_-]{22})""""),
            Regex(""""externalChannelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""")
        )
        private val VIDEO_DETAILS_CHANNEL_REGEX = Regex(
            """"videoDetails"\s*:\s*\{[^{}]*"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"[^{}]*"channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""""
        )
        private val OWNER_CHANNEL_URL_REGEX = Regex(
            """"ownerProfileUrl"\s*:\s*"http://www\.youtube\.com/(@[^"]+)"|"ownerChannelName"[^}]*"navigationEndpoint"[^}]*"browseId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""""
        )

        private val HANDLE_REGEX = Regex("""youtube\.com/(@[A-Za-z0-9._-]{2,40})""")
        private val OG_TITLE_REGEX = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
        private val META_TITLE_REGEX = Regex("""<meta\s+name="title"\s+content="([^"]+)"""")
        private val OG_IMAGE_REGEX = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
        private val JSON_TITLE_REGEX = Regex(""""channelMetadataRenderer"\s*:\s*\{[^}]*"title"\s*:\s*"([^"]+)"""")

        fun extractOEmbedAuthorUrl(jsonText: String): String? = try {
            JSONObject(jsonText).optString("author_url", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        fun extractChannelIdForVideoHtml(html: String, expectedVideoId: String): String? {
            // 1) تطابق صريح داخل videoDetails لنفس videoId
            VIDEO_DETAILS_CHANNEL_REGEX.find(html)?.let { m ->
                val vid = m.groupValues[1]
                val cid = m.groupValues[2]
                if (vid == expectedVideoId && YoutubeUrlParser.isValidChannelId(cid)) return cid
            }
            // 2) علامات head / playerMicroformatRenderer الثابتة للفيديو الرئيسي
            for (pattern in WATCH_CHANNEL_PATTERNS) {
                val m = pattern.find(html)
                if (m != null) {
                    val id = m.groupValues[1]
                    if (YoutubeUrlParser.isValidChannelId(id)) return id
                }
            }
            // 3) احتياط: channelId أو owner navigationEndpoint في الجزء الأول من الاستجابة
            Regex(""""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""").find(html)?.let { m ->
                val id = m.groupValues[1]
                if (YoutubeUrlParser.isValidChannelId(id)) return id
            }
            return null
        }

        fun extractChannelIdFromHtml(html: String): String? {
            for (pattern in CHANNEL_PAGE_PATTERNS) {
                val m = pattern.find(html)
                if (m != null) {
                    val id = m.groupValues[1]
                    if (YoutubeUrlParser.isValidChannelId(id)) return id
                }
            }
            return null
        }

        fun extractChannelInfoFromHtml(html: String): YoutubeChannelInfo? {
            val channelId = extractChannelIdFromHtml(html) ?: return null
            val handleRaw = HANDLE_REGEX.find(html)?.groupValues?.getOrNull(1)
            val handle = YoutubeChannelRepository.normalizeHandle(handleRaw)
            val rawTitle = OG_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: META_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: JSON_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: channelId
            val title = decodeHtmlEntities(rawTitle).trim().ifBlank { channelId }
            val avatar = OG_IMAGE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.let { decodeHtmlEntities(it) }
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            return YoutubeChannelInfo(
                channelId = channelId,
                handle = handle,
                title = title,
                avatarUrl = avatar
            )
        }

        private fun decodeHtmlEntities(text: String): String =
            text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
    }
}
