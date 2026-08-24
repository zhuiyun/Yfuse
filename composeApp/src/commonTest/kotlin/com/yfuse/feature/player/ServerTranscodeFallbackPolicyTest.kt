package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTranscodeFallbackPolicyTest {
    @Test
    fun generated_urls_without_server_approval_are_never_used_as_fallbacks() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "generated-best-effort-hls",
                fallbackTranscodeUrl = "generated-best-effort-mp4",
                title = "电影",
                playMethod = PlaybackMethod.DirectPlay,
                serverTranscodeSupported = false,
            )

        assertFalse(item.allowsServerTranscodeFallback("解码失败"))
        assertFalse(item.allowsServerTranscodeFallback("用户手动选择服务器转码"))
    }

    @Test
    fun an_explicitly_approved_server_stream_remains_available_for_recovery() {
        val item =
            PlayerMediaItem(
                id = "movie",
                url = "direct",
                transcodeUrl = "negotiated-hls",
                fallbackTranscodeUrl = "negotiated-mp4",
                title = "电影",
                playMethod = PlaybackMethod.DirectPlay,
                serverTranscodeSupported = true,
            )

        assertTrue(item.allowsServerTranscodeFallback("解码失败"))
    }

    @Test
    fun approval_without_a_concrete_stream_cannot_start_a_fallback() {
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
