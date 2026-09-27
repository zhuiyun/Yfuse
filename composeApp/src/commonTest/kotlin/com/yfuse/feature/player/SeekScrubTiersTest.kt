package com.yfuse.feature.player

import com.yfuse.core.designsystem.HapticSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeekScrubTiersTest {
    // At a density of 1, so pixels read as the design's dp.
    private val fine = 34f
    private val film = 84f
    private val slack = 6f
    private val step = 28f

    /** A hundred frames ten seconds apart over a 1 000 s file. */
    private val frames =
        SeekFilmstripFrames(TrickplayStoryboard("u/{index}.jpg", 320, 180, 10, 10, 10_000L, 100), 1_000_000L)

    private fun tier(
        lift: Float,
        current: SeekScrubTier,
        filmstrip: Boolean = true,
    ) = seekScrubTierFor(lift, current, fine, film, slack, filmstrip)

    private fun gesture() = SeekScrubGesture(fine, film, slack, step)

    @Test
    fun movingUpEntersEachTierAtItsLine() {
        assertEquals(SeekScrubTier.Normal, tier(33f, SeekScrubTier.Normal))
        assertEquals(SeekScrubTier.Fine, tier(34f, SeekScrubTier.Normal))
        assertEquals(SeekScrubTier.Fine, tier(83f, SeekScrubTier.Fine))
        assertEquals(SeekScrubTier.Filmstrip, tier(84f, SeekScrubTier.Fine))
        // A quick flick up skips straight past fine.
        assertEquals(SeekScrubTier.Filmstrip, tier(120f, SeekScrubTier.Normal))
    }

    @Test
    fun comingBackDownLeavesEachTierSixBelowItsLine() {
        assertEquals(SeekScrubTier.Fine, tier(28f, SeekScrubTier.Fine))
        assertEquals(SeekScrubTier.Normal, tier(27.9f, SeekScrubTier.Fine))
        assertEquals(SeekScrubTier.Filmstrip, tier(78f, SeekScrubTier.Filmstrip))
        assertEquals(SeekScrubTier.Fine, tier(77.9f, SeekScrubTier.Filmstrip))
        // Dropping a long way at once still honours fine's own slack on the way through.
        assertEquals(SeekScrubTier.Fine, tier(30f, SeekScrubTier.Filmstrip))
        assertEquals(SeekScrubTier.Normal, tier(10f, SeekScrubTier.Filmstrip))
    }

    @Test
    fun withoutTrickplayTheDragStopsAtFine() {
        assertEquals(SeekScrubTier.Fine, tier(200f, SeekScrubTier.Normal, filmstrip = false))
        assertEquals(SeekScrubTier.Fine, tier(90f, SeekScrubTier.Filmstrip, filmstrip = false))
        assertEquals(SeekScrubTier.Fine, tier(Float.NaN, SeekScrubTier.Filmstrip, filmstrip = false))
        assertEquals(SeekScrubTier.Normal, tier(Float.NaN, SeekScrubTier.Normal))
    }

    @Test
    fun finerIsAThresholdAndCoarserItsRelease() {
        assertEquals(HapticSignal.Threshold, seekScrubTierHaptic(SeekScrubTier.Normal, SeekScrubTier.Fine))
        assertEquals(HapticSignal.Threshold, seekScrubTierHaptic(SeekScrubTier.Fine, SeekScrubTier.Filmstrip))
        assertEquals(HapticSignal.ThresholdRelease, seekScrubTierHaptic(SeekScrubTier.Filmstrip, SeekScrubTier.Fine))
        assertEquals(HapticSignal.ThresholdRelease, seekScrubTierHaptic(SeekScrubTier.Fine, SeekScrubTier.Normal))
        assertNull(seekScrubTierHaptic(SeekScrubTier.Fine, SeekScrubTier.Fine))
    }

    @Test
    fun aNormalDragKeepsTheThumbUnderTheFinger() {
        val drag = gesture()
        assertEquals(0.5f, drag.begin(500f, 500f, 1_000f))
        assertEquals(0.6f, drag.move(600f, 505f, 1_000f, 0.5f, frames), 0.0001f)
        assertEquals(0.25f, drag.move(250f, 480f, 1_000f, 0.6f, frames), 0.0001f)
        assertEquals(SeekScrubTier.Normal, drag.tier)
    }

    @Test
    fun theFineTierMovesAQuarterAsFarAndPicksUpWhereTheThumbIs() {
        val drag = gesture()
        drag.begin(500f, 500f, 1_000f)
        drag.move(600f, 500f, 1_000f, 0.5f, frames)
        // Up 40: fine, from the thumb as drawn — no jump on the way in.
        assertEquals(0.6f, drag.move(600f, 460f, 1_000f, 0.6f, frames), 0.0001f)
        assertEquals(SeekScrubTier.Fine, drag.tier)
        assertEquals(40f, drag.liftPx)
        assertEquals(0.625f, drag.move(700f, 460f, 1_000f, 0.6f, frames), 0.0001f)
        assertEquals(0.55f, drag.move(400f, 460f, 1_000f, 0.625f, frames), 0.0001f)
    }

    @Test
    fun backAtNormalTheThumbCarriesOnAndMeetsTheFingerAtTheEnds() {
        val drag = gesture()
        drag.begin(500f, 500f, 1_000f)
        drag.move(500f, 460f, 1_000f, 0.5f, frames)
        drag.move(700f, 460f, 1_000f, 0.5f, frames)
        // Down past fine's slack: normal again, still at 0.55 where fine left it.
        assertEquals(0.55f, drag.move(700f, 473f, 1_000f, 0.55f, frames), 0.0001f)
        assertEquals(SeekScrubTier.Normal, drag.tier)
        assertEquals(1f, drag.move(1_000f, 473f, 1_000f, 0.55f, frames), 0.0001f)
        assertEquals(0f, drag.move(0f, 473f, 1_000f, 1f, frames), 0.0001f)
    }

    @Test
    fun theFilmstripStepsAFramePerStepAndLandsOnTheFramesTime() {
        val drag = gesture()
        drag.begin(500f, 500f, 1_000f)
        // Up 90 at once: the filmstrip, on the frame nearest the thumb.
        assertEquals(0.5f, drag.move(500f, 410f, 1_000f, 0.5f, frames), 0.0001f)
        assertEquals(SeekScrubTier.Filmstrip, drag.tier)
        assertEquals(50, drag.frame)
        assertEquals(0.5f, drag.move(527f, 410f, 1_000f, 0.5f, frames), 0.0001f)
        assertEquals(0.51f, drag.move(528f, 410f, 1_000f, 0.5f, frames), 0.0001f)
        assertEquals(51, drag.frame)
        // Coming back takes a whole step, so a finger resting on the line does not rattle.
        drag.move(501f, 410f, 1_000f, 0.51f, frames)
        assertEquals(51, drag.frame)
        drag.move(500f, 410f, 1_000f, 0.51f, frames)
        assertEquals(50, drag.frame)
        // Several steps in one move, and never past the last frame.
        drag.move(612f, 410f, 1_000f, 0.5f, frames)
        assertEquals(54, drag.frame)
        drag.move(99_000f, 410f, 1_000f, 0.54f, frames)
        assertEquals(99, drag.frame)
        assertEquals(990_000L, frames.startMs(drag.frame))
    }

    @Test
    fun theFilmstripStaysWithinItsSlackAndLeavesBelowIt() {
        val drag = gesture()
        drag.begin(500f, 500f, 1_000f)
        drag.move(500f, 410f, 1_000f, 0.5f, frames)
        drag.move(500f, 420f, 1_000f, 0.5f, frames)
        assertEquals(SeekScrubTier.Filmstrip, drag.tier)
        drag.move(500f, 423f, 1_000f, 0.5f, frames)
        assertEquals(SeekScrubTier.Fine, drag.tier)
    }

    @Test
    fun withoutFramesAHighFingerIsOnlyFine() {
        val drag = gesture()
        drag.begin(500f, 500f, 1_000f)
        drag.move(500f, 300f, 1_000f, 0.5f, null)
        assertEquals(SeekScrubTier.Fine, drag.tier)
    }

    @Test
    fun framesPastTheEndOfTheFileAreNotOffered() {
        val padded = SeekFilmstripFrames(TrickplayStoryboard("u", 320, 180, 10, 10, 10_000L, 100), 95_000L)
        assertEquals(10, padded.count)
        assertEquals(90_000L, padded.startMs(99))
        val listed =
            SeekFilmstripFrames(
                TrickplayStoryboard(
                    "u",
                    320,
                    180,
                    1,
                    1,
                    10_000L,
                    4,
                    frames =
                        listOf(
                            TrickplayStoryboardFrame(0L, "a"),
                            TrickplayStoryboardFrame(4_000L, "b"),
                            TrickplayStoryboardFrame(9_000L, "c"),
                            TrickplayStoryboardFrame(20_000L, "d"),
                        ),
                ),
                10_000L,
            )
        assertEquals(3, listed.count)
        assertEquals(1, listed.indexAt(8_999L))
        assertEquals(2, listed.nearestIndex(0.8f))
        assertNull(listed.stepMs)
    }

    @Test
    fun theNearestFrameIsChosenOnEitherSide() {
        assertEquals(12, frames.nearestIndex(0.1249f))
        assertEquals(13, frames.nearestIndex(0.1251f))
        assertEquals(0, frames.nearestIndex(0f))
        assertEquals(99, frames.nearestIndex(1f))
    }

    @Test
    fun shiftArrowsWalkTheStoryboardFrameByFrame() {
        assertEquals(20_000L, filmstripStepTargetMs(frames, 12_345L, 1))
        assertEquals(10_000L, filmstripStepTargetMs(frames, 12_345L, -1))
        // Just past a frame's start, back means the frame before it.
        assertEquals(0L, filmstripStepTargetMs(frames, 10_400L, -1))
        assertEquals(0L, filmstripStepTargetMs(frames, 0L, -1))
        assertEquals(990_000L, filmstripStepTargetMs(frames, 995_000L, 1))
    }

    @Test
    fun theStripShowsAnOddNumberOfFramesThatFit() {
        // 64 wide, 4 apart, 6 either side: seven need 484.
        assertEquals(7, filmstripSlots(484f, 64f, 4f, 6f))
        assertEquals(7, filmstripSlots(2_000f, 64f, 4f, 6f))
        // Six fit in 480 — the odd count below keeps the selection in the middle.
        assertEquals(5, filmstripSlots(480f, 64f, 4f, 6f))
        assertEquals(1, filmstripSlots(40f, 64f, 4f, 6f))
    }

    @Test
    fun theStripSaysHowFarApartItsFramesAre() {
        assertEquals("胶片条 · 每格 10 秒", filmstripHeading(10_000L))
        assertEquals("胶片条 · 每格 2.5 秒", filmstripHeading(2_500L))
        assertEquals("胶片条 · 逐格", filmstripHeading(null))
        assertEquals("胶片条 · 每格 10 秒", filmstripHeading(frames.stepMs))
    }

    @Test
    fun convergingFractionIsTheFingersOwnWhenItIsTheAnchor() {
        assertEquals(0.3f, convergingFraction(300f, 1_000f, 700f, 0.7f), 0.0001f)
        assertEquals(0.9f, convergingFraction(900f, 1_000f, 700f, 0.7f), 0.0001f)
        // Anchored off the finger, the ends still line up with the rail's.
        assertEquals(1f, convergingFraction(1_200f, 1_000f, 800f, 0.5f), 0.0001f)
        assertEquals(0.75f, convergingFraction(900f, 1_000f, 800f, 0.5f), 0.0001f)
        assertEquals(0.25f, convergingFraction(400f, 1_000f, 800f, 0.5f), 0.0001f)
    }
}
