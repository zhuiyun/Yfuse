package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiftMenuTest {
    // A 400×800 phone less margins, in pixels at density 1.
    private val phone = Rect(16f, 40f, 384f, 760f)
    private val card = Size(320f, 198f)
    private val row = 48f

    private fun place(
        source: Rect,
        bounds: Rect = phone,
        menuHeight: Float = 300f,
        menuWidth: Float = card.width,
    ): LiftPlacement =
        placeLift(
            source = source,
            bounds = bounds,
            card = card,
            menuWidth = menuWidth,
            menuHeight = menuHeight,
            gap = 10f,
            rowHeight = row,
        )

    @Test
    fun theCardCentresOnThePosterAndTheMenuHangsBelowIt() {
        val placement = place(Rect(140f, 300f, 250f, 465f), menuHeight = 150f)
        assertEquals(195f, placement.card.center.x, 0.01f)
        assertEquals(382.5f, placement.card.center.y, 0.01f)
        assertEquals(placement.card.bottom + 10f, placement.menu.top, 0.01f)
        assertEquals(150f, placement.menu.height, 0.01f)
        assertFalse(placement.menuScrolls)
    }

    @Test
    fun aPosterNearTheFootPushesTheColumnUpSoTheMenuEndsUnderTheFinger() {
        val placement = place(Rect(20f, 600f, 130f, 765f))
        assertEquals(phone.bottom, placement.menu.bottom, 0.01f)
        assertEquals(phone.left, placement.card.left, 0.01f)
        assertTrue(placement.card.top >= phone.top)
    }

    @Test
    fun aPosterNearTheTopKeepsTheCardInsideTheWindow() {
        val placement = place(Rect(260f, 0f, 370f, 120f))
        assertEquals(phone.top, placement.card.top, 0.01f)
        assertEquals(phone.right, placement.card.right, 0.01f)
    }

    @Test
    fun aShortWideWindowStandsCardAndMenuSideBySide() {
        val landscape = Rect(16f, 16f, 784f, 344f)
        val placement = place(Rect(300f, 100f, 410f, 265f), bounds = landscape, menuHeight = 420f)
        assertEquals(placement.card.right + 10f, placement.menu.left, 0.01f)
        assertEquals(landscape.height, placement.menu.height, 0.01f)
        assertTrue(placement.menuScrolls)
        assertTrue(placement.card.top >= landscape.top && placement.card.bottom <= landscape.bottom)
    }

    @Test
    fun aMenuTooTallForAnyArrangementScrollsBeforeTheCardShrinks() {
        val small = Rect(0f, 0f, 320f, 500f)
        val placement = place(Rect(100f, 100f, 200f, 250f), bounds = small, menuHeight = 900f)
        assertTrue(placement.menuScrolls)
        assertEquals(card.height, placement.card.height, 0.01f)
        assertEquals(small.bottom, placement.menu.bottom, 0.01f)
    }

    @Test
    fun contentHeightCountsRowsSeparatorsAndPaddingAndSkipsEmptyGroups() {
        assertEquals(2 * 6f + 5 * 48f + 1 * 9f, liftMenuContentHeight(listOf(2, 0, 3), 48f, 9f, 6f), 0.01f)
        assertEquals(0f, liftMenuContentHeight(listOf(0), 48f, 9f, 6f), 0.01f)
    }

    @Test
    fun theFingerHitsRowsInTheOrderTheyAreDrawnAndNothingOnAHairline() {
        val placement = LiftPlacement(Rect(0f, 0f, 320f, 198f), Rect(0f, 208f, 320f, 400f), menuScrolls = false)
        val sizes = listOf(2, 3)

        fun hit(y: Float) = liftHitAt(Offset(100f, y), placement, sizes, 48f, 9f, 6f)

        assertEquals(LiftHit.Row(0), hit(208f + 6f + 1f))
        assertEquals(LiftHit.Row(1), hit(208f + 6f + 48f + 1f))
        assertEquals(LiftHit.None, hit(208f + 6f + 96f + 4f))
        assertEquals(LiftHit.Row(2), hit(208f + 6f + 96f + 9f + 1f))
        assertEquals(LiftHit.Card, liftHitAt(Offset(10f, 10f), placement, sizes, 48f, 9f, 6f))
        assertEquals(LiftHit.None, liftHitAt(Offset(10f, 205f), placement, sizes, 48f, 9f, 6f))
    }

    private class Recorder {
        val events = mutableListOf<String>()
    }

    private fun session(
        recorder: Recorder,
        finger: Offset? = Offset(100f, 100f),
        leaves: Boolean = false,
    ): LiftSession {
        val menu =
            LiftMenu(
                title = "深海回声",
                onOpen = { recorder.events += "open" },
                sections =
                    listOf(
                        listOf(
                            ItemAction("播放", leavesPage = true) { recorder.events += "play" },
                            ItemAction("收藏", leavesPage = leaves) { recorder.events += "favorite" },
                        ),
                        emptyList(),
                    ),
            )
        return LiftSession(
            menu = menu,
            source = Rect(80f, 60f, 190f, 225f),
            finger = finger,
            onOpenTitle = menu.onOpen,
            onSettled = { recorder.events += "settled" },
            onFinished = { recorder.events += "finished" },
        ).also {
            it.placement = LiftPlacement(Rect(0f, 0f, 320f, 198f), Rect(0f, 208f, 320f, 320f), menuScrolls = false)
            it.rowHeight = 48f
            it.separatorHeight = 9f
            it.padding = 6f
        }
    }

    @Test
    fun aStillFingerHitsNothingSoLettingGoLeavesTheMenuUp() {
        val recorder = Recorder()
        val lift = session(recorder)
        assertFalse(lift.steer(Offset(103f, 102f), slop = 8f))
        lift.release()
        assertFalse(lift.holding)
        assertEquals(LiftExit.None, lift.exit)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun slidingOntoARowTicksOnceAndReleasingRunsItAfterTheCardSettles() {
        val recorder = Recorder()
        val lift = session(recorder)
        assertTrue(lift.steer(Offset(100f, 208f + 6f + 48f + 10f), slop = 8f))
        assertFalse(lift.steer(Offset(120f, 208f + 6f + 48f + 20f), slop = 8f))
        assertEquals(LiftHit.Row(1), lift.hot)
        lift.release()
        assertEquals(LiftExit.SettleBack, lift.exit)
        assertTrue(recorder.events.isEmpty())
        lift.finish()
        assertEquals(listOf("settled", "finished", "favorite"), recorder.events)
    }

    @Test
    fun aRowUnderAFingerThatHasNotMovedARowAwayIsNotPicked() {
        val recorder = Recorder()
        // A poster at the foot of the screen: the menu lands under the finger, on the first row.
        val lift = session(recorder, finger = Offset(100f, 230f))
        // Past the touch slop, still on that row, but not a row's height from where it began.
        assertFalse(lift.steer(Offset(100f, 242f), slop = 8f))
        assertEquals(LiftHit.None, lift.hot)
        lift.release()
        assertTrue(recorder.events.isEmpty())
        assertEquals(LiftExit.None, lift.exit)
    }

    @Test
    fun onceARowAwayTheFingerPicksRowsEvenBackNearWhereItBegan() {
        val recorder = Recorder()
        val lift = session(recorder, finger = Offset(100f, 230f))
        assertTrue(lift.steer(Offset(100f, 280f), slop = 8f))
        assertEquals(LiftHit.Row(1), lift.hot)
        assertTrue(lift.steer(Offset(100f, 240f), slop = 8f))
        assertEquals(LiftHit.Row(0), lift.hot)
        lift.release()
        assertEquals(listOf("play"), recorder.events)
    }

    @Test
    fun aDestructiveRowTakesATapRatherThanALetGo() {
        val recorder = Recorder()
        val menu =
            LiftMenu(
                title = "深海回声",
                sections =
                    listOf(
                        listOf(
                            ItemAction("播放", leavesPage = true) { recorder.events += "play" },
                            ItemAction("从继续观看移除", destructive = true) { recorder.events += "remove" },
                        ),
                    ),
            )
        val lift =
            LiftSession(menu, Rect(80f, 60f, 190f, 225f), Offset(100f, 100f), null, {}, {}).also {
                it.placement = LiftPlacement(Rect(0f, 0f, 320f, 198f), Rect(0f, 208f, 320f, 320f), menuScrolls = false)
                it.rowHeight = 48f
                it.separatorHeight = 9f
                it.padding = 6f
            }
        // Well past a row's travel, on the destructive row: nothing lights and letting go runs nothing.
        assertFalse(lift.steer(Offset(100f, 280f), slop = 8f))
        assertEquals(LiftHit.None, lift.hot)
        lift.release()
        assertTrue(recorder.events.isEmpty())
        // The menu stays up, and a tap on the row does it.
        lift.select(menu.actions[1])
        assertEquals(LiftExit.SettleBack, lift.exit)
        lift.finish()
        assertEquals(listOf("remove"), recorder.events)
    }

    @Test
    fun aRowThatLeavesThePageRunsAtOnceAndTheLiftFadesInsteadOfSettling() {
        val recorder = Recorder()
        val lift = session(recorder)
        lift.steer(Offset(100f, 208f + 6f + 10f), slop = 8f)
        lift.release()
        assertEquals(listOf("play"), recorder.events)
        assertEquals(LiftExit.FadeAway, lift.exit)
    }

    @Test
    fun releasingOnTheCardOpensTheTitle() {
        val recorder = Recorder()
        val lift = session(recorder)
        assertTrue(lift.steer(Offset(60f, 40f), slop = 8f))
        assertEquals(LiftHit.Card, lift.hot)
        lift.release()
        assertEquals(listOf("open"), recorder.events)
        assertEquals(LiftExit.FadeAway, lift.exit)
    }

    @Test
    fun aPosterThatLeftThePageFadesRatherThanSettlingIntoNothing() {
        val recorder = Recorder()
        val lift = session(recorder)
        lift.sourceDetached()
        lift.dismiss()
        assertEquals(LiftExit.FadeAway, lift.exit)
    }

    @Test
    fun aTakenStreamStopsSteeringWithoutRunningWhatWasUnderTheFinger() {
        val recorder = Recorder()
        val lift = session(recorder)
        lift.steer(Offset(100f, 208f + 6f + 10f), slop = 8f)
        lift.stopHolding()
        assertEquals(LiftHit.None, lift.hot)
        lift.release()
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun openedWithoutAFingerTheMenuWaitsToBeTapped() {
        val recorder = Recorder()
        val lift = session(recorder, finger = null)
        assertFalse(lift.holding)
        assertFalse(lift.steer(Offset(100f, 230f), slop = 8f))
        lift.select(lift.menu.actions[1])
        assertEquals(LiftExit.SettleBack, lift.exit)
        lift.dismiss()
        assertEquals(LiftExit.SettleBack, lift.exit)
    }

    @Test
    fun aLiftReplacedByAnotherRunsNothingWhenItsFingerComesUp() {
        val recorder = Recorder()
        val lift = session(recorder)
        lift.steer(Offset(100f, 208f + 6f + 10f), slop = 8f)
        lift.abandon()
        assertEquals(listOf("settled", "finished"), recorder.events)
        lift.release()
        lift.select(lift.menu.actions[0])
        lift.open()
        assertEquals(listOf("settled", "finished"), recorder.events)
        assertEquals(LiftExit.None, lift.exit)
    }

    @Test
    fun emptyGroupsAreDroppedAndThePosterFillsInMissingArtwork() {
        val menu = LiftMenu(title = "雾港", sections = listOf(emptyList(), listOf(ItemAction("播放") {})))
        assertEquals(1, menu.sections.size)
        assertEquals(1, menu.actions.size)
        val filled = menu.withArtwork(listOf("poster"))
        assertEquals(listOf("poster"), filled.artworkUrls)
        assertSame(filled, filled.withArtwork(listOf("other")))
        assertNull(menu.meta)
    }

    @Test
    fun anAnchoredMenuHangsUnderItsButtonAndOpensAwayFromTheNearEdge() {
        val window = Rect(16f, 40f, 376f, 800f)
        // 更多 at the top right: under the button, right edges lined up.
        val more = Rect(320f, 50f, 358f, 88f)
        val placed = placeAnchoredMenu(more, window, menuWidth = 248f, menuHeight = 300f, gap = 10f)
        assertEquals(Rect(110f, 98f, 358f, 398f), placed.menu)
        assertSame(more, placed.card)
        assertFalse(placed.menuScrolls)
        // A button on the left opens rightwards.
        val left = placeAnchoredMenu(Rect(20f, 50f, 58f, 88f), window, 248f, 300f, 10f)
        assertEquals(20f, left.menu.left, 0.01f)
    }

    @Test
    fun anAnchoredMenuWithNoRoomBelowGoesAboveAndScrollsOnlyWhenNeitherSideFits() {
        val window = Rect(0f, 0f, 400f, 800f)
        val low = Rect(300f, 700f, 340f, 740f)
        val above = placeAnchoredMenu(low, window, menuWidth = 248f, menuHeight = 300f, gap = 10f)
        assertEquals(390f, above.menu.top, 0.01f)
        assertEquals(690f, above.menu.bottom, 0.01f)
        assertFalse(above.menuScrolls)
        val middle = Rect(300f, 380f, 340f, 420f)
        val squeezed = placeAnchoredMenu(middle, window, menuWidth = 248f, menuHeight = 600f, gap = 10f)
        assertTrue(squeezed.menuScrolls)
        assertEquals(430f, squeezed.menu.top, 0.01f)
        assertEquals(800f, squeezed.menu.bottom, 0.01f)
    }

    @Test
    fun lettingGoBackOnAnAnchoredButtonDoesWhatTappingItDoes() {
        val recorder = Recorder()
        val lift = session(recorder, finger = Offset(339f, 69f))
        lift.placement = placeAnchoredMenu(Rect(320f, 50f, 358f, 88f), Rect(0f, 0f, 400f, 800f), 248f, 200f, 10f)
        lift.steer(Offset(330f, 80f), slop = 8f)
        lift.release()
        assertEquals(listOf("open"), recorder.events)
    }

    @Test
    fun theCardsWordsArriveOnlyOverTheLastStretchOfTheLift() {
        assertEquals(0f, liftTextAlpha(0f), 0.001f)
        assertEquals(0f, liftTextAlpha(0.55f), 0.001f)
        assertEquals(0.5f, liftTextAlpha(0.75f), 0.001f)
        assertEquals(1f, liftTextAlpha(1f), 0.001f)
        assertEquals(1f, liftTextAlpha(1.08f), 0.001f)
    }

    // ------------------------------------------------------------------ 按住拖看

    private class FakeScrub(
        var frames: Int,
    ) : LiftScrub {
        var prepared = 0

        override val frameCount: Int get() = frames

        override fun prepare() {
            prepared++
        }

        override fun label(index: Int): String = "#$index"

        @Composable
        override fun Frame(
            index: Int,
            modifier: Modifier,
        ) = Unit
    }

    private fun scrubMenu(
        scrub: FakeScrub,
        recorder: Recorder,
    ): LiftMenu =
        LiftMenu(
            title = "第3集",
            onOpen = { recorder.events += "open" },
            sections = listOf(listOf(ItemAction("播放", leavesPage = true) { recorder.events += "play" })),
            scrub = scrub,
        )

    // The card is 320 × 198 from the origin, the menu under it; the lift began at the card's centre.
    private fun scrubSession(
        scrub: FakeScrub,
        recorder: Recorder = Recorder(),
    ): LiftSession {
        val menu = scrubMenu(scrub, recorder)
        return LiftSession(
            menu = menu,
            source = Rect(80f, 60f, 240f, 150f),
            finger = Offset(160f, 100f),
            onOpenTitle = menu.onOpen,
            onSettled = {},
            onFinished = {},
        ).also {
            it.placement = LiftPlacement(Rect(0f, 0f, 320f, 198f), Rect(0f, 208f, 320f, 320f), menuScrolls = false)
            it.rowHeight = 48f
            it.separatorHeight = 9f
            it.padding = 6f
            it.scrubStep = 8f
        }
    }

    @Test
    fun theCardEdgeToEdgeIsTheTitleStartToEnd() {
        val card = Rect(20f, 0f, 340f, 198f)
        assertEquals(0f, liftScrubFraction(20f, card), 0.001f)
        assertEquals(0.5f, liftScrubFraction(180f, card), 0.001f)
        assertEquals(1f, liftScrubFraction(340f, card), 0.001f)
        assertEquals(0f, liftScrubFraction(-40f, card), 0.001f)
        assertEquals(1f, liftScrubFraction(400f, card), 0.001f)
        assertEquals(0f, liftScrubFraction(10f, Rect(10f, 0f, 10f, 10f)), 0.001f)

        assertEquals(0, liftScrubIndex(0f, 40))
        assertEquals(20, liftScrubIndex(0.5f, 40))
        assertEquals(39, liftScrubIndex(1f, 40))
        assertEquals(0, liftScrubIndex(-0.2f, 40))
        assertEquals(39, liftScrubIndex(1.5f, 40))
        assertEquals(-1, liftScrubIndex(0.5f, 0))

        assertEquals(0, liftScrubFrame(0, 40, 270))
        assertEquals(269, liftScrubFrame(39, 40, 270))
        assertEquals(269, liftScrubFrame(52, 40, 270))
        assertEquals(5, liftScrubFrame(5, 12, 12))
        assertEquals(0, liftScrubFrame(0, 1, 270))
        assertEquals(-1, liftScrubFrame(3, 0, 10))
        assertEquals(-1, liftScrubFrame(3, 10, 0))
    }

    @Test
    fun framesAreSpacedSoEachChangeIsADetentNotABuzz() {
        assertEquals(40, liftScrubStops(frames = 270, width = 320f, step = 8f))
        assertEquals(12, liftScrubStops(frames = 12, width = 320f, step = 8f))
        assertEquals(1, liftScrubStops(frames = 270, width = 4f, step = 8f))
        assertEquals(270, liftScrubStops(frames = 270, width = 320f, step = 0f))
        assertEquals(0, liftScrubStops(frames = 0, width = 320f, step = 8f))
        assertEquals(0, liftScrubStops(frames = 270, width = 0f, step = 8f))
    }

    @Test
    fun onlyAFingerGoneSidewaysPastTheDeadZoneScrubs() {
        val anchor = Offset(100f, 100f)
        assertEquals(DragAxis.Undecided, liftScrubAxis(anchor, Offset(105f, 101f), deadZone = 8f))
        assertEquals(DragAxis.Horizontal, liftScrubAxis(anchor, Offset(110f, 101f), deadZone = 8f))
        assertEquals(DragAxis.Horizontal, liftScrubAxis(anchor, Offset(88f, 97f), deadZone = 8f))
        assertEquals(DragAxis.Vertical, liftScrubAxis(anchor, Offset(100f, 130f), deadZone = 8f))
        assertEquals(DragAxis.Vertical, liftScrubAxis(anchor, Offset(110f, 110f), deadZone = 8f))
    }

    @Test
    fun aFingerLiftedOverTheRowsScrubsOnceItHasClimbedOntoTheCard() {
        val scrub = FakeScrub(270)
        val menu = scrubMenu(scrub, Recorder())
        // A poster low on the screen: the column slid up, and the finger starts on the menu.
        val lift =
            LiftSession(menu, Rect(80f, 240f, 240f, 330f), Offset(160f, 280f), menu.onOpen, {}, {}).also {
                it.placement = LiftPlacement(Rect(0f, 0f, 320f, 198f), Rect(0f, 208f, 320f, 320f), menuScrolls = false)
                it.rowHeight = 48f
                it.separatorHeight = 9f
                it.padding = 6f
                it.scrubStep = 8f
            }
        assertTrue(lift.steer(Offset(160f, 150f), slop = 8f))
        assertEquals(LiftHit.Card, lift.hot)
        assertEquals(-1, lift.scrubFrame)
        assertTrue(lift.steer(Offset(172f, 148f), slop = 8f))
        assertEquals(144, lift.scrubFrame)
    }

    @Test
    fun aFingerThatWentDownAndThenAcrossScrubsFromWhereItTurned() {
        val lift = scrubSession(FakeScrub(270))
        lift.steer(Offset(160f, 140f), slop = 8f)
        lift.steer(Offset(160f, 170f), slop = 8f)
        assertEquals(-1, lift.scrubFrame)
        assertTrue(lift.steer(Offset(172f, 172f), slop = 8f))
        assertEquals(144, lift.scrubFrame)
    }

    @Test
    fun aStillFingerOrOneGoingDownKeepsTheArtwork() {
        val lift = scrubSession(FakeScrub(270))
        assertFalse(lift.steer(Offset(104f + 60f, 102f), slop = 8f))
        assertEquals(-1, lift.scrubFrame)
        assertTrue(lift.steer(Offset(162f, 120f), slop = 8f))
        assertEquals(LiftHit.Card, lift.hot)
        assertEquals(-1, lift.scrubFrame)
    }

    @Test
    fun slidingAcrossTheCardStepsThroughTheFramesTickingOncePerFrame() {
        val lift = scrubSession(FakeScrub(270))
        // 172 / 320 of the way: stop 21 of 40, spread over 270 frames.
        assertTrue(lift.steer(Offset(172f, 100f), slop = 8f))
        assertEquals(LiftHit.Card, lift.hot)
        assertEquals(144, lift.scrubFrame)
        assertEquals(172f / 320f, lift.scrubFraction, 0.001f)
        // Within the same stop: the line follows, the frame and the tick do not.
        assertFalse(lift.steer(Offset(174f, 101f), slop = 8f))
        assertEquals(144, lift.scrubFrame)
        assertEquals(174f / 320f, lift.scrubFraction, 0.001f)
        assertTrue(lift.steer(Offset(180f, 101f), slop = 8f))
        assertEquals(151, lift.scrubFrame)
        assertTrue(lift.steer(Offset(0f, 100f), slop = 8f))
        assertEquals(0, lift.scrubFrame)
        assertTrue(lift.steer(Offset(319.9f, 100f), slop = 8f))
        assertEquals(269, lift.scrubFrame)
    }

    @Test
    fun leavingTheCardForTheRowsBringsTheArtworkBackAndTheRowsWorkAsBefore() {
        val recorder = Recorder()
        val lift = scrubSession(FakeScrub(270), recorder)
        lift.steer(Offset(200f, 100f), slop = 8f)
        assertTrue(lift.scrubFrame >= 0)
        assertTrue(lift.steer(Offset(200f, 208f + 6f + 10f), slop = 8f))
        assertEquals(LiftHit.Row(0), lift.hot)
        assertEquals(-1, lift.scrubFrame)
        // Back on the card the scrub picks up where the finger is, without a second dead zone.
        assertTrue(lift.steer(Offset(40f, 150f), slop = 8f))
        assertEquals(liftScrubFrame(5, 40, 270), lift.scrubFrame)
        lift.steer(Offset(200f, 208f + 6f + 10f), slop = 8f)
        lift.release()
        assertEquals(listOf("play"), recorder.events)
    }

    @Test
    fun lettingGoOnTheCardAfterScrubbingStillOpensTheTitle() {
        val recorder = Recorder()
        val lift = scrubSession(FakeScrub(270), recorder)
        lift.steer(Offset(250f, 100f), slop = 8f)
        lift.release()
        assertEquals(listOf("open"), recorder.events)
        assertEquals(LiftExit.FadeAway, lift.exit)
    }

    @Test
    fun framesStillOnTheirWayLeaveTheCardAsItIsUntilTheyArrive() {
        val scrub = FakeScrub(0)
        val lift = scrubSession(scrub)
        assertTrue(lift.steer(Offset(200f, 100f), slop = 8f))
        assertEquals(-1, lift.scrubFrame)
        scrub.frames = 12
        assertTrue(lift.steer(Offset(210f, 100f), slop = 8f))
        assertEquals(7, lift.scrubFrame)
    }

    @Test
    fun aTakenStreamMidScrubShowsTheArtworkAgain() {
        val lift = scrubSession(FakeScrub(270))
        lift.steer(Offset(200f, 100f), slop = 8f)
        lift.stopHolding()
        assertEquals(-1, lift.scrubFrame)
        assertFalse(lift.steer(Offset(260f, 100f), slop = 8f))
        assertEquals(-1, lift.scrubFrame)
    }

    @Test
    fun aMenuWithoutFramesNeverScrubs() {
        val lift = session(Recorder(), finger = Offset(100f, 100f))
        assertTrue(lift.steer(Offset(140f, 100f), slop = 8f))
        assertFalse(lift.steer(Offset(200f, 100f), slop = 8f))
        assertEquals(-1, lift.scrubFrame)
    }

    @Test
    fun onlyACardHeldByAFingerFetchesItsFrames() {
        val scrub = FakeScrub(0)
        val menu = scrubMenu(scrub, Recorder())
        val host = LiftMenuState()
        host.lift(menu, Rect(0f, 0f, 100f, 60f), finger = null, onOpen = null, onSettled = {})
        assertEquals(0, scrub.prepared)
        host.lift(menu, Rect(0f, 0f, 100f, 60f), finger = Offset(50f, 30f), onOpen = null, onSettled = {})
        assertEquals(1, scrub.prepared)
        assertSame(scrub, menu.withArtwork(listOf("poster")).scrub)
    }
}
