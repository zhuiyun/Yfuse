package com.yfuse.core.network

import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbyStreamTest {

    @Test
    fun selected_quality_rewrites_transcode_limits() {
        val original = EmbyStream.transcode("http://emby", "movie", "token")

        val fullHd = EmbyStream.withQuality(original, PlaybackQuality.FullHd)

        assertTrue("MaxWidth=1920" in fullHd)
        assertTrue("VideoBitrate=8000000" in fullHd)
        assertTrue("api_key=token" in fullHd)
        assertTrue("MediaSourceId=movie" in fullHd)
        assertTrue("TranscodingProtocol=hls" in fullHd)
    }

    @Test
    fun automatic_quality_keeps_original_transcode_url() {
        val original = EmbyStream.transcode("http://emby", "movie", "token")

        assertEquals(original, EmbyStream.withQuality(original, PlaybackQuality.Auto))
    }

    @Test
    fun progressive_fallback_requests_mp4_transcoding() {
        val url = EmbyStream.progressiveTranscode("http://emby", "movie", "token")

        assertTrue("/Videos/movie/stream.mp4" in url)
        assertTrue("static=false" in url)
        assertTrue("MediaSourceId=movie" in url)
        assertTrue("Container=mp4" in url)
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
}
