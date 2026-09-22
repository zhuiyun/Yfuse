package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativePlaybackFailureTest {
    @Test
    fun http_auth_failures_block_engine_and_version_rotation() {
        val unauthorized = assertNotNull(nativePlaybackLogFailure("HTTP error 401 Unauthorized"))
        val forbidden = assertNotNull(nativePlaybackLogFailure("server response status: 403"))

        assertTrue(unauthorized.blocksAutomaticFallback)
        assertTrue(unauthorized.message.contains("重新登录"))
        assertTrue(forbidden.blocksAutomaticFallback)
        assertTrue(forbidden.message.contains("播放权限"))
    }

    @Test
    fun token_and_forbidden_messages_are_treated_as_auth_failures_without_a_status_code() {
        assertTrue(nativePlaybackLogFailure("authentication failed: token expired")?.blocksAutomaticFallback == true)
        assertTrue(nativePlaybackLogFailure("request forbidden by upstream")?.blocksAutomaticFallback == true)
    }

    @Test
    fun fatal_render_failure_changes_engine_but_does_not_block_it() {
        val failure = nativePlaybackLogFailure("Failed initializing any suitable GPU context!")
        val missingSurface =
            nativePlaybackLogFailure("Failed to attach surface")

        assertEquals("播放器渲染器初始化失败，正在尝试其他播放器", failure?.message)
        assertEquals(PlaybackFailureKind.Renderer, missingSurface?.kind)
        assertTrue(isNativeSurfaceLossFailure("Failed to attach surface"))
        assertFalse(failure?.blocksAutomaticFallback ?: true)
    }

    @Test
    fun mediacodec_copy_without_a_decoder_surface_does_not_interrupt_successful_gpu_playback() {
        // Captured on the S10: this decoder warning was followed by successful codec start,
        // hardware copy decoding, and MPV's first video frame. Recovering the VO restarted
        // the decoder and replayed the same harmless warning until fallback was triggered.
        for (codec in listOf("h264", "hevc")) {
            val warning = "$codec" + "_mediacodec: Both surface and native_window are NULL"
            assertNull(nativePlaybackLogFailure(warning))
            assertFalse(isNativeSurfaceLossFailure(warning))
        }
        assertNull(
            nativePlaybackLogFailure("MediaCodec started successfully: codec = OMX.qcom.video.decoder.avc, ret = 0"),
        )
        assertNull(nativePlaybackLogFailure("Using hardware decoding (mediacodec-copy)."))
        assertNull(nativePlaybackLogFailure("first video frame after restart shown"))

        // A subsequent terminal player event must still fail even when its last diagnostic
        // contains that ambiguous decoder message.
        val terminal =
            terminalNativePlaybackFailure(
                fallbackMessage = "Native playback ended with an error",
                details = "h264_mediacodec: Both surface and native_window are NULL",
                kind = PlaybackFailureKind.Renderer,
            )
        assertEquals(PlaybackFailureKind.Renderer, terminal.kind)
        assertEquals("Native playback ended with an error", terminal.message)
    }

    @Test
    fun nativeTlsFailuresAreTransportFailuresInsteadOfUnknownEngineFailures() {
        val failure = nativePlaybackLogFailure("tls: mbedtls_ssl_handshake returned -0x6600")

        assertEquals(PlaybackFailureKind.Network, failure?.kind)
        assertFalse(failure?.blocksAutomaticFallback ?: true)
    }

    @Test
    fun fatal_audio_output_failure_changes_engine_as_an_audio_sink_failure() {
        val failure = nativePlaybackLogFailure("[ao/audiotrack] AudioTrack creation failed")
        val mdkFailure = nativePlaybackLogFailure("-5 audio.render failed to start backend")

        assertEquals(PlaybackFailureKind.AudioSink, failure?.kind)
        assertEquals(PlaybackFailureKind.AudioSink, mdkFailure?.kind)
        assertFalse(failure?.blocksAutomaticFallback ?: true)
        assertTrue(failure?.message.orEmpty().contains("音频输出"))
    }

    @Test
    fun decoder_and_demuxer_errors_stay_on_the_stream_fallback_ladder() {
        assertNull(nativePlaybackLogFailure("hevc decoder rejected profile 8"))
        assertNull(nativePlaybackLogFailure("truehd audio decoder rejected profile"))
        assertNull(nativePlaybackLogFailure("demuxer could not read packet"))
    }

    @Test
    fun a_known_terminal_exception_uses_its_fallback_message() {
        val failure = terminalNativePlaybackFailure("native load failed", "IllegalStateException")

        assertEquals("native load failed", failure.message)
        assertFalse(failure.blocksAutomaticFallback)
    }
}
