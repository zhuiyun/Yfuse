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
}
