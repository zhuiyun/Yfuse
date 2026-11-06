package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core.playback.classifyPlaybackFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

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
}
