package com.yfuse.core.designsystem

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailBackMotionTest {
    private val page = Size(1000f, 2000f)

    @Test
    fun returningDuringPosterEntryDisablesTheMorphButTheNextEntryStillExpands() {
        val controller = SharedMediaTransitionController()
        val poster = MediaSharedElementKey("server", "movie")
        controller.begin(poster)
        assertTrue(controller.isArtworkRegistered(poster))
        controller.suppressForPop()
        assertFalse(controller.isArtworkRegistered(poster))
        controller.finish(poster)
        assertFalse(controller.hasPreparedReturn)
        controller.begin(poster)
        assertTrue(controller.isArtworkRegistered(poster))
    }

    @Test
    fun opaqueDetailShrinksGentlyAndLeavesTheViewportCompletely() {
        for (toward in listOf(-1f, 1f)) {
            val rest = ZoomCard.resting(page)
            assertEquals(rest, detailBackCard(rest, page, toward, 0f, 24f))
            for (step in 0..20) {
                val p = step / 20f
                val card = detailBackCard(rest, page, toward, p, 24f)
                near(1f - 0.04f * p, card.contentScale)
                near(24f * p, card.cornerRadius)
                assertEquals(1f, card.contentAlpha)
                assertEquals(0f, card.artAlpha)
            }
            val gone = detailBackCard(rest, page, toward, 1f, 24f)
            assertTrue(if (toward > 0) gone.bounds.left >= page.width else gone.bounds.right <= 0f)
        }
    }

    @Test
    fun releaseContinuesFromTheExactPreviewIncludingCorners() {
        val released = detailBackCard(ZoomCard.resting(page), page, 1f, 0.43f, 24f)
        assertEquals(released, detailBackCard(released, page, 1f, 0f, 24f))
        val halfway = detailBackCard(released, page, 1f, 0.5f, 24f)
        near(0.96f + (released.contentScale - 0.96f) / 2, halfway.contentScale)
        assertTrue(halfway.bounds.left > released.bounds.left)
    }

    @Test
    fun releaseDurationIsBoundedAndShortensWithRemainingTravel() {
        assertEquals(240, detailBackReleaseDuration(0f))
        assertEquals(210, detailBackReleaseDuration(0.5f))
        assertEquals(180, detailBackReleaseDuration(1f))
        assertEquals(180, detailBackReleaseDuration(2f))
        assertEquals(240, detailBackReleaseDuration(-1f))
    }

    @Test
    fun releaseVelocityCannotOvershootOrReverseThePage() {
        for (speed in listOf(-5f, 0f, 2f, 12f, 100f, Float.NaN)) {
            val easing = detailBackReleaseEasing(speed, 200)
            val positions = (0..100).map { easing.transform(it / 100f) }
            near(0f, positions.first())
            near(1f, positions.last())
            assertTrue(positions.all { it in 0f..1f })
            assertTrue(positions.zipWithNext().all { (a, b) -> b >= a })
        }
        assertTrue(detailBackReleaseEasing(4f, 200).transform(0.1f) > detailBackReleaseEasing(0f, 200).transform(0.1f))
    }

    @Test
    fun nestedDetailPopsRememberTheDepartingRouteAndResetOnOtherNavigation() {
        val routes = DetailReturnRoutes()
        val stack = listOf("list", "detail", "related")
        routes.update(stack, setOf("detail", "related"))
        assertTrue(routes.isDetailReturn(stack.take(2)))
        routes.update(stack.take(2), setOf("detail"))
        assertTrue(routes.isDetailReturn(stack.take(2)))
        assertTrue(routes.isDetailReturn(stack.take(1)))
        routes.update(stack.take(1), emptySet())
        assertFalse(routes.isDetailReturn(listOf("list", "settings")))
        routes.update(listOf("list", "settings"), emptySet())
        assertFalse(routes.isDetailReturn(listOf("list")))
        assertFalse(routes.isDetailReturn(listOf("replacement")))
    }

    @Test
    fun previewCommitAndDuplicateCompletionPopExactlyOnceWithoutLookingUpAPoster() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.resolveTarget = { error("A detail side return must not resolve a poster") }
                fixture.controller.startSide(1f, 0f, detailPreview = true)
                fixture.controller.moveSide(0.4f)
                val release = fixture.controller.card()
                near(-24f, fixture.controller.underlayTranslation(page.width))
                fixture.controller.commitSide()
                fixture.controller.commitSide()
                fixture.controller.cancelSide()
                assertEquals(release, fixture.controller.card())
                assertFalse(fixture.controller.startSide(1f, 0f, detailPreview = true))
                fixture.frames(18)
                assertEquals(listOf(true), fixture.finished)
                assertEquals(ZoomBackPhase.Done, fixture.controller.phase)
                near(0f, fixture.controller.underlayTranslation(page.width))
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun cancellingRestoresGeometryAndARegrabPreservesBothPages() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.startSide(1f, 0.8f, detailPreview = true)
                fixture.controller.cancelSide()
                fixture.frames(3)
                val before = fixture.controller.card()
                val underlay = fixture.controller.underlayTranslation(page.width)
                fixture.controller.startSide(1f, 0f, detailPreview = true)
                assertEquals(before, fixture.controller.card())
                near(underlay, fixture.controller.underlayTranslation(page.width))
                fixture.controller.cancelSide()
                fixture.frames(120)
                assertEquals(ZoomCard.resting(page), fixture.controller.card())
                near(0f, fixture.controller.underlayTranslation(page.width))
                assertEquals(listOf(false), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun platformDecisionOverridesProgressWithoutAnAppCommitThreshold() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.startSide(1f, 0.95f, detailPreview = true)
                fixture.controller.cancelSide()
                fixture.frames(120)
                assertEquals(listOf(false), fixture.finished)
                fixture.controller.startSide(1f, 0.01f, detailPreview = true)
                fixture.controller.commitSide()
                fixture.frames(18)
                assertEquals(listOf(false, true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun reducedMotionRestoresAndCompletesWithoutWaitingForAFrame() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.still = true
                fixture.controller.startSide(1f, 0.5f, detailPreview = true)
                assertEquals(ZoomCard.resting(page), fixture.controller.card())
                near(0f, fixture.controller.underlayTranslation(page.width))
                fixture.controller.cancelSide()
                runCurrent()
                fixture.controller.startSide(1f, 0.5f, detailPreview = true)
                fixture.controller.commitSide()
                runCurrent()
                assertEquals(listOf(false, true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun routeReplacementCancelsPendingDetailCompletion() =
        runTest {
            val fixture = Fixture(this)
            fixture.controller.startSide(1f, 0.6f, detailPreview = true)
            fixture.controller.commitSide()
            fixture.frames(3)
            fixture.controller.reset()
            fixture.frames(30)
            assertTrue(fixture.finished.isEmpty())
            assertEquals(ZoomCard.resting(page), fixture.controller.card())
        }

    private fun near(
        expected: Float,
        actual: Float,
    ) = assertTrue(abs(expected - actual) < 0.001f, "$expected != $actual")

    private inner class Fixture(
        private val scope: TestScope,
    ) {
        private val clock = BroadcastFrameClock()
        private var nanos = 0L
        val finished = mutableListOf<Boolean>()
        val controller =
            ZoomBackController(CoroutineScope(scope.coroutineContext + clock)).apply {
                page = this@DetailBackMotionTest.page
                detailCornerPx = 24f
                onFinished = { finished += it }
            }

        fun frames(count: Int) {
            repeat(count) {
                scope.runCurrent()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                scope.runCurrent()
            }
        }
    }
}
