package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSpeedBoostTest {
    private val step = 100f
    private val slow = 0
    private val middle = SPEED_BOOST_DEFAULT_GEAR
    private val fast = SPEED_BOOST_GEARS.lastIndex

    @Test
    fun holdStartsAtTwiceSpeedWithFasterGearsToTheRight() {
        assertEquals(listOf(1.5f, 2f, 3f), SPEED_BOOST_GEARS)
        assertEquals(2f, SPEED_BOOST_GEARS[SPEED_BOOST_DEFAULT_GEAR])
    }

    @Test
    fun aFingerThatOnlyDriftsKeepsTheMiddleGear() {
        assertEquals(middle, speedBoostGearFor(0f, step, middle))
        assertEquals(middle, speedBoostGearFor(99f, step, middle))
        assertEquals(middle, speedBoostGearFor(-99f, step, middle))
    }

    @Test
    fun eachFullStepOutwardsIsOneGear() {
        assertEquals(fast, speedBoostGearFor(100f, step, middle))
        assertEquals(slow, speedBoostGearFor(-100f, step, middle))
        // Past the last gear there is nothing further to reach.
        assertEquals(fast, speedBoostGearFor(900f, step, middle))
        assertEquals(slow, speedBoostGearFor(-900f, step, middle))
    }

    @Test
    fun aFingerRestingOnAMarkDoesNotRattleBetweenGears() {
        // Came in over the mark at 100: jitter just behind it stays in the fast gear.
        assertEquals(fast, speedBoostGearFor(95f, step, fast))
        assertEquals(fast, speedBoostGearFor(81f, step, fast))
        assertEquals(middle, speedBoostGearFor(79f, step, fast))
        assertEquals(slow, speedBoostGearFor(-81f, step, slow))
        assertEquals(middle, speedBoostGearFor(-79f, step, slow))
    }

    @Test
    fun aFastSwipeCrossesStraightToTheOtherSide() {
        assertEquals(slow, speedBoostGearFor(-100f, step, fast))
        assertEquals(fast, speedBoostGearFor(100f, step, slow))
    }

    @Test
    fun unusableGeometryKeepsTheCurrentGear() {
        assertEquals(fast, speedBoostGearFor(Float.NaN, step, fast))
        assertEquals(slow, speedBoostGearFor(50f, 0f, slow))
        assertEquals(middle, speedBoostGearFor(500f, Float.POSITIVE_INFINITY, middle))
    }

    @Test
    fun labelsDropTheTrailingZeroOfAWholeGear() {
        assertEquals(listOf("1.5×", "2×", "3×"), SPEED_BOOST_GEARS.map(::speedBoostLabel))
    }

    @Test
    fun anOrdinaryHoldIsAllowed() {
        assertNull(refusal())
    }

    @Test
    fun roomsAndCastingSayWhyWhileOtherRefusalsStayQuiet() {
        assertEquals(SpeedBoostRefusal.PanelOpen, refusal(panelOpen = true))
        assertEquals(SpeedBoostRefusal.WatchGuest, refusal(watchGuest = true, watchRoom = true))
        assertEquals(SpeedBoostRefusal.WatchRoom, refusal(watchRoom = true))
        assertEquals(SpeedBoostRefusal.Casting, refusal(casting = true))
        assertEquals(SpeedBoostRefusal.NothingToPlay, refusal(durationMs = 0L))
        assertEquals(SpeedBoostRefusal.NothingToPlay, refusal(finished = true))

        assertNull(SpeedBoostRefusal.PanelOpen.message)
        assertNull(SpeedBoostRefusal.NothingToPlay.message)
        assertEquals("房主控制播放", SpeedBoostRefusal.WatchGuest.message)
    }

    @Test
    fun anOpenPanelTakesThePressBeforeAnythingIsAnnounced() {
        assertEquals(SpeedBoostRefusal.PanelOpen, refusal(panelOpen = true, watchGuest = true, casting = true))
    }

    private fun refusal(
        panelOpen: Boolean = false,
        watchGuest: Boolean = false,
        watchRoom: Boolean = false,
        casting: Boolean = false,
        durationMs: Long = 60_000L,
        finished: Boolean = false,
    ): SpeedBoostRefusal? =
        speedBoostRefusal(
            panelOpen = panelOpen,
            watchGuest = watchGuest,
            watchRoom = watchRoom,
            casting = casting,
            durationMs = durationMs,
            finished = finished,
        )
}
