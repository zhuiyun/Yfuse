package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PlayerRoot's answer to a silent fault YCore's runtime detector found, cheapest first. */
class PlaybackRuntimeFaultLadderTest {
    private fun FallbackLadders.step(
        fault: PlaybackRuntimeFaultKind = PlaybackRuntimeFaultKind.VideoOutputMissing,
        reopens: Int = 0,
        nativeOnly: Boolean = false,
        nativeRestarts: Int = 0,
        core2Adapter: Boolean = false,
        core2Disabled: Boolean = false,
        engineOrder: List<PlayerEngine> = listOf(PlayerEngine.Exo, PlayerEngine.Mpv, PlayerEngine.Mdk),
        enginesTried: Set<PlayerEngine> = emptySet(),
        buildKind: PlayerEngine = PlayerEngine.Exo,
        hasServerTranscode: Boolean = true,
        transcoding: Boolean = false,
    ) = runtimeFault(
        fault = fault,
        longBufferRecoveryAttempts = reopens,
        core2NativeOnlyActive = nativeOnly,
        nativeOnlyRecoveryAttempts = nativeRestarts,
        core2Adapter = core2Adapter,
        core2DisabledForSession = core2Disabled,
        engineOrder = engineOrder,
        enginesTried = enginesTried,
        buildKind = buildKind,
        hasServerTranscode = hasServerTranscode,
        transcoding = transcoding,
    )

    @Test
    fun a_starved_source_reopens_its_transport_twice_before_anything_else() {
        val starved = listOf(PlaybackRuntimeFaultKind.StartupNetworkTimeout, PlaybackRuntimeFaultKind.RebufferTimeout)
        forEachFallbackLadder { ladder ->
            for (fault in starved) {
                assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, ladder.step(fault, reopens = 0))
                assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, ladder.step(fault, reopens = 1))
                // Ahead of YCore's own steps too: a starved source is not the decoder's fault.
                assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, ladder.step(fault, nativeOnly = true))
                assertEquals(PlaybackRuntimeFaultStep.ReopenTransport, ladder.step(fault, core2Adapter = true))
                assertEquals(PlaybackRuntimeFaultStep.Engine(PlayerEngine.Mpv), ladder.step(fault, reopens = 2))
            }
        }
    }

    @Test
    fun a_decoder_or_output_fault_never_reopens_the_transport() {
        val local =
            listOf(
                PlaybackRuntimeFaultKind.StartupTimeout,
                PlaybackRuntimeFaultKind.PositionStalled,
                PlaybackRuntimeFaultKind.VideoOutputMissing,
                PlaybackRuntimeFaultKind.AudioOutputMissing,
            )
        forEachFallbackLadder { ladder ->
            for (fault in local) {
                assertEquals(PlaybackRuntimeFaultStep.Engine(PlayerEngine.Mpv), ladder.step(fault))
            }
        }
    }

    @Test
    fun ycore_native_restarts_in_place_twice_then_lets_the_fault_stand() {
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackRuntimeFaultStep.RestartNativePipeline, ladder.step(nativeOnly = true))
            assertEquals(
                PlaybackRuntimeFaultStep.RestartNativePipeline,
                ladder.step(nativeOnly = true, nativeRestarts = 1),
            )
            // Engines and a server stream are left, and Native-only still takes neither.
            assertEquals(
                PlaybackRuntimeFaultStep.NativeRestartsSpent,
                ladder.step(nativeOnly = true, nativeRestarts = 2, core2Adapter = true),
            )
            assertEquals(
                PlaybackRuntimeFaultStep.NativeRestartsSpent,
                ladder.step(
                    PlaybackRuntimeFaultKind.RebufferTimeout,
                    reopens = 2,
                    nativeOnly = true,
                    nativeRestarts = 2,
                ),
            )
        }
    }

    @Test
    fun the_ycore_trial_is_left_for_legacy_before_another_engine() {
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackRuntimeFaultStep.LeaveCore2Trial, ladder.step(core2Adapter = true))
            assertEquals(
                PlaybackRuntimeFaultStep.Engine(PlayerEngine.Mpv),
                ladder.step(core2Adapter = true, core2Disabled = true),
            )
        }
    }

    @Test
    fun the_next_engine_follows_the_plan_and_skips_the_faulted_and_tried_ones() {
        forEachFallbackLadder { ladder ->
            assertEquals(
                PlaybackRuntimeFaultStep.Engine(PlayerEngine.Mdk),
                ladder.step(
                    engineOrder = listOf(PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk),
                    enginesTried = setOf(PlayerEngine.Mpv),
                    buildKind = PlayerEngine.Exo,
                ),
            )
        }
    }

    @Test
    fun the_server_transcode_comes_after_the_last_engine_and_nothing_after_it() {
        val everyEngine = setOf(PlayerEngine.Exo, PlayerEngine.Mpv, PlayerEngine.Mdk)
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackRuntimeFaultStep.ServerTranscode, ladder.step(enginesTried = everyEngine))
            assertEquals(
                PlaybackRuntimeFaultStep.Exhausted,
                ladder.step(enginesTried = everyEngine, transcoding = true),
            )
            assertEquals(
                PlaybackRuntimeFaultStep.Exhausted,
                ladder.step(enginesTried = everyEngine, hasServerTranscode = false),
            )
        }
    }

    @Test
    fun budgets_come_back_only_thirty_seconds_past_the_last_recovery() {
        forEachFallbackLadder { ladder ->
            assertFalse(ladder.restoresRecoveryBudget(positionMs = 59_999L, lastRecoveryPositionMs = 30_000L))
            assertTrue(ladder.restoresRecoveryBudget(positionMs = 60_000L, lastRecoveryPositionMs = 30_000L))
            assertTrue(ladder.restoresRecoveryBudget(positionMs = 30_000L, lastRecoveryPositionMs = 0L))
        }
    }
}
