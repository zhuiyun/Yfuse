package com.yfuse.feature.player

import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCastPlaybackTest {
    private val receiver = CastDevice("chromecast:lounge", "客厅电视")

    @Test
    fun confirmed_receiver_truth_replaces_local_transport_truth() {
        val local =
            PlaybackState(
                playing = false,
                buffering = false,
                positionMs = 2_000L,
                durationMs = 100_000L,
                bufferedPositionMs = 3_000L,
            )
        val cast =
            CastState(
                status = CastPlaybackStatus.Playing,
                activeDevice = receiver,
                sessionConfirmed = true,
                positionMs = 50_000L,
                positionConfirmed = true,
                durationMs = 120_000L,
                lastRemoteWasPlaying = true,
            )

        val remote = local.withRemoteCast(cast, playMethod = "服务器转码")

        assertTrue(remote.playing)
        assertFalse(remote.buffering)
        assertEquals(50_000L, remote.positionMs)
        assertEquals(120_000L, remote.durationMs)
        assertEquals(50_000L, remote.bufferedPositionMs)
        assertEquals("远程投屏 · 客厅电视", remote.diagnostics.engine)
        assertEquals("服务器转码", remote.diagnostics.playMethod)
    }

    @Test
    fun non_fatal_cast_command_error_never_becomes_player_fatal_error() {
        val local = PlaybackState(error = "旧的本地解码错误")
        val cast =
            CastState(
                status = CastPlaybackStatus.Error,
                activeDevice = receiver,
                sessionConfirmed = true,
                positionMs = 8_000L,
                positionConfirmed = true,
                lastRemoteWasPlaying = true,
                error = "接收端拒绝调节音量",
            )

        val remote = local.withRemoteCast(cast, playMethod = "直播放")

        assertNull(remote.error)
        assertTrue(remote.playing)
        assertEquals(8_000L, remote.positionMs)
    }

    @Test
    fun unconfirmed_receiver_position_does_not_replace_local_position() {
        val local = PlaybackState(positionMs = 12_000L, bufferedPositionMs = 13_000L)
        val cast =
            CastState(
                status = CastPlaybackStatus.Connecting,
                activeDevice = receiver,
                positionMs = 50_000L,
                positionConfirmed = false,
            )

        val projected = local.withRemoteCast(cast, playMethod = "直播放")

        assertEquals(12_000L, projected.positionMs)
        assertEquals(13_000L, projected.bufferedPositionMs)
        assertTrue(projected.buffering)
    }
}
