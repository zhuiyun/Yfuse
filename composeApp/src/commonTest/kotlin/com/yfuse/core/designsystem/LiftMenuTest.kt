package com.yfuse.core.designsystem

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
                            LiftMenuAction("播放", leavesPage = true) { recorder.events += "play" },
                            LiftMenuAction("收藏", leavesPage = leaves) { recorder.events += "favorite" },
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
    fun emptyGroupsAreDroppedAndThePosterFillsInMissingArtwork() {
        val menu = LiftMenu(title = "雾港", sections = listOf(emptyList(), listOf(LiftMenuAction("播放") {})))
        assertEquals(1, menu.sections.size)
        assertEquals(1, menu.actions.size)
        val filled = menu.withArtwork(listOf("poster"))
        assertEquals(listOf("poster"), filled.artworkUrls)
        assertSame(filled, filled.withArtwork(listOf("other")))
        assertNull(menu.meta)
    }

    @Test
    fun theCardsWordsArriveOnlyOverTheLastStretchOfTheLift() {
        assertEquals(0f, liftTextAlpha(0f), 0.001f)
        assertEquals(0f, liftTextAlpha(0.55f), 0.001f)
        assertEquals(0.5f, liftTextAlpha(0.75f), 0.001f)
        assertEquals(1f, liftTextAlpha(1f), 0.001f)
        assertEquals(1f, liftTextAlpha(1.08f), 0.001f)
    }
}
