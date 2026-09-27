package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerHoldScanTest {
    private val step = 44f
    private val forward = 1
    private val back = -1

    @Test
    fun theGearsStandStillThenRunAtTenThirtyAndSixtyTimes() {
        assertEquals(listOf(0, 10, 30, 60), HOLD_SCAN_RATES)
        assertEquals(10, HOLD_SCAN_RATES[HOLD_SCAN_DEFAULT_GEAR])
        assertEquals(30, HOLD_SCAN_RATES[HOLD_SCAN_RAMP_GEAR])
    }

    @Test
    fun aFingerThatOnlyDriftsKeepsTenTimes() {
        assertEquals(1, holdScanGearFor(0f, forward, step, 1))
        assertEquals(1, holdScanGearFor(43f, forward, step, 1))
        assertEquals(1, holdScanGearFor(-43f, forward, step, 1))
    }

    @Test
    fun slidingTheScansWaySpeedsUpAndBackSlowsToAStop() {
        assertEquals(2, holdScanGearFor(44f, forward, step, 1))
        assertEquals(3, holdScanGearFor(88f, forward, step, 1))
        assertEquals(3, holdScanGearFor(500f, forward, step, 1))
        assertEquals(0, holdScanGearFor(-44f, forward, step, 1))
        assertEquals(0, holdScanGearFor(-500f, forward, step, 1))
        // Rewinding, the scan's way is to the left.
        assertEquals(2, holdScanGearFor(-44f, back, step, 1))
        assertEquals(0, holdScanGearFor(44f, back, step, 1))
    }

    @Test
    fun aFingerRestingOnAMarkDoesNotRattleBetweenGears() {
        assertEquals(3, holdScanGearFor(80f, forward, step, 3))
        assertEquals(2, holdScanGearFor(79f, forward, step, 3))
        assertEquals(0, holdScanGearFor(-36f, forward, step, 0))
        assertEquals(1, holdScanGearFor(-35f, forward, step, 0))
    }

    @Test
    fun unusableInputKeepsTheCurrentGear() {
        assertEquals(2, holdScanGearFor(Float.NaN, forward, step, 2))
        assertEquals(2, holdScanGearFor(100f, forward, 0f, 2))
        assertEquals(2, holdScanGearFor(100f, 0, step, 2))
    }

    @Test
    fun eachTickMovesTheGearsMultipleOfIt() {
        assertEquals(0L, holdScanStepMs(0, 300L))
        assertEquals(3_000L, holdScanStepMs(1, 300L))
        assertEquals(9_000L, holdScanStepMs(2, 300L))
        assertEquals(18_000L, holdScanStepMs(3, 300L))
    }

    @Test
    fun theHudSaysTheGearAndWhereTheScanHasGot() {
        assertEquals("快进 ×30 · 12:34 / 45:00", holdScanLabel(forward, 2, 754_000L, 2_700_000L))
        assertEquals("快退 停住 · 12:34 / 45:00", holdScanLabel(back, 0, 754_000L, 2_700_000L))
    }

    @Test
    fun aStillFingerRampsToThirtyAndItsJitterDoesNotUndoIt() {
        val gears = HoldScanGears()
        gears.start(500f)
        assertFalse(gears.follow(530f, forward, step))
        assertTrue(gears.ramp(forward, step))
        assertEquals(2, gears.gear)
        assertFalse(gears.follow(531f, forward, step))
        assertFalse(gears.follow(505f, forward, step))
        assertEquals(2, gears.gear)
        // A real slide back from there is one gear down, as it would be from a slide up.
        assertTrue(gears.follow(470f, forward, step))
        assertEquals(1, gears.gear)
    }

    @Test
    fun aRewindRampsTowardsTheLeft() {
        val gears = HoldScanGears()
        gears.start(100f)
        assertTrue(gears.ramp(back, step))
        assertFalse(gears.follow(100f, back, step))
        assertTrue(gears.follow(56f, back, step))
        assertEquals(3, gears.gear)
    }

    @Test
    fun onceTheFingerHasShiftedTheRampLeavesItAlone() {
        val gears = HoldScanGears()
        gears.start(500f)
        assertTrue(gears.follow(544f, forward, step))
        assertTrue(gears.follow(500f, forward, step))
        assertEquals(1, gears.gear)
        assertFalse(gears.ramp(forward, step))
        assertEquals(1, gears.gear)
    }
}
