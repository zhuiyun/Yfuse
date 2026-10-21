package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CinemaPlaybackEntranceCompositionTest {
    @Test
    fun entrance_stops_frame_requests_and_item_switches_get_their_own_camera() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val key = mutableStateOf("first")
            val awaiting = mutableStateOf(true)
            val ready = mutableStateOf(false)
            val allowed = mutableStateOf(true)
            lateinit var entrance: CinemaPlaybackEntrance
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
                    entrance = rememberCinemaPlaybackEntrance(key.value, awaiting.value, ready.value, allowed.value)
                }
                repeat(20) { frame() }
                val first = entrance
                assertFalse(clock.hasAwaiters, "Waiting for the network must not keep requesting display frames")
                assertEquals(0f, entrance.revealProgress)
                ready.value = true
                awaiting.value = false
                repeat(3) { frame() }
                assertTrue(entrance.revealProgress > 0f)
                repeat(10) { frame() }
                assertFalse(clock.hasAwaiters)
                assertEquals(1f, entrance.revealProgress)

                // Same media after a decoder retry: no second opening and no transparent hole.
                awaiting.value = true
                ready.value = false
                repeat(3) { frame() }
                assertSame(first, entrance)
                assertFalse(entrance.choreographed)
                assertFalse(clock.hasAwaiters)

                key.value = "next"
                repeat(4) { frame() }
                assertNotSame(first, entrance)
                assertTrue(entrance.scale > 1f)
                assertTrue(clock.hasAwaiters)
                allowed.value = false
                repeat(3) { frame() }
                assertFalse(clock.hasAwaiters)
                assertEquals(1f, entrance.scale)
                allowed.value = true
                repeat(3) { frame() }
                assertFalse(clock.hasAwaiters, "Lifting suppression must not replay a cancelled entrance")
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
