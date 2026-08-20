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
            nativePlaybackLogFailure("hevc_mediacodec: Both surface and native_window are NULL")

        assertEquals("播放器渲染器初始化失败，正在尝试其他播放器", failure?.message)
        assertEquals(PlaybackFailureKind.Renderer, missingSurface?.kind)
        assertTrue(isNativeSurfaceLossFailure("Both surface and native_window are NULL"))
        assertFalse(failure?.blocksAutomaticFallback ?: true)
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
