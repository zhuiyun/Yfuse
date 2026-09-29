package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import com.yfuse.core.designsystem.DragAxis
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    ): Long = gestures.burstSeek(direction, at, taps, stepMs = 10_000L, positionMs = positionMs, durationMs = hour)

    /** One move of a drag across a 1,000 × 500 px picture at density 1, the playhead at 1:00. */
    private fun drag(
        dx: Float,
        dy: Float,
        watchGuest: Boolean = false,
        swapBrightnessVolume: Boolean = false,
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
        )

    private fun startDrag(x: Float) = gestures.startDrag(x, positionMs = 60_000L, volume = 0.5f, brightness = 0.5f)

    /** A side held for one step of its scan, then the effect running it cancelled, as a release would. */
    private fun TestScope.holdForOneStep(positionMs: Long) {
        gestures.startScan(direction = 1, x = 500f, positionMs = positionMs)
        val scan =
            backgroundScope.launch {
                gestures.runScan(1, stepPx = 44f, durationMs = { hour }, onShift = {}, onSeek = {})
            }
        runCurrent()
        scan.cancel()
    }

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
    fun aHeldSideRunsAtTenTimesThenThirtyOnceItHasBeenHeldThreeSeconds() =
        runTest {
            val seeks = mutableListOf<Long>()
            var shifts = 0
            gestures.startScan(direction = 1, x = 500f, positionMs = 60_000L)
            assertTrue(gestures.scanning)
            backgroundScope.launch {
                gestures.runScan(
                    direction = 1,
                    stepPx = 44f,
                    durationMs = { hour },
                    onShift = { shifts++ },
                    onSeek = { seeks += it },
                )
            }
            runCurrent()
            // The first step goes at once: 300 ms at 10×.
            assertEquals(listOf(63_000L), seeks)
            assertEquals("快进 ×10 · 1:03 / 60:00", gestures.hud)
            assertEquals(63_000L, gestures.previewMs)

            advanceTimeBy(HOLD_SEEK_RAMP_MS - HOLD_SEEK_TICK_MS)
            runCurrent()
            assertEquals(90_000L, seeks.last())
            assertEquals(0, shifts)

            advanceTimeBy(HOLD_SEEK_TICK_MS)
            runCurrent()
            assertEquals(1, shifts)
            assertEquals(99_000L, seeks.last())
            assertEquals("快进 ×30 · 1:39 / 60:00", gestures.hud)
        }

    @Test
    fun aHeldSideStopsAtTheStartOfTheItem() =
        runTest {
            val seeks = mutableListOf<Long>()
            gestures.startScan(direction = -1, x = 100f, positionMs = 1_000L)
            backgroundScope.launch {
                gestures.runScan(-1, stepPx = 44f, durationMs = { hour }, onShift = {}, onSeek = { seeks += it })
            }
            runCurrent()
            assertEquals(listOf(0L), seeks)
            assertEquals("快退 ×10 · 0:00 / 60:00", gestures.hud)
        }

    @Test
    fun slidingBackStandsTheScanStillAndItIsNeverRampedAfterwards() =
        runTest {
            val seeks = mutableListOf<Long>()
            var shifts = 0
            gestures.startScan(direction = 1, x = 500f, positionMs = 60_000L)
            // A step back against the scan's way stands it still, said at once rather than on the next tick.
            assertTrue(gestures.followScan(x = 456f, stepPx = 44f, durationMs = hour))
            assertEquals("快进 停住 · 1:00 / 60:00", gestures.hud)
            assertFalse(gestures.followScan(x = 456f, stepPx = 44f, durationMs = hour))
            backgroundScope.launch {
                gestures.runScan(
                    direction = 1,
                    stepPx = 44f,
                    durationMs = { hour },
                    onShift = { shifts++ },
                    onSeek = { seeks += it },
                )
            }
            runCurrent()
            advanceTimeBy(2 * HOLD_SEEK_RAMP_MS)
            runCurrent()
            assertEquals(emptyList(), seeks)
            assertEquals(0, shifts)
            // It never moved, so letting go has nowhere to offer to go back to.
            assertTrue(gestures.releaseScan())
            assertNull(gestures.scanUndoMs)
        }

    @Test
    fun lettingGoOfAScanThatMovedOffersTheWayBackOnce() =
        runTest {
            holdForOneStep(positionMs = 60_000L)
            assertTrue(gestures.releaseScan())
            assertFalse(gestures.scanning)
            assertFalse(gestures.releaseScan())
            assertEquals(60_000L, gestures.scanUndoMs)

            assertEquals(60_000L, gestures.takeScanUndo())
            assertEquals("已回到 1:00", gestures.hud)
            assertNull(gestures.scanUndoMs)
            assertNull(gestures.takeScanUndo())
        }

    @Test
    fun theWayBackGoesWhenItRunsOutOrAnotherHoldTakes() =
        runTest {
            holdForOneStep(positionMs = 60_000L)
            gestures.releaseScan()
            gestures.expireScanUndo()
            assertNull(gestures.scanUndoMs)

            holdForOneStep(positionMs = 60_000L)
            gestures.releaseScan()
            assertEquals(60_000L, gestures.scanUndoMs)
            gestures.startScan(direction = -1, x = 100f, positionMs = 63_000L)
            assertNull(gestures.scanUndoMs)
            assertEquals(63_000L, gestures.previewMs)
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

        // A held side owns the timeline, so a swipe under way lands nowhere either.
        startDrag(x = 500f)
        drag(dx = 40f, dy = 0f)
        gestures.startScan(direction = 1, x = 540f, positionMs = 60_000L)
        assertNull(drag(dx = 40f, dy = 0f))
        assertNull(gestures.endDrag(durationMs = hour, watchGuest = false))
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
    fun aSecondFingerStopsAHeldSideWhereItGotToWithoutTheWayBack() =
        runTest {
            holdForOneStep(positionMs = 60_000L)
            assertTrue(gestures.scanning)
            assertFalse(gestures.secondFinger())
            assertFalse(gestures.scanning)
            assertNull(gestures.scanUndoMs)
            assertNull(gestures.hud)
            assertNull(gestures.previewMs)
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
