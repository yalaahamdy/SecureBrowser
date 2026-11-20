package com.securebrowser.app.security.youtube

import com.securebrowser.app.policy.ExternalAppPolicy
import com.securebrowser.app.policy.InMemoryTempAccessStore
import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.policy.TemporaryAccessManager
import com.securebrowser.app.security.SecurityEngine
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.whitelist.WhiteListEngine
import com.securebrowser.app.security.whitelist.WhiteListRule
import com.securebrowser.app.security.whitelist.WhiteListRuleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة شاملة لنظام قنوات يوتيوب المسموحة (v1.15.0):
 * - تحليل كل صيغ الروابط والنطاقات وإعادة التوجيه.
 * - قواعد السماح والحظر والـ PendingVerification.
 * - أسبقية سياسة القنوات فوق القائمة البيضاء العامة حتى لو أُضيف youtube.com.
 * - منع الهروب لتطبيق يوتيوب الرسمي (vnd.youtube / intent:// / suggestForWebUrl).
 * - دقة استخراج channelId من HTML و oEmbed.
 */
class YoutubeChannelPolicyTest {

    private val allowedChannelId = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val otherChannelId = "UCBR8-60-B28hp2BmDPdntcQ"
    private val allowedVideoId = "dQw4w9WgXcQ"
    private val blockedVideoId = "9bZkp7q19f0"
    private val unknownVideoId = "jNQXAC9IVRw"

    private fun buildSecurityEngine(
        youtubeMode: Boolean = true,
        blockShorts: Boolean = true,
        whitelistYoutubeDomain: Boolean = false,
        tempAccessActive: Boolean = false,
        configuredChannels: Set<String> = setOf(allowedChannelId)
    ): SecurityEngine {
        val rules = buildList {
            add(
                WhiteListRule(
                    id = 1,
                    host = "example.com",
                    path = null,
                    scheme = null,
                    port = null,
                    type = RuleType.ALLOW_DOMAIN,
                    subdomainPolicy = SubdomainPolicy.INCLUDE_SUBDOMAINS,
                    enabled = true,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
            if (whitelistYoutubeDomain) {
                add(
                    WhiteListRule(
                        id = 2,
                        host = "youtube.com",
                        path = null,
                        scheme = null,
                        port = null,
                        type = RuleType.ALLOW_DOMAIN,
                        subdomainPolicy = SubdomainPolicy.INCLUDE_SUBDOMAINS,
                        enabled = true,
                        createdAt = 1L,
                        updatedAt = 1L
                    )
                )
            }
        }
        val wl = WhiteListEngine(WhiteListRuleProvider { rules })
        kotlinx.coroutines.runBlocking { wl.refresh() }
        val temp = TemporaryAccessManager(InMemoryTempAccessStore())
        if (tempAccessActive) temp.start(10 * 60_000L)
        val policyManager = PolicyManager(temp)

        val videoCache = mapOf(
            allowedVideoId to allowedChannelId,
            blockedVideoId to otherChannelId
        )
        val handleMap = mapOf("@googledevelopers" to allowedChannelId)

        val ytPolicy = YoutubeChannelPolicy(
            modeEnabled = { youtubeMode },
            blockShorts = { blockShorts },
            enabledChannelIds = { configuredChannels },
            channelIdForHandle = { handleMap[it] },
            cachedChannelForVideo = { videoCache[it] }
        )

        return SecurityEngine(
            whiteListEngine = wl,
            policyManager = policyManager,
            youtubeChannelPolicy = ytPolicy
        )
    }

    @Test
    fun `parser classifies all youtube video and channel url formats`() {
        assertEquals(
            YoutubeUrlParser.Target.Video(allowedVideoId),
            YoutubeUrlParser.classifyRaw("https://www.youtube.com/watch?v=$allowedVideoId")
        )
        assertEquals(
            YoutubeUrlParser.Target.Video(allowedVideoId),
            YoutubeUrlParser.classifyRaw("https://youtu.be/$allowedVideoId?si=abc")
        )
        assertEquals(
            YoutubeUrlParser.Target.Video(allowedVideoId, isShort = true),
            YoutubeUrlParser.classifyRaw("https://m.youtube.com/shorts/$allowedVideoId")
        )
        assertEquals(
            YoutubeUrlParser.Target.Video(allowedVideoId, isEmbed = true),
            YoutubeUrlParser.classifyRaw("https://www.youtube-nocookie.com/embed/$allowedVideoId?autoplay=1")
        )
        assertEquals(
            YoutubeUrlParser.Target.Video(allowedVideoId, isLive = true),
            YoutubeUrlParser.classifyRaw("https://www.youtube.com/live/$allowedVideoId")
        )
        assertEquals(
            YoutubeUrlParser.Target.ChannelById(allowedChannelId),
            YoutubeUrlParser.classifyRaw("https://m.youtube.com/channel/$allowedChannelId/videos")
        )
        assertEquals(
            YoutubeUrlParser.Target.ChannelByHandle("@googledevelopers"),
            YoutubeUrlParser.classifyRaw("https://www.youtube.com/@GoogleDevelopers/playlists")
        )
        assertTrue(YoutubeUrlParser.classifyRaw("https://m.youtube.com/") is YoutubeUrlParser.Target.ForbiddenSurface)
        assertTrue(YoutubeUrlParser.classifyRaw("https://www.youtube.com/results?search_query=cat") is YoutubeUrlParser.Target.ForbiddenSurface)
        assertTrue(YoutubeUrlParser.classifyRaw("https://m.youtube.com/shorts") is YoutubeUrlParser.Target.ForbiddenSurface)
    }

    @Test
    fun `channel mode blocks home search trending and other channels even if youtube is whitelisted`() {
        val engine = buildSecurityEngine(youtubeMode = true, whitelistYoutubeDomain = true)

        val home = engine.validate("https://m.youtube.com/", NavigationType.TYPED)
        assertTrue(home is NavigationDecision.Block)
        assertEquals(BlockReason.YOUTUBE_CHANNEL_NOT_ALLOWED, (home as NavigationDecision.Block).reason)

        val search = engine.validate("https://www.youtube.com/results?search_query=games", NavigationType.LINK)
        assertTrue(search is NavigationDecision.Block)

        val otherCh = engine.validate("https://www.youtube.com/channel/$otherChannelId", NavigationType.LINK)
        assertTrue(otherCh is NavigationDecision.Block)

        val blockedVid = engine.validate("https://www.youtube.com/watch?v=$blockedVideoId", NavigationType.LINK)
        assertTrue(blockedVid is NavigationDecision.Block)
    }

    @Test
    fun `channel mode allows whitelisted channel and verified videos even without youtube in general whitelist`() {
        val engine = buildSecurityEngine(youtubeMode = true, whitelistYoutubeDomain = false)

        assertTrue(engine.validate("https://m.youtube.com/channel/$allowedChannelId", NavigationType.INITIAL) is NavigationDecision.Allow)
        assertTrue(engine.validate("https://www.youtube.com/@GoogleDevelopers", NavigationType.LINK) is NavigationDecision.Allow)
        assertTrue(engine.validate("https://www.youtube.com/watch?v=$allowedVideoId", NavigationType.LINK) is NavigationDecision.Allow)
        assertTrue(engine.validate("https://youtu.be/$allowedVideoId", NavigationType.LINK) is NavigationDecision.Allow)

        val uncached = engine.validate("https://www.youtube.com/watch?v=$unknownVideoId", NavigationType.LINK)
        assertTrue(uncached is NavigationDecision.PendingVerification)
        assertEquals(unknownVideoId, (uncached as NavigationDecision.PendingVerification).videoId)
    }

    @Test
    fun `shorts setting controls shorts urls strictly`() {
        val strictShorts = buildSecurityEngine(blockShorts = true)
        assertTrue(strictShorts.validate("https://youtube.com/shorts/$allowedVideoId", NavigationType.LINK) is NavigationDecision.Block)
        assertTrue(strictShorts.validate("https://m.youtube.com/channel/$allowedChannelId/shorts", NavigationType.LINK) is NavigationDecision.Block)

        val allowShorts = buildSecurityEngine(blockShorts = false)
        assertTrue(allowShorts.validate("https://youtube.com/shorts/$allowedVideoId", NavigationType.LINK) is NavigationDecision.Allow)
    }

    @Test
    fun `redirect wrapper cannot bypass general whitelist or channel policy`() {
        val engine = buildSecurityEngine(youtubeMode = true)

        val externalBlocked = engine.validate(
            "https://www.youtube.com/redirect?q=https%3A%2F%2Fevil.com%2Fhack",
            NavigationType.LINK
        )
        assertTrue(externalBlocked is NavigationDecision.Block)

        val externalAllowed = engine.validate(
            "https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.com%2Fdocs",
            NavigationType.LINK
        )
        assertTrue(externalAllowed is NavigationDecision.Allow)

        val ytAllowed = engine.validate(
            "https://www.youtube.com/attribution_link?u=%2Fwatch%3Fv%3D$allowedVideoId",
            NavigationType.LINK
        )
        assertTrue(ytAllowed is NavigationDecision.Allow)
    }

    @Test
    fun `channel mode blocks native youtube app handoff schemes and intents`() {
        val engine = buildSecurityEngine(youtubeMode = true)

        val vnd = engine.validate("vnd.youtube://$blockedVideoId", NavigationType.LINK)
        assertTrue(vnd is NavigationDecision.Block)

        val intentYt = engine.validate(
            "intent://www.youtube.com/watch?v=$blockedVideoId#Intent;scheme=https;package=com.google.android.youtube;end",
            NavigationType.LINK
        )
        assertTrue(intentYt is NavigationDecision.Block)

        assertNull(ExternalAppPolicy.suggestForWebUrl("https://youtube.com/watch?v=$blockedVideoId", suppressYoutubeSuggestion = true))
        assertNotNull(ExternalAppPolicy.suggestForWebUrl("https://youtube.com/watch?v=$blockedVideoId", suppressYoutubeSuggestion = false))

        assertEquals(
            "https://www.youtube.com/watch?v=$allowedVideoId",
            ExternalAppPolicy.webFallbackForAppUri("vnd.youtube", "//$allowedVideoId")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=$allowedVideoId",
            ExternalAppPolicy.webFallbackForAppUri(
                "intent",
                "intent://www.youtube.com/watch?v=$allowedVideoId#Intent;scheme=https;package=com.google.android.youtube;end"
            )
        )
    }

    @Test
    fun `html and oembed extractors resolve exact channelId safely`() {
        val watchHtml = """
            <html><head>
            <meta itemprop="identifier" content="$allowedChannelId">
            </head><body>
            <script>var ytInitialPlayerResponse = {"videoDetails":{"videoId":"$allowedVideoId","channelId":"$allowedChannelId"}};</script>
            </body></html>
        """.trimIndent()
        assertEquals(allowedChannelId, YoutubeChannelVerifier.extractChannelIdForVideoHtml(watchHtml, allowedVideoId))

        val channelHtml = """
            <html><head>
            <meta property="og:title" content="Google for Developers">
            <meta property="og:image" content="https://yt3.googleusercontent.com/avatar.jpg">
            <link rel="canonical" href="https://www.youtube.com/@GoogleDevelopers">
            <meta itemprop="identifier" content="$allowedChannelId">
            </head></html>
        """.trimIndent()
        val info = YoutubeChannelVerifier.extractChannelInfoFromHtml(channelHtml)
        assertNotNull(info)
        assertEquals(allowedChannelId, info!!.channelId)
        assertEquals("@googledevelopers", info.handle)
        assertEquals("Google for Developers", info.title)
        assertEquals("https://yt3.googleusercontent.com/avatar.jpg", info.avatarUrl)

        val oembed = """{"author_url":"https://www.youtube.com/@GoogleDevelopers","title":"Demo"}"""
        assertEquals("https://www.youtube.com/@GoogleDevelopers", YoutubeChannelVerifier.extractOEmbedAuthorUrl(oembed))
    }
}
