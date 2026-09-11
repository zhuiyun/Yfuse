package com.yfuse.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WaitingPulsePolicyTest {
    @Test
    fun zero_duration_scale_suspends_custom_loop_and_positive_scale_resumes_without_a_new_request() =
        runTest {
            val clock = BroadcastFrameClock()
            val scale = mutableStateOf(0f)
            val policy =
                object : MotionDurationScale {
                    override val scaleFactor: Float get() = scale.value
                }
            val recomposer = Recomposer(coroutineContext + clock + policy)
            val runner = launch(clock + policy) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val reduced = mutableStateOf(false)
            var nanos = 0L

            fun frames() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    advanceTimeBy(20L)
                    runCurrent()
                    nanos += 20_000_000L
                    clock.sendFrame(nanos)
                    runCurrent()
                }
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value),
                    ) {
                        Modifier.waitingPulse(true, CircleShape, Color.White)
                    }
                }
                advanceTimeBy(200L)
                frames()
                assertFalse(clock.hasAwaiters)
                scale.value = 1f
                frames()
                assertTrue(clock.hasAwaiters)
                scale.value = 0f
                frames()
                frames()
                assertFalse(clock.hasAwaiters)
                scale.value = 1f
                frames()
                assertTrue(clock.hasAwaiters)
                reduced.value = true
                frames()
                assertFalse(clock.hasAwaiters)
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
