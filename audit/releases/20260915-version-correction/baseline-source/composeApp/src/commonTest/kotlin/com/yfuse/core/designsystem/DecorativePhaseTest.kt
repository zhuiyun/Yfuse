package com.yfuse.core.designsystem

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DecorativePhaseTest {
    @Test
    fun feature_visibility_and_reduced_motion_remove_the_clock_and_allow_restart() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val enabled = mutableStateOf(false)
            val visible = mutableStateOf(true)
            val reduced = mutableStateOf(false)
            lateinit var phase: State<Float>
            var time = 0L

            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 100_000_000L
                clock.sendFrame(time)
                runCurrent()
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalRouteVisible provides visible.value,
                        LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value),
                    ) {
                        phase =
                            rememberDecorativePhase(
                                enabled.value,
                                periodMillis = 1_000,
                                rest = 0.5f,
                                label = "test",
                            )
                    }
                }
                repeat(3) { frame() }
                assertEquals(0.5f, phase.value)
                assertFalse(clock.hasAwaiters, "Disabled feature must not request animation frames")
                enabled.value = true
                repeat(4) { frame() }
                assertTrue(clock.hasAwaiters)
                val moving = phase.value
                frame()
                assertTrue(phase.value > moving)

                // Each policy change removes an already running clock, not just its visual output.
                for (gate in listOf(reduced, visible, enabled)) {
                    gate.value = gate === reduced
                    repeat(3) { frame() }
                    assertEquals(0.5f, phase.value)
                    assertFalse(clock.hasAwaiters, "Inactive policy must remove the frame subscription")
                    gate.value = gate !== reduced
                    repeat(4) { frame() }
                    assertTrue(clock.hasAwaiters, "Restoring the policy must restart the animation")
                }
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
