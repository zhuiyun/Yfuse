package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomBackGeometryTest {
    private val page = Size(1000f, 2000f)
    private val corner = 100f

    private fun assertNear(
        expected: Float,
        actual: Float,
        message: String = "",
    ) = assertTrue(abs(expected - actual) < 0.01f, "$message expected $expected but was $actual")

    private fun assertRect(
        expected: Rect,
        actual: Rect,
    ) {
        assertNear(expected.left, actual.left, "left")
        assertNear(expected.top, actual.top, "top")
        assertNear(expected.right, actual.right, "right")
        assertNear(expected.bottom, actual.bottom, "bottom")
    }

    @Test
    fun pullProgressIsTravelOverFortyFivePercentOfTheHeight() {
        assertNear(0f, zoomBackPullProgress(0f, 2000f))
        assertNear(1f, zoomBackPullProgress(900f, 2000f))
        assertNear(0.5f, zoomBackPullProgress(450f, 2000f))
        assertNear(0f, zoomBackPullProgress(-200f, 2000f), "above the start")
        assertNear(0f, zoomBackPullProgress(300f, 0f), "no page")
    }

    @Test
    fun pullAtRestIsTheWholePage() {
        val card =
            zoomBackPullCard(
                page,
                pivot = Offset(300f, 400f),
                drag = Offset.Zero,
                progress = 0f,
                cornerAtFullPx = corner,
            )
        assertRect(Rect(0f, 0f, 1000f, 2000f), card.bounds)
        assertNear(1f, card.contentScale)
        assertNear(0f, card.cornerRadius)
    }

    @Test
    fun fullPullShrinksToSeventyPercentAboutTheFingerAndKeepsItUnderTheFinger() {
        val pivot = Offset(300f, 400f)
        val drag = Offset(50f, 900f)
        val card = zoomBackPullCard(page, pivot, drag, progress = 1f, cornerAtFullPx = corner)
        assertNear(0.7f, card.contentScale)
        assertNear(corner, card.cornerRadius)
        assertNear(700f, card.bounds.width)
        assertNear(1400f, card.bounds.height)
        // The page point that was under the finger is where the finger is now.
        val underFinger =
            Offset(
                card.bounds.left + pivot.x * card.contentScale,
                card.bounds.top + pivot.y * card.contentScale,
            )
        assertNear(pivot.x + drag.x, underFinger.x, "x")
        assertNear(pivot.y + drag.y, underFinger.y, "y")
    }

    @Test
    fun pullProgressPastOneStopsShrinking() {
        val card =
            zoomBackPullCard(page, Offset(500f, 500f), Offset(0f, 1500f), progress = 1.7f, cornerAtFullPx = corner)
        assertNear(0.7f, card.contentScale)
        assertNear(corner, card.cornerRadius)
    }

    @Test
    fun pullNeverLiftsThePageAboveItsOwnTop() {
        val card = zoomBackPullCard(page, Offset(500f, 500f), Offset(0f, -300f), progress = 0f, cornerAtFullPx = corner)
        assertNear(0f, card.bounds.top)
    }

    @Test
    fun sideSwipeShrinksAboutTheCentreAndLeansTowardsTheFinger() {
        val rest =
            zoomBackSideCard(page, progress = 0f, toward = 1f, lift = 0f, shiftAtFullPx = 40f, cornerAtFullPx = corner)
        assertRect(Rect(0f, 0f, 1000f, 2000f), rest.bounds)

        val full =
            zoomBackSideCard(
                page,
                progress = 1f,
                toward = 1f,
                lift = 100f,
                shiftAtFullPx = 40f,
                cornerAtFullPx = corner,
            )
        assertNear(0.88f, full.contentScale)
        assertNear(880f, full.bounds.width)
        assertNear(60f + 40f, full.bounds.left, "centred, then 40px towards the swipe")
        assertNear(120f + 12f, full.bounds.top, "centred, then 12% of the finger's lift")
        assertNear(corner, full.cornerRadius)

        val fromRight =
            zoomBackSideCard(page, progress = 1f, toward = -1f, lift = 0f, shiftAtFullPx = 40f, cornerAtFullPx = corner)
        assertNear(60f - 40f, fromRight.bounds.left)
    }

    @Test
    fun underlayRecoversFromNinetyFivePercentAndHalfShade() {
        assertNear(0.95f, zoomBackUnderlayScale(0f))
        assertNear(1f, zoomBackUnderlayScale(1f))
        assertNear(0.975f, zoomBackUnderlayScale(0.5f))
        assertNear(0.5f, zoomBackUnderlayDim(0f))
        assertNear(0f, zoomBackUnderlayDim(1f))
        assertNear(0f, zoomBackUnderlayDim(3f))
    }

    @Test
    fun releaseDecisionProjectsTheFlick() {
        val extent = 900f
        // offset (px), velocity (px/s), commits?
        val table =
            listOf(
                Triple(0f, 0f, false),
                Triple(250f, 0f, false), // 28%, slow: springs home
                Triple(280f, 0f, true), // 31%, slow: goes back
                Triple(100f, 1200f, true), // a short flick: 100 + 204 = 304 px, 34%
                Triple(100f, 800f, false), // 100 + 136 = 236 px, 26%
                Triple(400f, -1000f, false), // pulled far, flung back up: 400 − 170 = 230 px
                Triple(400f, -500f, true), // 400 − 85 = 315 px
                Triple(-50f, 3000f, true), // a hard downward flick from just above the start
            )
        table.forEach { (offset, velocity, commits) ->
            assertEquals(commits, zoomBackCommits(offset, velocity, extent), "offset $offset velocity $velocity")
        }
    }

    @Test
    fun flightStartsAtTheReleasedCardAndEndsOnThePoster() {
        val from =
            zoomBackPullCard(page, Offset(500f, 300f), Offset(0f, 450f), progress = 0.5f, cornerAtFullPx = corner)
        val poster = Rect(100f, 1200f, 300f, 1500f)
        val start = zoomBackFlightCard(from, poster, page, fraction = 0f, toCornerPx = 24f, landing = true)
        assertRect(from.bounds, start.bounds)
        assertNear(from.contentScale, start.contentScale)
        assertNear(1f, start.contentAlpha)
        assertNear(0f, start.artAlpha)

        val end = zoomBackFlightCard(from, poster, page, fraction = 1f, toCornerPx = 24f, landing = true)
        assertRect(poster, end.bounds)
        assertNear(24f, end.cornerRadius)
        assertNear(0f, end.contentAlpha)
        assertNear(1f, end.artAlpha)
        // The page's content covers the poster rather than letterboxing inside it.
        assertNear(0.2f, end.contentScale)
        val local = end.localBounds(page)
        assertNear(500f, local.center.x)
        assertNear(1000f, local.center.y)
        assertNear(1000f, local.width)
        assertNear(1500f, local.height)
    }

    @Test
    fun contentIsGoneByFortyFivePercentOfTheFlight() {
        val from = ZoomCard.resting(page)
        val poster = Rect(0f, 0f, 200f, 300f)
        val early = zoomBackFlightCard(from, poster, page, fraction = 0.225f, toCornerPx = 0f, landing = true)
        assertNear(0.5f, early.contentAlpha)
        assertNear(0.5f, early.artAlpha)
        val done = zoomBackFlightCard(from, poster, page, fraction = 0.45f, toCornerPx = 0f, landing = true)
        assertNear(0f, done.contentAlpha)
        assertNear(1f, done.artAlpha)
    }

    @Test
    fun springingBackKeepsTheContentAndReachesTheWholePage() {
        val from =
            zoomBackPullCard(page, Offset(500f, 300f), Offset(80f, 200f), progress = 0.22f, cornerAtFullPx = corner)
        val home =
            zoomBackFlightCard(from, Rect(Offset.Zero, page), page, fraction = 1f, toCornerPx = 0f, landing = false)
        assertRect(Rect(0f, 0f, 1000f, 2000f), home.bounds)
        assertNear(1f, home.contentScale)
        assertNear(0f, home.cornerRadius)
        assertNear(1f, home.contentAlpha)
        assertNear(0f, home.artAlpha)
    }

    @Test
    fun ordinaryBackFadesAndShrinksAboutTheCardsCentre() {
        val from =
            zoomBackPullCard(page, Offset(500f, 300f), Offset(0f, 450f), progress = 0.5f, cornerAtFullPx = corner)
        val end = zoomBackFadeCard(from, fraction = 1f, shrink = true)
        assertNear(0f, end.contentAlpha)
        assertNear(from.bounds.center.x, end.bounds.center.x)
        assertNear(from.bounds.center.y, end.bounds.center.y)
        assertNear(from.bounds.width * 0.9f, end.bounds.width)
        val still = zoomBackFadeCard(from, fraction = 1f, shrink = false)
        assertRect(from.bounds, still.bounds)
        assertNear(0f, still.contentAlpha)
    }

    @Test
    fun flightVelocityIsReleaseSpeedOverTheDistance() {
        val from = Rect(0f, 0f, 100f, 100f)
        val to = Rect(0f, 1000f, 100f, 1100f)
        assertNear(2f, zoomBackFlightVelocity(Offset(0f, 2000f), from, to))
        assertNear(-1f, zoomBackFlightVelocity(Offset(0f, -1000f), from, to), "flung away from the poster")
        assertNear(0f, zoomBackFlightVelocity(Offset(3000f, 0f), from, to), "sideways carries nothing")
        assertNear(0f, zoomBackFlightVelocity(Offset(0f, 2000f), from, from), "nowhere to go")
        assertNear(ZOOM_BACK_MAX_FLIGHT_VELOCITY, zoomBackFlightVelocity(Offset(0f, 1e6f), from, to))
    }

    @Test
    fun posterHalfOffScreenStillCountsButLessDoesNot() {
        val screen = Rect(0f, 0f, 1000f, 2000f)
        val poster = Rect(100f, 1800f, 300f, 2100f)
        assertTrue(zoomBackTargetVisible(poster, poster, screen), "two thirds on screen")
        val lower = Rect(100f, 1900f, 300f, 2200f)
        assertFalse(zoomBackTargetVisible(lower, lower, screen), "a third on screen")
        val clipped = Rect(100f, 100f, 300f, 400f)
        assertFalse(zoomBackTargetVisible(clipped, Rect(100f, 350f, 300f, 400f), screen), "scrolled under a header")
        assertFalse(zoomBackTargetVisible(Rect.Zero, Rect.Zero, screen), "never laid out")
    }
}
