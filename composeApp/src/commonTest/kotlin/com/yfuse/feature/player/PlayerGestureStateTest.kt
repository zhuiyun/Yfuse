package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import com.yfuse.core.designsystem.DragAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerGestureStateTest {
    private var now = 0L
    private val gestures = PlayerGestureState(DoubleTapSeekBurst(nowMs = { now }))
    private val hour = 3_600_000L

    private fun tap(
        direction: Int,
        taps: Int,
        positionMs: Long,
        at: Offset = Offset.Zero,
    ): Long? = gestures.burstSeek(direction, at, taps, stepMs = 10_000L, positionMs = positionMs, durationMs = hour)

    /** One move of a drag across a 1,000 × 500 px picture at density 1, the playhead at 1:00. */
    private fun drag(
        dx: Float,
        dy: Float,
        watchGuest: Boolean = false,
        swapBrightnessVolume: Boolean = false,
        hasNext: Boolean = false,
        hasPrevious: Boolean = false,
    ): PictureLevel? =
        gestures.drag(
            dx = dx,
            dy = dy,
            dtMs = 16L,
            width = 1_000,
            height = 500,
            density = 1f,
            positionMs = 60_000L,
            durationMs = hour,
            watchGuest = watchGuest,
            swapBrightnessVolume = swapBrightnessVolume,
            hasNext = hasNext,
            hasPrevious = hasPrevious,
        )

    private fun startDrag(
        x: Float,
        changesEpisode: Boolean = false,
    ) = gestures.startDrag(
        x,
        positionMs = 60_000L,
        volume = 0.5f,
        brightness = 0.5f,
        changesEpisode = changesEpisode,
    )

    @Test
    fun theHudSaysWhatItIsToldUntilCleared() {
        assertNull(gestures.hud)
        gestures.say("字幕 · 中文")
        assertEquals("字幕 · 中文", gestures.hud)
        gestures.say(null)
        assertNull(gestures.hud)
    }

    @Test
    fun aDoubleTapPulsesWhereItLandedAndTheHudCountsTheWholeRun() {
        val at = Offset(120f, 80f)
        assertEquals(70_000L, tap(direction = 1, taps = 2, positionMs = 60_000L, at = at))
        assertEquals("快进 10 秒", gestures.hud)
        assertEquals(1, gestures.pulseRevision)
        assertEquals(at, gestures.pulsePosition)

        now += 500L
        assertTrue(gestures.burstContinues(1))
        assertFalse(gestures.burstContinues(-1))
        // A single tap inside the run is one more step, and the HUD says the total so far.
        assertEquals(80_000L, tap(direction = 1, taps = 1, positionMs = 70_000L))
        assertEquals("快进 20 秒", gestures.hud)
        assertEquals(2, gestures.pulseRevision)
    }

    @Test
    fun aBurstStaysInsideTheItem() {
        assertEquals(0L, tap(direction = -1, taps = 2, positionMs = 4_000L))
        assertEquals("快退 10 秒", gestures.hud)
        now += DOUBLE_TAP_BURST_WINDOW_MS
        assertFalse(gestures.burstContinues(-1))
        assertEquals(hour, tap(direction = 1, taps = 2, positionMs = hour - 3_000L))
        assertEquals("快进 10 秒", gestures.hud)
    }

    @Test
    fun aDoubleTapBeforeTheDurationIsKnownCountsNothingAndSaysNothing() {
        // Opening at a resume point, the clamp could only have landed on 0:00.
        assertNull(gestures.burstSeek(1, Offset.Zero, taps = 2, stepMs = 10_000L, positionMs = 0L, durationMs = 0L))
        assertNull(gestures.hud)
        assertEquals(0, gestures.pulseRevision)
        assertFalse(gestures.burstContinues(1))
    }

    @Test
    fun aSlowBackendCannotLoseStepsInAForwardOrRewindBurst() {
        assertEquals(70_000L, tap(1, 2, 60_000L))
        now += 100L
        assertEquals(80_000L, tap(1, 1, 60_000L))
        now += 100L
        assertEquals(100_000L, tap(1, 2, 60_000L))
        assertEquals("快进 40 秒", gestures.hud)
        gestures.resetBurst()
        assertEquals(50_000L, tap(-1, 2, 60_000L))
        now += 100L
        assertEquals(40_000L, tap(-1, 1, 60_000L))
    }

    @Test
    fun anAcknowledgedForwardSeekIncludesPlaybackProgressWithoutDoubleCounting() {
        assertEquals(70_000L, tap(1, 2, 60_000L))
        now += 500L
        assertEquals(80_500L, tap(1, 1, 70_500L))
    }

    @Test
    fun replacementClearsTheBurstAndItsOldTargetBeforeTheNextTap() {
        tap(1, 2, 60_000L)
        gestures.resetBurst()
        assertFalse(gestures.burstContinues(1))
        assertNull(gestures.hud)
        assertEquals(15_000L, tap(1, 2, 5_000L))
        assertEquals("快进 10 秒", gestures.hud)
    }

    @Test
    fun aNewBurstAfterTheWindowUsesTheCurrentPlayhead() {
        tap(1, 2, 60_000L)
        now += DOUBLE_TAP_BURST_WINDOW_MS
        assertEquals(30_000L, tap(1, 2, 20_000L))
    }

    @Test
    fun theHeldMiddleStartsAtTwiceAndASlideShiftsAGearAtATime() {
        gestures.say("快进 10 秒")
        assertEquals(2f, gestures.startBoost(x = 300f))
        assertEquals(SPEED_BOOST_DEFAULT_GEAR, gestures.boostGear)
        assertTrue(gestures.boosting)
        assertNull(gestures.hud)
        // A finger that only drifts keeps its gear.
        assertNull(gestures.followBoost(x = 343f, stepPx = 44f))
        assertEquals(3f, gestures.followBoost(x = 344f, stepPx = 44f))
        assertEquals(1.5f, gestures.followBoost(x = 256f, stepPx = 44f))
        assertTrue(gestures.endBoost())
        assertFalse(gestures.boosting)
        assertFalse(gestures.endBoost())
        assertNull(gestures.followBoost(x = 400f, stepPx = 44f))
    }

    @Test
    fun aSidewaysDragPreviewsWhereItWouldLandAndLandsThereOnRelease() {
        startDrag(x = 500f)
        assertNull(drag(dx = 40f, dy = 0f))
        assertEquals(DragAxis.Horizontal, gestures.dragAxis)
        // As far as a fresh swipe at this pace goes; see SwipeSeekPace.
        val landing = 60_000L + SwipeSeekPace().step(40f, 16L, 1_000, 1f, hour)
        assertTrue(landing > 60_000L)
        assertEquals(landing, gestures.pictureScrubMs)
        assertEquals(landing, gestures.previewMs)
        assertEquals("+${(landing - 60_000L).asClock()} · ${landing.asClock()} / 60:00", gestures.hud)
        // However far the thumb then wanders upright, a drag that began sideways stays a seek.
        assertNull(drag(dx = 0f, dy = -400f))
        assertEquals(DragAxis.Horizontal, gestures.dragAxis)
        assertEquals(landing, gestures.endDrag(durationMs = hour, watchGuest = false))
        assertNull(gestures.pictureScrubMs)
    }

    @Test
    fun aSidewaysDragLandsNowhereForAGuestOrWithNothingToSeekIn() {
        startDrag(x = 500f)
        assertNull(drag(dx = 40f, dy = 0f, watchGuest = true))
        assertEquals("房主控制播放", gestures.hud)
        assertNull(gestures.pictureScrubMs)
        assertNull(gestures.endDrag(durationMs = hour, watchGuest = true))

        startDrag(x = 500f)
        drag(dx = 40f, dy = 0f)
        assertNull(gestures.endDrag(durationMs = 0L, watchGuest = false))
    }

    @Test
    fun aSidewaysDragBeforeTheDurationIsKnownShowsNoTarget() {
        startDrag(x = 500f)
        val level =
            gestures.drag(
                dx = 40f,
                dy = 0f,
                dtMs = 16L,
                width = 1_000,
                height = 500,
                density = 1f,
                positionMs = 0L,
                durationMs = 0L,
                watchGuest = false,
                swapBrightnessVolume = false,
            )
        assertNull(level)
        assertEquals(DragAxis.Horizontal, gestures.dragAxis)
        assertNull(gestures.pictureScrubMs)
        assertNull(gestures.hud)
        assertNull(gestures.endDrag(durationMs = 0L, watchGuest = false))
    }

    @Test
    fun anUprightDragSetsBrightnessOnTheLeftAndVolumeOnTheRightUnlessSwapped() {
        startDrag(x = 200f)
        // A quarter of the picture's height up.
        assertEquals(PictureLevel.Brightness(0.75f), drag(dx = 0f, dy = -125f))
        assertEquals("亮度 75%", gestures.hud)
        assertEquals(DragAxis.Vertical, gestures.dragAxis)
        assertNull(gestures.endDrag(durationMs = hour, watchGuest = false))

        startDrag(x = 800f)
        assertEquals(PictureLevel.Volume(0.25f), drag(dx = 0f, dy = 125f))
        assertEquals("音量 25%", gestures.hud)

        startDrag(x = 200f)
        assertEquals(PictureLevel.Volume(0.75f), drag(dx = 0f, dy = -125f, swapBrightnessVolume = true))
    }

    @Test
    fun downAShortDramasMiddleAnUprightDragChangesEpisodeInsteadOfTheLevels() {
        startDrag(x = 500f, changesEpisode = true)
        // An eighth of the 500 px picture is the threshold: short of it nothing is armed.
        assertNull(drag(dx = 0f, dy = -40f, hasNext = true, hasPrevious = true))
        assertEquals(EpisodeSwipe.None, gestures.episodeArmed)
        assertNull(drag(dx = 0f, dy = -40f, hasNext = true, hasPrevious = true))
        assertEquals(EpisodeSwipe.Next, gestures.episodeArmed)
        assertEquals("松手播放下一集", gestures.hud)
        assertEquals(EpisodeSwipe.Next, gestures.endEpisodeDrag(height = 500, hasNext = true, hasPrevious = true))
        assertNull(gestures.hud)
        // No seek lands from it either.
        assertNull(gestures.endDrag(durationMs = hour, watchGuest = false))

        startDrag(x = 500f, changesEpisode = true)
        assertNull(drag(dx = 0f, dy = 100f, hasNext = true, hasPrevious = false))
        assertEquals(EpisodeSwipe.None, gestures.episodeArmed)
        assertEquals("已是第一集", gestures.hud)
        assertEquals(EpisodeSwipe.None, gestures.endEpisodeDrag(height = 500, hasNext = true, hasPrevious = false))
    }

    @Test
    fun anEpisodeDragIsTheHostsInARoomAndASidewaysOneStillSeeks() {
        startDrag(x = 500f, changesEpisode = true)
        assertNull(drag(dx = 0f, dy = -100f, watchGuest = true, hasNext = true))
        assertEquals(EpisodeSwipe.None, gestures.episodeArmed)
        assertEquals("房主控制播放", gestures.hud)

        startDrag(x = 500f, changesEpisode = true)
        drag(dx = 40f, dy = 0f, hasNext = true)
        assertNull(gestures.endEpisodeDrag(height = 500, hasNext = true, hasPrevious = true))
        assertTrue(gestures.endDrag(durationMs = hour, watchGuest = false) != null)

        // An ordinary drag is never an episode change.
        startDrag(x = 500f)
        drag(dx = 0f, dy = -100f, hasNext = true)
        assertNull(gestures.endEpisodeDrag(height = 500, hasNext = true, hasPrevious = true))
    }

    @Test
    fun brightnessNeverGoesFullyDarkAndVolumeStopsAtSilence() {
        startDrag(x = 200f)
        assertEquals(PictureLevel.Brightness(0.02f), drag(dx = 0f, dy = 1_000f))
        assertEquals("亮度 2%", gestures.hud)
        startDrag(x = 800f)
        assertEquals(PictureLevel.Volume(0f), drag(dx = 0f, dy = 1_000f))
        assertEquals("音量 0%", gestures.hud)
    }

    @Test
    fun aFingerDriftingDuringAHoldIsNotADrag() {
        gestures.startBoost(x = 500f)
        startDrag(x = 500f)
        assertNull(drag(dx = 0f, dy = -125f))
        assertEquals(DragAxis.Undecided, gestures.dragAxis)
        assertNull(gestures.hud)
        gestures.endBoost()
    }

    @Test
    fun aDragTakenAwayClearsItsPreviewAndTheHud() {
        startDrag(x = 500f)
        drag(dx = 40f, dy = 0f)
        gestures.cancelDrag()
        assertNull(gestures.pictureScrubMs)
        assertNull(gestures.previewMs)
        assertNull(gestures.hud)
    }

    @Test
    fun theRailCountsOneInteractionPerDragAndArmsFineScrubbing() {
        assertFalse(gestures.fineScrubArmed)
        assertTrue(gestures.scrub())
        assertTrue(gestures.scrubbing)
        assertTrue(gestures.fineScrubArmed)
        assertFalse(gestures.scrub())
        gestures.endScrub()
        assertFalse(gestures.scrubbing)
        assertTrue(gestures.fineScrubArmed)
        assertTrue(gestures.scrub())
    }

    @Test
    fun aSecondFingerLetsGoOfTheHeldMiddleAndASwipesPreview() {
        startDrag(x = 500f)
        drag(dx = 40f, dy = 0f)
        gestures.startBoost(x = 500f)
        assertTrue(gestures.secondFinger())
        assertFalse(gestures.boosting)
        assertNull(gestures.pictureScrubMs)
        assertFalse(gestures.secondFinger())
    }
}
