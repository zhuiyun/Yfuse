package com.yfuse.feature.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import com.yfuse.core.designsystem.HandoffLaunch
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.core.designsystem.ScreenGeometry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PlayerPredictiveBackTest {
    @Test
    fun cancellingAndStartingAgainCapturesTheCurrentVideoFrame() =
        runTest {
            val state = PlayerTransitionState(launch(TestTimeSource()), this)
            val oldFrame = frame()
            val currentFrame = frame()
            var captures = 0
            state.snapshotSource = { if (++captures == 1) oldFrame else currentFrame }

            state.onBackProgress(0.5f)
            runCurrent()
            assertSame(oldFrame, state.exitFrame)
            state.onBackCancel()
            assertFalse(state.backActive)

            state.onBackProgress(0.2f)
            runCurrent()
            assertEquals(2, captures)
            assertSame(currentFrame, state.exitFrame)
        }

    @Test
    fun aLateSnapshotFromACancelledGestureCannotReplaceTheNewFrame() =
        runTest {
            val state = PlayerTransitionState(launch(TestTimeSource()), this)
            val oldFrame = frame()
            val currentFrame = frame()
            val oldCapture = CompletableDeferred<Unit>()
            var captures = 0
            state.snapshotSource = {
                if (++captures == 1) {
                    withContext(NonCancellable) {
                        oldCapture.await()
                        oldFrame
                    }
                } else {
                    currentFrame
                }
            }

            state.onBackProgress(0.5f)
            runCurrent()
            state.onBackCancel()
            state.onBackProgress(0.3f)
            runCurrent()
            assertEquals(2, captures)
            assertSame(currentFrame, state.exitFrame)

            oldCapture.complete(Unit)
            runCurrent()
            assertSame(currentFrame, state.exitFrame)
        }

    @Test
    fun cancellationRestoresChromeAtTheSameSpeedAcrossFrameRates() =
        runTest {
            listOf(8, 16, 33).forEach { frameMillis ->
                val clock = TestTimeSource()
                val state = PlayerTransitionState(launch(clock), this)
                state.markReady()
                state.tick(handsOverLate = false)
                clock += 10_000.milliseconds
                state.tick(handsOverLate = false)
                state.snapshotSource = { frame() }
                state.onBackProgress(1f)
                runCurrent()
                assertTrue(state.exitFrame != null)
                state.onBackCancel()
                var elapsed = 0
                while (elapsed < 100) {
                    val step = minOf(frameMillis, 100 - elapsed)
                    clock += step.milliseconds
                    state.tick(handsOverLate = false)
                    elapsed += step
                }
                assertEquals(0.85f - 100f / 220f, state.backProgress, absoluteTolerance = 0.0001f)

                clock += 200.milliseconds
                state.tick(handsOverLate = false)
                assertEquals(0f, state.backProgress)
                assertEquals(1f, state.chromeAlpha())
                assertNull(state.exitFrame)
            }
        }

    @Test
    fun disablingMotionDuringCommittedExitStillFinishesOnce() =
        runTest {
            val state = PlayerTransitionState(launch(TestTimeSource()), this)
            var finishes = 0
            assertTrue(state.requestExit { finishes++ })
            state.disable()
            runCurrent()

            assertTrue(state.disabled)
            assertTrue(state.finished)
            assertEquals(1, finishes)
            state.disable()
            state.tick(handsOverLate = false)
            assertEquals(1, finishes)
        }

    @Test
    fun aCancellationAfterCommitCannotCancelThePendingExit() =
        runTest {
            val clock = TestTimeSource()
            val state = PlayerTransitionState(launch(clock), this)
            var finishes = 0
            assertTrue(state.requestExit { finishes++ })
            state.onBackCancel()
            runCurrent()
            assertTrue(state.closing)
            assertTrue(state.exitAt != null)

            clock += 10_000.milliseconds
            state.tick(handsOverLate = false)
            assertEquals(1, finishes)
        }

    private fun launch(clock: TestTimeSource): HandoffLaunch =
        HandoffLaunch(
            style = PlayerTransitionStyle.Glass,
            startedAt = clock.markNow(),
            screen = ScreenGeometry(0, Size(1080f, 2400f)),
            hero = Rect(0f, 0f, 1080f, 1150f),
            urls = emptyList(),
            key = null,
        )

    /** Identity-only video frame: these tests never need an Android bitmap or its pixels. */
    private fun frame(): ImageBitmap =
        Proxy.newProxyInstance(
            ImageBitmap::class.java.classLoader,
            arrayOf(ImageBitmap::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "video-frame"
                else -> error("Unexpected bitmap operation: ${method.name}")
            }
        } as ImageBitmap
}
