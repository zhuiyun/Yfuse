package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackRuntimeContentTest {
    @Test
    fun runtime_ticks_and_telemetry_keep_live_values_without_recomposing_routing() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            var source by mutableStateOf(MutableStateFlow(PlaybackState(positionMs = 1_000L, durationMs = 60_000L)))
            val routedStates = mutableListOf<PlaybackState>()
            var controlCompositions = 0
            var controls = PlaybackState()
            var timeline = PlaybackState()
            var nanos = 0L

            fun settle() {
                repeat(3) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    nanos += 16_000_000L
                    clock.sendFrame(nanos)
                    runCurrent()
                }
            }
            try {
                composition.setContent {
                    PlaybackRuntimeContent(owner = source, source = source, items = emptyList()) { structural, live ->
                        SideEffect {
                            controls = structural
                            routedStates += structural
                            controlCompositions++
                        }
                        PlaybackTimelineContent(live) { current -> SideEffect { timeline = current } }
                    }
                }
                settle()
                val initial = controlCompositions
                repeat(20) { index ->
                    source.value =
                        source.value.copy(
                            positionMs = 1_500L + index * 500L,
                            bufferedPositionMs = 30_000L + index,
                            diagnostics =
                                source.value.diagnostics.copy(
                                    frameRate = 23.9f + index * 0.01f,
                                    networkBitsPerSecond = 1_000_000L + index,
                                    droppedFrames = index,
                                    avSyncOffsetMs = index.toLong(),
                                    playbackHealth = "sample $index",
                                    outputEvidence =
                                        source.value.diagnostics.outputEvidence.copy(
                                            rendererDetail = "frame $index",
                                        ),
                                ),
                        )
                    settle()
                    assertEquals(source.value, timeline)
                    assertEquals(initial, controlCompositions)
                }
                source.value = source.value.copy(playing = true, buffering = false, speed = 2f, currentIndex = 1)
                settle()
                assertTrue(controlCompositions > initial)
                assertEquals(source.value.playing, controls.playing)
                assertEquals(source.value.currentIndex, controls.currentIndex)
                assertEquals(source.value.speed, controls.speed)
                val beforeRestart = controlCompositions
                source.value = source.value.copy(positionMs = 0L)
                settle()
                assertTrue(
                    controlCompositions > beforeRestart,
                    "The started/not-started boundary still reaches auto-hide policy",
                )
                assertEquals(0L, controls.positionMs)
                source.value = source.value.copy(ended = true, playing = false)
                settle()
                routedStates.clear()
                source = MutableStateFlow(PlaybackState(buffering = true, durationMs = 0L))
                settle()
                assertTrue(routedStates.isNotEmpty())
                assertTrue(
                    routedStates.all { it.buffering && !it.ended },
                    "A new backend must not see old terminal flags",
                )
                assertEquals(60_000L, timeline.durationMs, "Same-item handover preserves the established timeline")
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.join()
            }
        }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }
}
