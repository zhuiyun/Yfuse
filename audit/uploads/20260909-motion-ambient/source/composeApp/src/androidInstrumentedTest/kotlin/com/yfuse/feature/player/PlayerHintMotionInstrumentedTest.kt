package com.yfuse.feature.player

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.YfuseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlayerHintMotionInstrumentedTest {
    @Test
    fun hints_move_without_remeasurement_and_hit_targets_follow_reversals_and_reduced_motion() {
        val progress = mutableFloatStateOf(0f)
        val useAnimation = mutableStateOf(false)
        val controls = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val skip = HintProbeState()
        val chat = HintProbeState()
        val video = AtomicReference<Rect>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        val phase = if (useAnimation.value) rememberPlayerHintProgress(controls.value) else progress
                        // Keep both the current and previous touch positions inside this window.
                        // The Activity draws edge-to-edge; a bottom hint at 24dp otherwise overlaps
                        // Android 9's navigation bar and sendPointerSync targets System UI.
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { video.set(it.boundsInRoot()) })
                            HintProbe(
                                phase,
                                (-60).dp,
                                skip,
                                Modifier.align(Alignment.BottomEnd),
                                bottomPadding = 24.dp,
                            )
                            HintProbe(
                                phase,
                                52.dp,
                                chat,
                                Modifier.align(Alignment.TopEnd),
                                topPadding = 18.dp,
                            )
                        }
                    }
                }
            }
            repeat(3) { awaitDraw(scenario) }
            val initialSkip = requireNotNull(skip.bounds.get())
            val initialChat = requireNotNull(chat.bounds.get())
            val initialVideo = requireNotNull(video.get())
            val compositions = listOf(skip.compositions.get(), chat.compositions.get())
            val measures = listOf(skip.measures.get(), chat.measures.get())
            repeat(12) { frame ->
                scenario.onActivity { progress.floatValue = (frame + 1) / 12f }
                awaitDraw(scenario)
                assertEquals(
                    "Hint progress recomposed content",
                    compositions,
                    listOf(skip.compositions.get(), chat.compositions.get()),
                )
                assertEquals(
                    "Hint progress remeasured content",
                    measures,
                    listOf(skip.measures.get(), chat.measures.get()),
                )
                assertEquals("Hint motion moved the video area", initialVideo, video.get())
            }
            assertEquals(initialSkip.top + skip.travelPixels.get(), skip.bounds.get().top, 1f)
            assertEquals(initialChat.top + chat.travelPixels.get(), chat.bounds.get().top, 1f)
            tap(skip.bounds.get().center)
            tap(chat.bounds.get().center)
            assertEquals("The moved skip target did not receive its tap", 1, skip.clicks.get())
            assertEquals("The moved chat target did not receive its tap", 1, chat.clicks.get())
            tap(initialSkip.center)
            tap(initialChat.center)
            assertEquals("The skip target still accepted its previous position", 1, skip.clicks.get())
            assertEquals("The chat target still accepted its previous position", 1, chat.clicks.get())

            scenario.onActivity { useAnimation.value = true }
            awaitDraw(scenario)
            repeat(3) { index ->
                scenario.onActivity { controls.value = index % 2 == 0 }
                awaitDraw(scenario)
            }
            scenario.onActivity { reduced.value = true }
            awaitDraw(scenario)
            assertTrue(
                "Reduced motion did not immediately reach the visible-control anchor",
                abs(skip.bounds.get().top - initialSkip.top - skip.travelPixels.get()) <= 1f &&
                    abs(chat.bounds.get().top - initialChat.top - chat.travelPixels.get()) <= 1f,
            )
            scenario.onActivity { controls.value = false }
            awaitDraw(scenario)
            assertTrue(
                "Reduced motion did not reach the hidden-control anchor",
                abs(
                    skip.bounds.get().top - initialSkip.top,
                ) <= 1f &&
                    abs(chat.bounds.get().top - initialChat.top) <= 1f,
            )
            assertEquals(initialVideo, video.get())
            tap(skip.bounds.get().center)
            tap(chat.bounds.get().center)
            assertEquals(2, skip.clicks.get())
            assertEquals(2, chat.clicks.get())
        }
    }

    private fun tap(position: Offset) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitDraw(scenario: ActivityScenario<MainActivity>) {
        val latch = CountDownLatch(1)
        val listener = AtomicReference<ViewTreeObserver.OnDrawListener>()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            listener.set(ViewTreeObserver.OnDrawListener { decor.post { latch.countDown() } })
            decor.viewTreeObserver.addOnDrawListener(listener.get())
            decor.postInvalidateOnAnimation()
        }
        try {
            assertTrue("Hint test did not receive a draw", latch.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity { activity ->
                activity.window.decorView.viewTreeObserver
                    .takeIf { it.isAlive }
                    ?.removeOnDrawListener(listener.get())
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

private class HintProbeState {
    val compositions = AtomicInteger()
    val measures = AtomicInteger()
    val clicks = AtomicInteger()
    val bounds = AtomicReference<Rect>()
    val travelPixels = AtomicReference(0f)
}

@Composable
private fun HintProbe(
    phase: State<Float>,
    travel: Dp,
    probe: HintProbeState,
    modifier: Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    val owner = LocalView.current
    val pixels = with(LocalDensity.current) { travel.toPx() }
    SideEffect {
        probe.compositions.incrementAndGet()
        probe.travelPixels.set(pixels)
    }
    Box(
        modifier
            .playerHintOffset(phase, travel)
            .padding(end = 22.dp, top = topPadding, bottom = bottomPadding)
            .size(140.dp, 36.dp)
            .layout { measurable, constraints ->
                probe.measures.incrementAndGet()
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }.onGloballyPositioned {
                val origin = IntArray(2)
                owner.getLocationOnScreen(origin)
                probe.bounds.set(it.boundsInRoot().translate(Offset(origin[0].toFloat(), origin[1].toFloat())))
            }.clickable { probe.clicks.incrementAndGet() }
            .background(Color.Gray),
    )
}
