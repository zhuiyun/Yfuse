package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NextUpRingCompositionTest {
    @Test
    fun item_switch_discards_previous_ring_and_inactive_policies_remove_frame_requests() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val key = mutableStateOf("first")
            val visible = mutableStateOf(true)
            val reduced = mutableStateOf(false)
            val advancing = mutableStateOf(true)
            lateinit var remaining: State<Float>
            var now = 0L

            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                now += 50_000_000L
                clock.sendFrame(now)
                runCurrent()
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalRouteVisible provides visible.value,
                        LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value),
                    ) {
                        remaining = rememberNextUpRemaining(key.value, 8_000L, advancing.value, 1f)
                    }
                }
                repeat(4) { frame() }
                val original = remaining
                key.value = "next"
                advancing.value = false
                repeat(3) { frame() }
                assertNotSame(original, remaining)
                assertEquals(8_000f, remaining.value)
                assertFalse(clock.hasAwaiters)
                for (gate in listOf(reduced, visible, advancing)) {
                    advancing.value = true
                    visible.value = true
                    reduced.value = false
                    repeat(3) { frame() }
                    assertTrue(clock.hasAwaiters)
                    gate.value = gate === reduced
                    repeat(3) { frame() }
                    assertEquals(8_000f, remaining.value)
                    assertFalse(clock.hasAwaiters)
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
