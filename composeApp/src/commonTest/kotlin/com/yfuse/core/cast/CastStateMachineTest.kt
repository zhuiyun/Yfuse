package com.yfuse.core.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastStateMachineTest {
    private val receiver = CastDevice("chromecast:lounge", "客厅电视")

    @Test
    fun receiver_state_transitions_only_become_active_after_confirmation() {
        val connecting = CastState().connectingTo(receiver, positionMs = 42_000L)

        assertEquals(CastPlaybackStatus.Connecting, connecting.status)
        assertEquals(1L, connecting.sessionRevision)
        assertEquals(42_000L, connecting.positionMs)
        assertFalse(connecting.positionConfirmed)
        assertFalse(connecting.hasActiveSession)

        val buffering =
            connecting.remoteUpdate(
                status = CastPlaybackStatus.Buffering,
                positionMs = 42_500L,
                durationMs = 120_000L,
            )
        assertTrue(buffering.sessionConfirmed)
        assertTrue(buffering.positionConfirmed)
        assertTrue(buffering.hasActiveSession)

        val playing = buffering.remoteUpdate(CastPlaybackStatus.Playing, positionMs = 43_000L)
        assertTrue(playing.lastRemoteWasPlaying)

        val paused = playing.remoteUpdate(CastPlaybackStatus.Paused, positionMs = 44_000L)
        assertFalse(paused.lastRemoteWasPlaying)

        val ended = paused.remoteUpdate(CastPlaybackStatus.Ended, positionMs = 120_000L)
        assertEquals(CastPlaybackStatus.Ended, ended.status)
        assertFalse(ended.lastRemoteWasPlaying)
    }

    @Test
    fun command_failure_preserves_last_confirmed_transport_intent() {
        val failed =
            CastState()
                .connectingTo(receiver, positionMs = 0L)
                .remoteUpdate(CastPlaybackStatus.Playing, positionMs = 15_000L)
                .commandFailed("接收端拒绝调节音量")

        assertEquals(CastPlaybackStatus.Error, failed.status)
        assertTrue(failed.sessionConfirmed)
        assertTrue(failed.lastRemoteWasPlaying)
        assertEquals("接收端拒绝调节音量", failed.error)
    }

    @Test
    fun unexpected_disconnect_recovers_from_last_confirmed_remote_position() {
        val disconnected =
            CastState()
                .connectingTo(receiver, positionMs = 0L)
                .remoteUpdate(CastPlaybackStatus.Playing, positionMs = 91_250L)
                .unexpectedDisconnect("连接中断")

        assertEquals(
            CastRecoveryDecision(positionMs = 91_250L, resumePlayback = true),
            castRecoveryDecision(disconnected, fallbackPositionMs = 7_000L),
        )
    }

    @Test
    fun unconfirmed_remote_position_uses_local_fallback_on_disconnect() {
        val disconnected =
            CastState()
                .connectingTo(receiver, positionMs = 30_000L)
                .unexpectedDisconnect("加载期间连接中断")

        assertEquals(
            CastRecoveryDecision(positionMs = 12_000L, resumePlayback = false),
            castRecoveryDecision(disconnected, fallbackPositionMs = 12_000L),
        )
    }

    @Test
    fun explicit_stop_never_triggers_disconnect_recovery() {
        val stopped =
            CastState()
                .connectingTo(receiver, positionMs = 1_000L)
                .remoteUpdate(CastPlaybackStatus.Paused, positionMs = 2_000L)
                .userStopped()

        assertEquals(CastTermination.UserStop, stopped.termination)
        assertFalse(stopped.hasActiveSession)
        assertNull(castRecoveryDecision(stopped, fallbackPositionMs = 3_000L))
    }

    @Test
    fun receiver_evidence_is_revision_scoped_and_cleared_between_loads() {
        val first =
            CastState()
                .connectingTo(receiver, positionMs = 0L)
                .withReceiverCapabilities(
                    revision = 1L,
                    dolbyVision = CastCapability.Supported,
                    dolbyAtmos = CastCapability.Supported,
                    requestedMedia = CastCapability.Supported,
                ).withReceiverOutputReceipt(
                    revision = 1L,
                    playbackConfirmed = true,
                    dolbyVisionOutput = true,
                    dolbyAtmosOutput = true,
                    detail = "PLAYING",
                )

        assertTrue(first.capabilities.receiverConfirmed)
        assertTrue(first.outputEvidence.dolbyVisionOutput)
        assertTrue(first.outputEvidence.dolbyAtmosOutput)

        val second = first.connectingTo(receiver, positionMs = 10_000L)
        assertEquals(2L, second.sessionRevision)
        assertFalse(second.capabilities.receiverConfirmed)
        assertFalse(second.outputEvidence.receiverConfirmed)
        assertEquals(
            second,
            second.withReceiverOutputReceipt(
                revision = 1L,
                playbackConfirmed = true,
                dolbyVisionOutput = true,
                dolbyAtmosOutput = true,
                detail = "stale",
            ),
        )
    }

    @Test
    fun dlna_time_and_cast_start_position_are_bounded_and_precise() {
        assertEquals(3_723_500L, parseDlnaTimeMillis("01:02:03.500"))
        assertEquals("00:01:30", formatDlnaTime(90_999L))
        assertEquals("00:00:00", formatDlnaTime(-1L))
        assertNull(parseDlnaTimeMillis("NOT_IMPLEMENTED"))
        assertNull(parseDlnaTimeMillis("00:61:00"))

        val requestedStart = CastState().connectingTo(receiver, positionMs = -1L)
        assertEquals(0L, requestedStart.positionMs)
    }

    @Test
    fun invalid_or_missing_url_is_rejected_without_a_load_attempt() {
        assertEquals("没有可用的投屏地址", castMediaUrlError(""))
        assertEquals("投屏地址必须使用 HTTP 或 HTTPS", castMediaUrlError("file:///video.mkv"))
        assertEquals("投屏地址无效", castMediaUrlError("https://server/video id"))
        assertNull(castMediaUrlError("https://server/video.mkv"))
    }
}
