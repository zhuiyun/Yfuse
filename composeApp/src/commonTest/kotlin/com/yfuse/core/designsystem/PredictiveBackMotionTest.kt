package com.yfuse.core.designsystem

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictiveBackMotionTest {
    private val page = Size(1000f, 2000f)

    @Test
    fun aPointerDownCannotTurnACancellingSideSwipeIntoAPosterPull() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.startSide(-1f, 0.6f)
                fixture.controller.cancelSide()
                fixture.frames(3)
                val before = fixture.controller.card()
                val edge = Offset(1f, page.height / 2f)
                assertTrue(before.bounds.contains(edge))
                assertFalse(fixture.controller.catchAt(edge))
                assertEquals(before, fixture.controller.card())
                assertEquals(ZoomBackPhase.Returning, fixture.controller.phase)
                assertTrue(fixture.controller.startSide(-1f, 0f))
                fixture.controller.moveSide(0.7f)
                fixture.controller.commitSide()
                fixture.frames(60)
                assertEquals(listOf(true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun aNewSwipeCanCatchACancellingPageWithoutJumpingOrBeingIgnored() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.startSide(1f, 0.6f)
                fixture.controller.cancelSide()
                fixture.frames(3)
                val caught = fixture.controller.card()
                assertTrue(fixture.controller.startSide(-1f, 0f))
                assertEquals(caught, fixture.controller.card())
                fixture.controller.moveSide(0.5f)
                assertEquals(1f, fixture.controller.card().contentScale)
                assertEquals(1f, fixture.controller.card().contentAlpha)
                fixture.controller.commitSide()
                fixture.frames(60)
                assertEquals(listOf(true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun eitherEdgeKeepsThePageOpaqueUntilItIsEntirelyOutsideTheViewport() {
        for (toward in listOf(-1f, 1f)) {
            val released = zoomBackSideCard(page, 0.65f, toward)
            assertEquals(released, zoomBackCommitCard(released, page, toward, 0f))
            for (step in 0..20) {
                val card = zoomBackCommitCard(released, page, toward, step / 20f)
                assertEquals(1f, card.contentAlpha)
                assertEquals(0f, card.artAlpha)
                assertEquals(released.contentScale, card.contentScale)
            }
            val last = zoomBackCommitCard(released, page, toward, 1f)
            assertTrue(if (toward > 0f) last.bounds.left >= page.width else last.bounds.right <= 0f)
            assertEquals(last, zoomBackCommitCard(released, page, toward, 2f))
        }
    }

    @Test
    fun committingNeverMorphsIntoAPosterAndFinishesExactlyOnce() =
        runTest {
            val fixture = Fixture(this)
            try {
                var targetReads = 0
                fixture.controller.resolveTarget = {
                    targetReads++
                    ZoomBackTarget(Rect(100f, 1200f, 300f, 1500f), null)
                }
                fixture.controller.startSide(-1f, 0f)
                fixture.controller.moveSide(0.7f)
                val released = fixture.controller.card()
                fixture.controller.commitSide()
                fixture.controller.commitSide()
                fixture.controller.cancelSide()
                assertEquals(released, fixture.controller.card())
                assertEquals(ZoomBackPhase.Sliding, fixture.controller.phase)
                assertTrue(fixture.controller.leaving)
                assertFalse(fixture.controller.startSide(1f, 0f))
                fixture.frames(60) {
                    if (fixture.controller.phase !=
                        ZoomBackPhase.Done
                    ) {
                        assertEquals(1f, fixture.controller.card().contentAlpha)
                    }
                }
                assertEquals(0, targetReads)
                assertEquals(listOf(true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun cancellationRestoresThePageAndTheNextCommitStartsAtItsOwnReleasePosition() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.startSide(1f, 0.8f)
                fixture.controller.cancelSide()
                fixture.frames(180) { assertEquals(1f, fixture.controller.card().contentAlpha) }
                assertTrue(fixture.controller.idle)
                assertEquals(ZoomCard.resting(page), fixture.controller.card())
                assertEquals(listOf(false), fixture.finished)

                fixture.controller.startSide(-1f, 0.3f)
                val released = fixture.controller.card()
                fixture.controller.commitSide()
                // The previous spring ended at 1; reusing that value would teleport this page off screen.
                assertEquals(released, fixture.controller.card())
                fixture.frames(60)
                assertEquals(listOf(false, true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    @Test
    fun replacingAPageDuringCommitCannotCompleteAnOldBack() =
        runTest {
            val fixture = Fixture(this)
            fixture.controller.startSide(1f, 0.6f)
            fixture.controller.commitSide()
            fixture.frames(3)
            fixture.controller.reset()
            fixture.frames(60)
            assertTrue(fixture.controller.idle)
            assertTrue(fixture.finished.isEmpty())
        }

    @Test
    fun reducedMotionKeepsThePageWholeAndCompletesWithoutAnimationFrames() =
        runTest {
            val fixture = Fixture(this)
            try {
                fixture.controller.still = true
                fixture.controller.startSide(-1f, 0.9f)
                assertEquals(ZoomCard.resting(page), fixture.controller.card())
                fixture.controller.cancelSide()
                runCurrent()
                assertEquals(listOf(false), fixture.finished)
                assertTrue(fixture.controller.idle)
                fixture.controller.startSide(1f, 0.9f)
                fixture.controller.commitSide()
                runCurrent()
                assertEquals(listOf(false, true), fixture.finished)
            } finally {
                fixture.controller.reset()
            }
        }

    private inner class Fixture(
        private val scope: TestScope,
    ) {
        private val clock = BroadcastFrameClock()
        private var nanos = 0L
        val finished = mutableListOf<Boolean>()
        val controller =
            ZoomBackController(CoroutineScope(scope.coroutineContext + clock)).apply {
                page = this@PredictiveBackMotionTest.page
                onFinished = { finished += it }
            }

        fun frames(
            count: Int,
            check: () -> Unit = {},
        ) {
            repeat(count) {
                scope.runCurrent()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                scope.runCurrent()
                check()
            }
        }
    }
}
