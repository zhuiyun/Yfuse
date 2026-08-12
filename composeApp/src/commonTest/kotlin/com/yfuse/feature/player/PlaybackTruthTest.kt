package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTruthTest {

    @Test
    fun manual_cap_starts_on_transcode_and_records_the_user_reason() {
        val item = PlayerMediaItem(
            id = "movie",
            url = "direct",
            transcodeUrl = "hls",
            title = "电影",
        )

        assertTrue(item.startsWithServerTranscode(PlaybackQuality.FullHd))
        assertEquals(
            PlaybackMethod.Transcode,
            item.effectivePlaybackMethod(PlaybackQuality.FullHd),
        )
        assertEquals("用户选择 1080P · 8 Mbps", item.initialFallbackReason(PlaybackQuality.FullHd))
    }

    @Test
    fun auto_preserves_the_server_negotiated_direct_stream_truth() {
        val item = PlayerMediaItem(
            id = "movie",
            url = "direct-stream",
            transcodeUrl = "hls",
            title = "电影",
            playMethod = PlaybackMethod.DirectStream,
        )

        assertFalse(item.startsWithServerTranscode(PlaybackQuality.Auto))
        assertEquals(PlaybackMethod.DirectStream, item.effectivePlaybackMethod(PlaybackQuality.Auto))
        assertEquals("服务器协商为直串流", item.initialFallbackReason(PlaybackQuality.Auto))
    }

    @Test
    fun missing_transcode_url_does_not_replace_a_working_direct_source() {
        val item = PlayerMediaItem("movie", "direct", "", "电影")

        assertFalse(item.startsWithServerTranscode(PlaybackQuality.Hd))
        assertEquals(PlaybackMethod.DirectPlay, item.effectivePlaybackMethod(PlaybackQuality.Hd))
        assertEquals(
            "服务器未提供转码地址，已保留原始播放方式",
            item.initialFallbackReason(PlaybackQuality.Hd),
        )
        assertNull(item.initialFallbackReason(PlaybackQuality.Original))
    }
}
