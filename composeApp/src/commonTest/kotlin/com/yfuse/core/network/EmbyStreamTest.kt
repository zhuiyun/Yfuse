package com.yfuse.core.network

import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbyStreamTest {
    @Test
    fun sameOriginNegotiatedHlsIsForcedToH264AacCompatibility() {
        val compatible =
            EmbyStream.compatibleHlsTranscodeUrl(
                baseUrl = "https://emby.example/base",
                url =
                    "https://emby.example/Videos/movie/master.m3u8" +
                        "?AudioCodec=eac3&Container=fmp4&MaxAudioChannels=8",
            )

        assertTrue("AudioCodec=aac" in compatible, compatible)
        assertTrue("VideoCodec=h264" in compatible, compatible)
        assertTrue("Container=ts" in compatible, compatible)
        assertTrue("SegmentContainer=ts" in compatible, compatible)
        assertTrue("MaxAudioChannels=2" in compatible, compatible)
        assertTrue("TranscodingMaxAudioChannels=2" in compatible, compatible)
    }

    @Test
    fun signedCrossOriginTranscodeUrlIsNotModified() {
        val signed = "https://cdn.example/master.m3u8?signature=a%2Bb&AudioCodec=eac3"

        assertEquals(
            signed,
            EmbyStream.compatibleHlsTranscodeUrl("https://emby.example", signed),
        )
    }

    @Test
    fun negotiatedPublicHttpCdnUrlIsUsableWithoutLeakingServerCredentials() {
        val raw = "http://media.example/video?signature=abc%2B123"
        val negotiated =
            EmbyStream.negotiatedUrl(
                baseUrl = "https://emby.example",
                rawUrl = raw,
                token = "secret-token",
                playSessionId = "session",
            )

        assertEquals(raw, negotiated)
        assertTrue("secret-token" !in negotiated.orEmpty(), negotiated.orEmpty())
        assertTrue("PlaySessionId" !in negotiated.orEmpty(), negotiated.orEmpty())
        assertTrue("DeviceId" !in negotiated.orEmpty(), negotiated.orEmpty())
    }

    @Test
    fun negotiatedLocalHttpCdnUrlIsAlsoKeptCredentialFree() {
        val raw = "http://192.168.1.20/video"
        val negotiated =
            EmbyStream.negotiatedUrl(
                baseUrl = "https://emby.example",
                rawUrl = raw,
                token = "secret-token",
                playSessionId = "session",
            )

        assertEquals(raw, negotiated)
        assertTrue("secret-token" !in negotiated.orEmpty(), negotiated.orEmpty())
        assertTrue("PlaySessionId" !in negotiated.orEmpty(), negotiated.orEmpty())
    }

    @Test
    fun negotiated_https_and_relative_urls_remain_usable() {
        val https =
            EmbyStream.negotiatedUrl(
                baseUrl = "https://emby.example",
                rawUrl = "https://cdn.example/video",
                token = "token",
                playSessionId = "session",
            )
        val relative =
            EmbyStream.negotiatedUrl(
                baseUrl = "http://emby.local",
                rawUrl = "/Videos/item/stream",
                token = "token",
                playSessionId = "session",
            )

        assertTrue(https?.startsWith("https://cdn.example/video") == true, https.orEmpty())
        assertTrue(relative?.startsWith("http://emby.local/Videos/item/stream") == true, relative.orEmpty())
        assertEquals("https://cdn.example/video", https)
        assertTrue("api_key=token" in relative.orEmpty(), relative.orEmpty())
        assertTrue("PlaySessionId=session" in relative.orEmpty(), relative.orEmpty())
    }

    @Test
    fun same_origin_absolute_url_keeps_authentication_and_normalizes_default_port() {
        val negotiated =
            EmbyStream.negotiatedUrl(
                baseUrl = "https://emby.example:443/base",
                rawUrl = "https://EMBY.EXAMPLE/Videos/item/stream",
                token = "secret-token",
                playSessionId = "session",
            )

        assertTrue(negotiated?.startsWith("https://EMBY.EXAMPLE/Videos/item/stream") == true, negotiated.orEmpty())
        assertTrue("api_key=secret-token" in negotiated.orEmpty(), negotiated.orEmpty())
        assertTrue("PlaySessionId=session" in negotiated.orEmpty(), negotiated.orEmpty())
        assertTrue("DeviceId=" in negotiated.orEmpty(), negotiated.orEmpty())
    }

    @Test
    fun selected_quality_rewrites_transcode_limits() {
        val original = EmbyStream.transcode("http://emby", "movie", "token")

        val fullHd = EmbyStream.withQuality(original, PlaybackQuality.FullHd)

        assertTrue("MaxWidth=1920" in fullHd)
        assertTrue("VideoBitrate=8000000" in fullHd)
        assertTrue("api_key=token" in fullHd)
        assertTrue("MediaSourceId=movie" in fullHd)
        assertTrue("TranscodingProtocol=hls" in fullHd)
        assertTrue("Container=ts" in fullHd)
        assertTrue("MaxAudioChannels=2" in fullHd)
        assertTrue("TranscodingMaxAudioChannels=2" in fullHd)
    }

    @Test
    fun automatic_quality_keeps_original_transcode_url() {
        val original = EmbyStream.transcode("http://emby", "movie", "token")

        assertEquals(original, EmbyStream.withQuality(original, PlaybackQuality.Auto))
        assertEquals(original, EmbyStream.withQuality(original, PlaybackQuality.Original))
    }

    @Test
    fun selected_quality_adds_limits_to_a_server_negotiated_url() {
        val negotiated =
            "https://emby/Videos/movie/master.m3u8?api_key=token&PlaySessionId=session"

        val capped = EmbyStream.withQuality(negotiated, PlaybackQuality.Hd)

        assertTrue("MaxWidth=1280" in capped, capped)
        assertTrue("VideoBitrate=4000000" in capped, capped)
        assertTrue("api_key=token" in capped, capped)
        assertTrue("PlaySessionId=session" in capped, capped)
    }

    @Test
    fun selected_quality_does_not_materialize_a_missing_transcode_url() {
        assertEquals("", EmbyStream.withQuality("", PlaybackQuality.FullHd))
    }

    @Test
    fun progressive_fallback_requests_mp4_transcoding() {
        val url = EmbyStream.progressiveTranscode("http://emby", "movie", "token")

        assertTrue("/Videos/movie/stream.mp4" in url)
        assertTrue("static=false" in url)
        assertTrue("MediaSourceId=movie" in url)
        assertTrue("Container=mp4" in url)
        assertTrue("MaxAudioChannels=2" in url)
    }

    @Test
    fun credentials_and_media_source_are_query_encoded() {
        val url =
            EmbyStream.transcode(
                baseUrl = "http://emby",
                itemId = "movie",
                token = "token+/= value",
                mediaSourceId = "source one+two",
            )

        assertTrue("api_key=token%2B%2F%3D%20value" in url, url)
        assertTrue("MediaSourceId=source%20one%2Btwo" in url, url)
    }

    @Test
    fun a_transcode_aims_at_the_source_rather_than_a_fixed_1080p() {
        // A 4K remux falls back to 4K, not to a quarter of the pixels it started with.
        assertEquals(3840 to 24_000_000, EmbyStream.transcodeTarget(3840, 80_000_000))
        assertEquals(2560 to 16_000_000, EmbyStream.transcodeTarget(2560, 40_000_000))
        assertEquals(1920 to 8_000_000, EmbyStream.transcodeTarget(1920, 20_000_000))
    }

    @Test
    fun a_transcode_never_upscales_and_never_drops_below_the_old_default() {
        // A 720p source stays a 720p-worth of bits, but the ceiling stays where servers
        // are known to cope.
        assertEquals(1920, EmbyStream.transcodeTarget(1280, null).first)
        // Beyond 4K is nobody's real-time transcode.
        assertEquals(3840, EmbyStream.transcodeTarget(7680, null).first)
        // Nothing known about the source is the case the old fixed default was written for.
        assertEquals(1920 to 8_000_000, EmbyStream.transcodeTarget(null, null))
    }

    @Test
    fun a_source_thinner_than_the_ladder_is_not_padded_out() {
        // Re-encoding a 3 Mbps file at 24 Mbps buys nothing and costs the server.
        assertEquals(3840 to 3_000_000, EmbyStream.transcodeTarget(3840, 3_000_000))
    }

    /**
     * Emby matches a running encoding on (DeviceId, PlaySessionId). All three addresses for
     * one file have to name the same session or falling back from one to another reads as a
     * second playback — and `Playing/Stopped` can then end neither.
     */
    @Test
    fun every_address_for_a_file_names_the_same_play_session() {
        val urls = EmbyStream.streamUrls("http://emby", "movie", "token")

        assertTrue(urls.playSessionId.isNotBlank())
        listOf(urls.direct, urls.transcode, urls.progressiveTranscode).forEach { url ->
            assertTrue("PlaySessionId=${urls.playSessionId}" in url, url)
            assertTrue("DeviceId=" in url, url)
        }
    }

    @Test
    fun each_file_gets_its_own_play_session() {
        val first = EmbyStream.streamUrls("http://emby", "movie", "token")
        val second = EmbyStream.streamUrls("http://emby", "movie", "token")

        assertTrue(first.playSessionId != second.playSessionId)
        assertTrue(first.playSessionId.all(Char::isLetterOrDigit))
    }

    /**
     * The device id was the literal `yfuse` on every install, so a server could not tell two
     * of the user's own devices apart and reaped the wrong sessions.
     */
    @Test
    fun the_device_id_is_not_a_shared_constant() {
        assertTrue("DeviceId=yfuse&" !in EmbyStream.streamUrls("http://emby", "m", "t").transcode)
        assertTrue(!EmbyStream.streamUrls("http://emby", "m", "t").transcode.endsWith("DeviceId=yfuse"))
    }

    @Test
    fun a_transcode_ladder_follows_the_source_through_stream_urls() {
        val urls =
            EmbyStream.streamUrls(
                baseUrl = "http://emby",
                itemId = "movie",
                token = "token",
                sourceWidth = 3840,
                sourceBitrateBps = 80_000_000,
            )

        // The episode-polling path used to build these with the bare 1080p/6 Mbps defaults.
        assertTrue("MaxWidth=3840" in urls.transcode, urls.transcode)
        assertTrue("VideoBitrate=24000000" in urls.transcode, urls.transcode)
        assertTrue("MaxWidth=3840" in urls.progressiveTranscode, urls.progressiveTranscode)
    }

    @Test
    fun quality_selection_still_rewrites_a_session_bearing_url() {
        val urls = EmbyStream.streamUrls("http://emby", "movie", "token", sourceWidth = 3840)

        val capped = EmbyStream.withQuality(urls.transcode, PlaybackQuality.FullHd)

        assertTrue("MaxWidth=1920" in capped, capped)
        assertTrue("PlaySessionId=${urls.playSessionId}" in capped, capped)
    }
}
