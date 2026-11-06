package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRuntimeFaultLadderTest {
    private fun step(
        fault: PlaybackRuntimeFaultKind = PlaybackRuntimeFaultKind.VideoOutputMissing,
        reopens: Int = 0,
        restarts: Int = 0,
        engine: PlayerEngine = PlayerEngine.YCore,
        server: Boolean = false,
    ) = PlaybackFallbackLadder.nextRuntimeFaultStep(fault, reopens, restarts, engine, server)

    @Test
    fun starving_sources_reopen_twice_before_native_restarts() {
        listOf(
            PlaybackRuntimeFaultKind.StartupNetworkTimeout,
            PlaybackRuntimeFaultKind.RebufferTimeout,
        ).forEach { fault ->
            assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, step(fault, reopens = 0))
            assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, step(fault, reopens = 1))
            assertEquals(PlaybackRuntimeFaultStep.RestartNativePipeline, step(fault, reopens = 2))
        }
    }

    @Test
    fun output_faults_restart_only_ycore_and_never_switch_to_sen() {
        listOf(
            PlaybackRuntimeFaultKind.StartupTimeout,
            PlaybackRuntimeFaultKind.PositionStalled,
            PlaybackRuntimeFaultKind.VideoOutputMissing,
            PlaybackRuntimeFaultKind.AudioOutputMissing,
        ).forEach { fault ->
            assertEquals(PlaybackRuntimeFaultStep.RestartNativePipeline, step(fault))
            assertEquals(PlaybackRuntimeFaultStep.RestartNativePipeline, step(fault, restarts = 1))
            assertEquals(PlaybackRuntimeFaultStep.NativeRestartsSpent, step(fault, restarts = 2))
        }
    }

    @Test
    fun spent_restarts_can_only_use_an_approved_server_source() {
        assertEquals(PlaybackRuntimeFaultStep.ServerTranscode, step(restarts = 2, server = true))
        assertEquals(PlaybackRuntimeFaultStep.NativeRestartsSpent, step(restarts = 2, server = false))
    }

    @Test
    fun sen_does_not_misrepresent_ycore_restart_support() {
        assertEquals(PlaybackRuntimeFaultStep.Exhausted, step(engine = PlayerEngine.SenPlayer))
        assertEquals(PlaybackRuntimeFaultStep.ServerTranscode, step(engine = PlayerEngine.SenPlayer, server = true))
    }

    @Test
    fun recovery_budget_requires_thirty_seconds_of_real_forward_progress_without_overflow() {
        assertFalse(PlaybackFallbackLadder.restoresRecoveryBudget(59_999L, 30_000L))
        assertTrue(PlaybackFallbackLadder.restoresRecoveryBudget(60_000L, 30_000L))
        assertFalse(PlaybackFallbackLadder.restoresRecoveryBudget(100L, Long.MAX_VALUE - 1L))
        assertFalse(PlaybackFallbackLadder.restoresRecoveryBudget(Long.MAX_VALUE, Long.MAX_VALUE - 10L))
        assertFalse(PlaybackFallbackLadder.restoresRecoveryBudget(30_000L, -1L))
    }
}
