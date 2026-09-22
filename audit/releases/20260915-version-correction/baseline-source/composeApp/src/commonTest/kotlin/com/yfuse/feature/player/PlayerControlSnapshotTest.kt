package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerControlSnapshotTest {
    @Test
    fun position_and_buffer_ticks_update_timeline_without_recomposing_the_control_reader() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val source = mutableStateOf(PlaybackState(positionMs = 1_000L, durationMs = 60_000L))
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
                    val chrome by rememberPlayerControlSnapshot(source)
                    val captured = chrome
                    SideEffect {
                        controls = captured
                        controlCompositions++
                    }
                    PlaybackTimelineContent(source) { current -> SideEffect { timeline = current } }
                }
                settle()
                val initial = controlCompositions
                repeat(20) { index ->
                    source.value =
                        source.value.copy(positionMs = 1_500L + index * 500L, bufferedPositionMs = 30_000L + index)
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
