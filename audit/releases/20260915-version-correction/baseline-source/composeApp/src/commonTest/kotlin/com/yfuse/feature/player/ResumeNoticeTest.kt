package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResumeNoticeTest {
    @Test
    fun buffering_does_not_consume_the_three_second_offer_and_reopening_controls_does_not_replay_it() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val ready = mutableStateOf(false)
            val controls = mutableStateOf(true)
            val interrupted = mutableStateOf(false)
            lateinit var notice: ResumeNoticeState
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
                    notice = rememberResumeNotice(60_000L, 0, ready.value, controls.value, interrupted.value)
                }
                settle()
                advanceTimeBy(10_000L)
                settle()
                assertFalse(notice.visible)
                ready.value = true
                settle()
                assertTrue(notice.visible)
                advanceTimeBy(2_000L)
                ready.value = false
                settle()
                ready.value = true
                settle()
                advanceTimeBy(1_001L)
                settle()
                assertFalse(notice.visible)
                controls.value = false
                settle()
                controls.value = true
                settle()
                assertFalse(notice.visible)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.join()
            }
        }

    @Test
    fun dismissed_offer_cannot_be_revived_and_new_playback_has_no_offer() {
        val notice = ResumeNoticeState(0, true)
        notice.dismiss()
        notice.show()
        assertFalse(notice.visible)
        val fresh = ResumeNoticeState(0, false)
        fresh.show()
        assertFalse(fresh.visible)
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
