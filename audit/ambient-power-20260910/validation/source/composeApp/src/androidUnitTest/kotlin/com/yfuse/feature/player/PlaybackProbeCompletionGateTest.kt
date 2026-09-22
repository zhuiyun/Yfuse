package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackProbeResult
import com.yfuse.core.playback.PlaybackProbeStatus
import com.yfuse.core.playback.PlaybackSourceRequirements
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackProbeCompletionGateTest {
    @Test
    fun buffering_cancellation_leaves_the_same_item_eligible_after_playback_settles() =
        runTest {
            val gate = PlaybackProbeCompletionGate()
            val entered = CompletableDeferred<Unit>()
            val first =
                launch {
                    gate.run("item") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            first.cancelAndJoin()
            assertTrue(gate.needsProbe("item"))
            assertNotNull(gate.run("item") { result(PlaybackProbeStatus.Complete) })
            assertFalse(gate.needsProbe("item"))
            assertNull(gate.run("item") { error("Completed probes must not restart during repeated buffering") })
        }

    @Test
    fun a_busy_or_timed_out_lane_can_be_retried_and_a_new_source_gets_a_fresh_probe() =
        runTest {
            val gate = PlaybackProbeCompletionGate()
            assertEquals(
                PlaybackProbeStatus.TimedOut,
                gate.run("item") { result(PlaybackProbeStatus.TimedOut) }?.status,
            )
            assertTrue(gate.needsProbe("item"))
            gate.run("item") { result(PlaybackProbeStatus.Complete) }
            assertTrue(gate.needsProbe("next item"))
            assertNotNull(gate.run("next item") { result(PlaybackProbeStatus.Complete) })
        }

    private fun result(status: PlaybackProbeStatus): PlaybackProbeResult =
        PlaybackProbeResult(
            status,
            PlaybackMediaProbe(
                container = "MP4",
                discSource = false,
                source =
                    PlaybackSourceRequirements(
                        dolbyVision = false,
                        needsDolbyDecoder = false,
                        dynamicRange = null,
                    ),
                hasServerTranscode = false,
            ),
        )
}
