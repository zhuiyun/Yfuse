package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomBackCatchTest {
    private val page = Size(1000f, 2000f)
    private val poster = Rect(100f, 1500f, 300f, 1800f)

    private fun assertNear(
        expected: Float,
        actual: Float,
        message: String = "",
    ) = assertTrue(abs(expected - actual) < 0.01f, "$message expected $expected but was $actual")

    /** A controller whose flights never run: the test scope is never advanced. */
    private fun controller(): ZoomBackController =
        ZoomBackController(TestScope()).also {
            it.page = page
            it.resolveTarget = { ZoomBackTarget(poster, null) }
        }

    private fun pulledAndLetGo(controller: ZoomBackController) {
        assertTrue(controller.startPull(Offset(500f, 300f)))
        controller.movePull(Offset(500f, 1300f))
        controller.releasePull(Offset(0f, 2000f))
    }

    @Test
    fun aFingerOnTheCardInFlightCatchesItWhereItIs() {
        val controller = controller()
        pulledAndLetGo(controller)
        assertEquals(ZoomBackPhase.Flying, controller.phase)
        val inFlight = controller.card()

        assertTrue(controller.catchAt(inFlight.bounds.center))

        assertEquals(ZoomBackPhase.Following, controller.phase)
        assertEquals(inFlight, controller.card())
        // It follows the finger as it was caught: moved, not rescaled.
        controller.movePull(inFlight.bounds.center + Offset(20f, -300f))
        assertEquals(inFlight.bounds.translate(Offset(20f, -300f)), controller.card().bounds)
        assertNear(inFlight.contentScale, controller.card().contentScale)
    }

    @Test
    fun aFingerBesideTheCardOrOnACardAtRestDoesNotCatch() {
        val controller = controller()
        assertFalse(controller.catchAt(Offset(500f, 500f)), "nothing in flight")
        pulledAndLetGo(controller)
        assertFalse(controller.catchAt(Offset(990f, 10f)), "outside the card")
        assertEquals(ZoomBackPhase.Flying, controller.phase)
    }

    @Test
    fun underReducedMotionNothingIsCaught() {
        val controller = controller()
        controller.still = true
        pulledAndLetGo(controller)
        assertFalse(controller.catchAt(controller.card().bounds.center))
    }

    @Test
    fun aCaughtCardFlungBackUpGoesHome() {
        val controller = controller()
        pulledAndLetGo(controller)
        val caughtAt = controller.card().bounds.center
        assertTrue(controller.catchAt(caughtAt))
        controller.movePull(caughtAt + Offset(0f, -800f))
        controller.releasePull(Offset(0f, -3000f))
        assertEquals(ZoomBackPhase.Returning, controller.phase)
    }

    @Test
    fun aCaughtCardCarriesOnFromHowFarThePageUnderneathHadCome() {
        assertNear(0.6f, zoomBackCaughtProgress(under = 0.6f, dy = 0f, extent = 900f))
        assertNear(0.7f, zoomBackCaughtProgress(under = 0.6f, dy = 90f, extent = 900f))
        assertNear(0f, zoomBackCaughtProgress(under = 0.2f, dy = -900f, extent = 900f))
        assertNear(0.4f, zoomBackCaughtProgress(under = 0.4f, dy = 300f, extent = 0f))
    }

    @Test
    fun aReleaseAfterACatchKeepsWhatTheCardAlreadyShowed() {
        val bounds = Rect(0f, 0f, 400f, 800f)
        val caught = ZoomCard(bounds, 0.4f, 20f, contentAlpha = 0.3f, artAlpha = 0.7f)
        val onward = ZoomCard(bounds, 0.3f, 16f, contentAlpha = 0.2f, artAlpha = 0.1f)
        // Flying on into the poster: the art does not drop back to where a fresh flight starts it.
        assertNear(0.7f, onward.continuingFrom(caught, 0.05f, landing = true).artAlpha)
        assertNear(0.9f, onward.copy(artAlpha = 0.9f).continuingFrom(caught, 0.4f, landing = true).artAlpha)
        // Flying home: the page comes back whole and the art goes, by the time the content fade ends.
        val home = onward.continuingFrom(caught, ZOOM_BACK_CONTENT_FADE, landing = false)
        assertNear(1f, home.contentAlpha)
        assertNear(0f, home.artAlpha)
        val halfway = onward.continuingFrom(caught, ZOOM_BACK_CONTENT_FADE / 2f, landing = false)
        assertNear(0.65f, halfway.contentAlpha)
        assertNear(0.35f, halfway.artAlpha)
    }

    @Test
    fun anUncaughtCardIsLeftExactlyAsItWas() {
        val bounds = Rect(0f, 0f, 400f, 800f)
        val whole = ZoomCard(bounds, 1f, 0f)
        val flight = ZoomCard(bounds, 0.5f, 10f, contentAlpha = 0.4f, artAlpha = 0.6f)
        assertEquals(flight, flight.continuingFrom(whole, 0.3f, landing = true))
        val back = ZoomCard(bounds, 0.8f, 5f, contentAlpha = 1f, artAlpha = 0f)
        assertEquals(back, back.continuingFrom(whole, 0.3f, landing = false))
    }

    @Test
    fun aShrinkingCardStartsItsFlightAtTheSpeedItWasShrinking() {
        val from = Rect(0f, 0f, 1000f, 2000f)
        val to = Rect(400f, 800f, 600f, 1200f)
        // Every edge moving towards the target at the rate that covers the way in one second.
        val oneFlight =
            zoomBackEdgeFlightVelocity(
                topLeft = Offset(400f, 800f),
                bottomRight = Offset(-400f, -800f),
                from = from,
                to = to,
            )
        assertNear(1f, oneFlight)
        // The centre stands still, so the centre-only reading says it is not moving at all.
        assertNear(0f, zoomBackFlightVelocity(Offset.Zero, from, to))
        // Growing back instead of shrinking is flying the other way.
        assertTrue(zoomBackEdgeFlightVelocity(Offset(-400f, -800f), Offset(400f, 800f), from, to) < 0f)
        // Clamped, and nothing for nowhere to go or a broken reading.
        assertNear(
            ZOOM_BACK_MAX_FLIGHT_VELOCITY,
            zoomBackEdgeFlightVelocity(Offset(1e6f, 1e6f), Offset(-1e6f, -1e6f), from, to),
        )
        assertNear(0f, zoomBackEdgeFlightVelocity(Offset(10f, 10f), Offset(10f, 10f), from, from))
        assertNear(0f, zoomBackEdgeFlightVelocity(Offset(Float.NaN, 0f), Offset.Zero, from, to))
    }

    @Test
    fun aCardMovingAsOnePieceReadsTheSameEitherWay() {
        val from = Rect(0f, 0f, 200f, 300f)
        val to = Rect(300f, 400f, 500f, 700f)
        val velocity = Offset(150f, 200f)
        assertNear(
            zoomBackFlightVelocity(velocity, from, to),
            zoomBackEdgeFlightVelocity(velocity, velocity, from, to),
        )
    }
}
