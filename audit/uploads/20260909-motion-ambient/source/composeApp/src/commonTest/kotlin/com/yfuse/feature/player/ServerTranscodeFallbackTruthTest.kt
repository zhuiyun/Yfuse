package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTranscodeFallbackTruthTest {
    @Test
    fun generated_urls_are_not_automatic_fallback_when_server_rejected_transcoding() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "generated-hls",
                fallbackTranscodeUrl = "generated-mp4",
                title = "电影",
                playMethod = PlaybackMethod.DirectPlay,
                serverTranscodeSupported = false,
            )

        assertFalse(item.allowsServerTranscodeFallback("解码失败"))
    }

    @Test
    fun server_approved_transcode_with_a_concrete_url_remains_available() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "server-hls",
                title = "电影",
                serverTranscodeSupported = true,
            )

        assertTrue(item.allowsServerTranscodeFallback("解码失败"))
    }

    @Test
    fun an_explicit_transcode_play_method_is_server_evidence() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "server-hls",
                title = "电影",
                playMethod = PlaybackMethod.Transcode,
            )

        assertTrue(item.allowsServerTranscodeFallback("解码失败"))
    }

    @Test
    fun approval_without_a_url_cannot_start_a_fallback() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                title = "电影",
                serverTranscodeSupported = true,
            )

        assertFalse(item.allowsServerTranscodeFallback("解码失败"))
    }
}
