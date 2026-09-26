package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeActionsRowTest {
    // A 360px row at density 1: the open threshold is 64px, the run line 162px, the flick line 108px.
    private val width = 360f
    private val open = 64f

    @Test
    fun theRowFollowsTheFingerUntilEightyPercentThenAQuarterAsFar() {
        assertEquals(100f, swipeRowOffset(100f, width), 0.001f)
        assertEquals(-288f, swipeRowOffset(-288f, width), 0.001f)
        // 40px past 80% of the row moves it 10px.
        assertEquals(298f, swipeRowOffset(328f, width), 0.001f)
        assertEquals(-298f, swipeRowOffset(-328f, width), 0.001f)
        assertEquals(0f, swipeRowOffset(100f, 0f), 0.001f)
        assertEquals(0f, swipeRowOffset(Float.NaN, width), 0.001f)
    }

    @Test
    fun aDragOfAMovingRowPicksUpWhereTheFingerWouldHaveBeen() {
        listOf(0f, 88f, -88f, 288f, 298f, -310f).forEach { offset ->
            assertEquals(offset, swipeRowOffset(swipeRowTravel(offset, width), width), 0.001f)
        }
        assertEquals(328f, swipeRowTravel(298f, width), 0.001f)
    }

    @Test
    fun aSideWithNothingOnItDoesNotOpen() {
        assertEquals(0f, swipeRowAllowedTravel(40f, towardsRight = false, towardsLeft = true), 0.001f)
        assertEquals(-40f, swipeRowAllowedTravel(-40f, towardsRight = false, towardsLeft = true), 0.001f)
        assertEquals(0f, swipeRowAllowedTravel(-40f, towardsRight = true, towardsLeft = false), 0.001f)
        assertEquals(40f, swipeRowAllowedTravel(40f, towardsRight = true, towardsLeft = true), 0.001f)
    }

    @Test
    fun theRunLineIsFortyFivePercentOfTheRowEitherWay() {
        assertFalse(swipeRowPastRunLine(161f, width))
        assertTrue(swipeRowPastRunLine(162f, width))
        assertTrue(swipeRowPastRunLine(-170f, width))
        assertFalse(swipeRowPastRunLine(170f, 0f))
    }

    @Test
    fun aShortSlowDragClosesAndOnePastSixtyFourOpens() {
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(40f, 0f, width, open))
        assertEquals(SwipeRowSettle.Open, swipeRowSettle(70f, 0f, width, open))
        assertEquals(SwipeRowSettle.Open, swipeRowSettle(-70f, 0f, width, open))
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(0f, 2_000f, width, open))
    }

    @Test
    fun pastFortyFivePercentTheActionRunsWhateverTheSpeed() {
        assertEquals(SwipeRowSettle.Run, swipeRowSettle(170f, 0f, width, open))
        assertEquals(SwipeRowSettle.Run, swipeRowSettle(-170f, 0f, width, open))
        // Even let go while moving back: it was past the line.
        assertEquals(SwipeRowSettle.Run, swipeRowSettle(170f, -500f, width, open))
    }

    @Test
    fun aFlickRunsFromThirtyPercentOnlyWhenFastEnoughAndOutward() {
        // 0.9px/ms is 900px/s.
        assertEquals(SwipeRowSettle.Run, swipeRowSettle(120f, 1_000f, width, open))
        assertEquals(SwipeRowSettle.Run, swipeRowSettle(-120f, -1_000f, width, open))
        assertEquals(SwipeRowSettle.Open, swipeRowSettle(120f, 800f, width, open))
        // Fast, but not yet 30% of the row.
        assertEquals(SwipeRowSettle.Open, swipeRowSettle(100f, 1_000f, width, open))
        // Fast the wrong way: not a run.
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(120f, -1_000f, width, open))
    }

    @Test
    fun theOpenDecisionIsProjectedLikeEveryOtherDrag() {
        // 30px flicked outward at 400px/s projects to 98px: it opens.
        assertEquals(SwipeRowSettle.Open, swipeRowSettle(30f, 400f, width, open))
        // 80px flicked back at 300px/s projects to 29px: it closes.
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(80f, -300f, width, open))
    }

    @Test
    fun aRowWithNoRoomNeverSettlesOpen() {
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(80f, 0f, 0f, open))
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(Float.NaN, 0f, width, open))
        assertEquals(SwipeRowSettle.Closed, swipeRowSettle(80f, 0f, width, 0f))
    }
}
