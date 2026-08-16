package com.yfuse.feature.player

import androidx.media3.common.PlaybackException
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.classifyPlaybackFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A failure's category decides whether a backend gets blamed for it.
 *
 * `allowsBackendFallback` excludes only Network, Authorization and Drm, so anything that lands
 * on Unknown both switches backend and writes an engine-scoped record into a seven-day store.
 * The category therefore has to come from the backend, which knew it, rather than be recovered
 * from the sentence shown to the viewer.
 */
class PlaybackFailureKindReportingTest {
    /**
     * The defect. Every message the engines actually emit is Chinese; the classifier's keyword
     * lists are lowercase English. Five of the seven came back Unknown — including a network
     * failure, which is precisely the kind the architecture document promises will never
     * blacklist a decoder.
     */
    @Test
    fun the_message_classifier_cannot_read_the_messages_the_engines_emit() {
        val emitted =
            listOf(
                "当前视频无法解码，且服务器未提供可用转码流",
                "当前视频无法解码，正在尝试其他播放器",
                "当前音轨不受 ExoPlayer 支持，正在尝试其他播放器",
                "服务器返回了无效的转码清单",
                "网络连接多次失败，已尝试所有播放方式",
            )

        emitted.forEach { message ->
            assertEquals(
                PlaybackFailureKind.Unknown,
                classifyPlaybackFailure(message),
                "This is why the category must be reported, not parsed: $message",
            )
        }
    }

    @Test
    fun a_network_failure_read_as_unknown_would_blame_the_backend() {
        assertFalse(
            PlaybackFailureKind.Unknown.allowsBackendFallback.not(),
            "Unknown permits backend fallback and an engine-scoped penalty…",
        )
        assertFalse(
            PlaybackFailureKind.Network.allowsBackendFallback,
            "…while Network is meant to be exempt, which is the whole point of reporting it",
        )
        assertNotEquals(
            PlaybackFailureKind.Network.allowsBackendFallback,
            PlaybackFailureKind.Unknown.allowsBackendFallback,
        )
    }

    @Test
    fun media3_error_codes_map_to_the_category_media3_already_decided() {
        val cases =
            mapOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to PlaybackFailureKind.Network,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to PlaybackFailureKind.Network,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS to PlaybackFailureKind.Authorization,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED to PlaybackFailureKind.Drm,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to PlaybackFailureKind.Container,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to PlaybackFailureKind.Decoder,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to PlaybackFailureKind.Decoder,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to PlaybackFailureKind.AudioSink,
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED to PlaybackFailureKind.Renderer,
            )

        cases.forEach { (code, expected) ->
            val exception =
                PlaybackException(
                    /* message = */ "test",
                    /* cause = */ null,
                    /* errorCode = */ code,
                )
            assertEquals(expected, exception.playbackFailureKind(), "errorCode $code")
        }
    }

    @Test
    fun an_unrecognised_code_stays_unknown_rather_than_guessing() {
        val exception =
            PlaybackException(
                "test",
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )

        assertEquals(PlaybackFailureKind.Unknown, exception.playbackFailureKind())
    }

    @Test
    fun every_exo_failure_site_reports_a_category() {
        val exo = source("feature/player/ExoVideoEngine.kt")
        val calls = Regex("""failPlayback\(""").findAll(exo).count()
        val withKind = Regex("""kind = (PlaybackFailureKind\.|error\.playbackFailureKind)""").findAll(exo).count()

        assertTrue(calls > 1, "expected failPlayback call sites")
        assertEquals(
            calls - 1,
            withKind,
            "every call site but the declaration itself must name a category",
        )
    }

    @Test
    fun the_native_backends_report_the_category_they_already_decided() {
        val native = source("../commonMain/kotlin/com/yfuse/feature/player/NativePlaybackFailure.kt")

        assertTrue("val kind: PlaybackFailureKind" in native)
        assertTrue("kind = PlaybackFailureKind.Authorization" in native)
        assertTrue("kind = PlaybackFailureKind.Renderer" in native)
    }

    @Test
    fun the_consumer_prefers_the_reported_category() {
        val root = source("feature/player/PlayerRoot.kt")
        val reported = root.indexOf("state.errorKind")
        val parsed = root.indexOf("classifyPlaybackFailure(")

        assertTrue(reported in 1..<parsed, "the reported category has to be consulted first")
    }

    private fun source(path: String): String =
        sequenceOf(
            File("src/androidMain/kotlin/com/yfuse/$path"),
            File("composeApp/src/androidMain/kotlin/com/yfuse/$path"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate $path from ${File(".").absolutePath}")
}
