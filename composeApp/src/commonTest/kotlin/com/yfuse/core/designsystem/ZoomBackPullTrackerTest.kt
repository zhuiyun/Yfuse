package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomBackPullTrackerTest {
    private val tracker = ZoomBackPullTracker()
    private val slop = 8f

    @Test
    fun aSmallDownwardJitterCannotStartReturning() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(0f, 4f), 16, slop)
        assertFalse(tracker.canPull)

        tracker.move(Offset(0f, 12f), 32, slop)
        assertTrue(tracker.canPull)
    }

    @Test
    fun diagonalAndHorizontalShelfDragsCannotBecomePullsLater() {
        listOf(Offset(16f, 14f), Offset(20f, 2f)).forEach { firstMove ->
            tracker.start(Offset.Zero, 0)
            tracker.move(firstMove, 16, slop)
            assertFalse(tracker.canPull)

            tracker.move(Offset(20f, 100f), 32, slop)
            assertFalse(tracker.canPull, "The shelf's gesture must keep its original owner")
        }
    }

    @Test
    fun aDeliberateDownwardPullMayHaveSomeHorizontalDrift() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(8f, 16f), 16, slop)
        assertTrue(tracker.canPull)
    }

    @Test
    fun scrollingToTheTopCannotDismissThePageInTheSameGesture() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(0f, 40f), 16, slop)
        tracker.onScroll(Offset(0f, 24f))
        assertFalse(tracker.canPull, "The list consumed part of the drag before reaching its top")

        tracker.move(Offset(0f, 140f), 32, slop)
        tracker.onScroll(Offset.Zero)
        assertFalse(tracker.canPull)

        tracker.start(Offset(0f, 140f), 48)
        tracker.move(Offset(0f, 180f), 64, slop)
        tracker.onScroll(Offset.Zero)
        assertTrue(tracker.canPull, "A new pull while already at the top remains available")
    }

    @Test
    fun scrollingAShelfPreventsTakeoverEvenBeforeTheDirectionIsDecided() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(3f, 1f), 16, slop)
        tracker.onScroll(Offset(3f, 0f))
        tracker.move(Offset(4f, 60f), 32, slop)
        assertFalse(tracker.canPull)
    }

    @Test
    fun anUpwardScrollCannotReverseIntoReturning() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(0f, -16f), 16, slop)
        tracker.move(Offset(0f, 100f), 32, slop)
        assertFalse(tracker.canPull)
    }

    @Test
    fun aSecondFingerOrAReleasedFingerCannotStartReturning() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(0f, 16f), 16, slop)
        tracker.multiTouch = true
        assertFalse(tracker.canPull)

        tracker.start(Offset.Zero, 32)
        tracker.move(Offset(0f, 16f), 48, slop)
        tracker.pressed = false
        assertFalse(tracker.canPull)
    }

    @Test
    fun takeoverDropsTheVelocityFromBeforeThePull() {
        tracker.start(Offset.Zero, 0)
        tracker.move(Offset(0f, 100f), 16, slop)
        tracker.move(Offset(0f, 200f), 32, slop)
        assertTrue(tracker.velocity.calculateVelocity().y > 0f)

        tracker.beginPull()
        assertFalse(tracker.canPull)
        assertTrue(tracker.pulled)
        assertTrue(tracker.velocity.calculateVelocity().y == 0f)
    }
}
