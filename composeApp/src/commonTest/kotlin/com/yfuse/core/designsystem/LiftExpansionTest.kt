package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiftExpansionTest {
    private val poster = MediaSharedElementKey(serverId = "server-a", itemId = "movie-1")

    private fun session(anchored: Boolean = false): LiftSession {
        val menu =
            LiftMenu(
                title = "深海回声",
                onOpen = {},
                anchored = anchored,
                sections = listOf(listOf(ItemAction("收藏") {})),
            )
        return LiftSession(
            menu = menu,
            source = Rect(80f, 60f, 190f, 225f),
            finger = Offset(100f, 100f),
            onOpenTitle = menu.onOpen,
            onSettled = {},
            onFinished = {},
        )
    }

    @Test
    fun aCardOpeningItsTitleCarriesThePageInsteadOfFading() {
        val lift = session()
        lift.open()
        assertEquals(LiftExit.FadeAway, lift.exit)

        lift.expandInto(poster, Offset(0f, -900f))

        assertEquals(LiftExit.Expand, lift.exit)
        val expansion = lift.expansion
        assertSame(poster, expansion?.key)
        assertEquals(Offset(0f, -900f), expansion?.velocity)
        assertFalse(expansion?.landed ?: true)
    }

    @Test
    fun onlyAnOpeningCardExpands() {
        // Not before 打开, not for a button's menu, not once another lift has replaced it.
        val waiting = session()
        waiting.expandInto(poster, Offset.Zero)
        assertEquals(LiftExit.None, waiting.exit)
        assertNull(waiting.expansion)

        val button = session(anchored = true)
        button.open()
        button.expandInto(poster, Offset.Zero)
        assertEquals(LiftExit.FadeAway, button.exit)

        val replaced = session()
        replaced.open()
        replaced.abandon()
        replaced.expandInto(poster, Offset.Zero)
        assertNull(replaced.expansion)
    }

    @Test
    fun aHeroNotYetLaidOutIsNowhereAndAPoppedPageIsGone() {
        val expansion = LiftExpansion(poster, Offset.Zero)
        assertNull(expansion.targetBounds())
        assertFalse(expansion.gone)
        expansion.detached = true
        assertTrue(expansion.gone)
    }

    @Test
    fun thePagesWordsWaitOnlyForACardStillOnItsWay() {
        val flying = LiftExpansion(poster, Offset.Zero)
        assertTrue(oneTakeHoldsWords(flying, current = flying))
        // The lift ended without handing over, or another took its place: nothing will land.
        assertFalse(oneTakeHoldsWords(flying, current = null))
        assertFalse(oneTakeHoldsWords(flying, current = LiftExpansion(poster, Offset.Zero)))

        val landed = LiftExpansion(poster, Offset.Zero).apply { landed = true }
        assertFalse(oneTakeHoldsWords(landed, current = landed))
        val slowPage = LiftExpansion(poster, Offset.Zero).apply { gaveUp = true }
        assertFalse(oneTakeHoldsWords(slowPage, current = slowPage))
        val popped = LiftExpansion(poster, Offset.Zero).apply { detached = true }
        assertFalse(oneTakeHoldsWords(popped, current = popped))
    }

    @Test
    fun cornersAndArtFollowTheFlightButNeverPastItsEnds() {
        assertEquals(48f, liftFlightBlend(48f, 0f, 0f), 0.001f)
        assertEquals(24f, liftFlightBlend(48f, 0f, 0.5f), 0.001f)
        assertEquals(0f, liftFlightBlend(48f, 0f, 1f), 0.001f)
        // A spring's overshoot does not round a corner past zero, nor the art past whole.
        assertEquals(0f, liftFlightBlend(48f, 0f, 1.02f), 0.001f)
        assertEquals(1f, liftFlightBlend(0.4f, 1f, 1.02f), 0.001f)
        assertEquals(0.4f, liftFlightBlend(0.4f, 1f, -0.1f), 0.001f)
    }

    @Test
    fun theCardsWordsAreGoneAQuarterOfTheWayIn() {
        assertEquals(1f, liftFlightWords(1f, 0f), 0.001f)
        assertEquals(0.5f, liftFlightWords(1f, LIFT_WORDS_GONE / 2f), 0.001f)
        assertEquals(0f, liftFlightWords(1f, LIFT_WORDS_GONE), 0.001f)
        assertEquals(0f, liftFlightWords(1f, 0.9f), 0.001f)
        assertEquals(0f, liftFlightWords(0f, 0.1f), 0.001f)
    }

    @Test
    fun theLandscapeArtGrowsWithTheCardOnlyOnceItOutgrowsItself() {
        val card = Rect(0f, 0f, 320f, 198f)
        assertEquals(1f, liftArtCover(Rect(0f, 0f, 110f, 165f), card), 0.001f)
        assertEquals(1f, liftArtCover(card, card), 0.001f)
        // A hero 400 wide and 460 tall: the art has to be 460 / 198 times the card to cover it.
        assertEquals(460f / 198f, liftArtCover(Rect(0f, 0f, 400f, 460f), card), 0.001f)
        assertEquals(1f, liftArtCover(Rect(0f, 0f, 400f, 460f), Rect.Zero), 0.001f)
    }

    @Test
    fun thePagesWordsRiseInOneBeatApart() {
        // Nothing before the hand-over, everything once the clock has run.
        for (row in 0 until ONE_TAKE_ARRIVAL_ROWS) {
            assertEquals(0f, oneTakeArrivalProgress(0f, row), 0.001f)
            assertEquals(1f, oneTakeArrivalProgress(1f, row), 0.001f)
        }
        val total = (ONE_TAKE_ARRIVAL_ROWS - 1) * Motion.SEARCH_ROW_STAGGER + Motion.STANDARD
        // One beat in, the first row is under way and the second has not started.
        val oneBeat = Motion.SEARCH_ROW_STAGGER.toFloat() / total
        assertTrue(oneTakeArrivalProgress(oneBeat, 0) > 0f)
        assertEquals(0f, oneTakeArrivalProgress(oneBeat, 1), 0.001f)
        // The first row is whole a STANDARD in; later rows are still behind it.
        val first = Motion.STANDARD.toFloat() / total
        assertEquals(1f, oneTakeArrivalProgress(first, 0), 0.001f)
        assertTrue(oneTakeArrivalProgress(first, 1) < 1f)
        assertTrue(oneTakeArrivalProgress(first, 2) < oneTakeArrivalProgress(first, 1))
    }
}
