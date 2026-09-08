package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.yfuse.MainActivity
import com.yfuse.feature.search.SearchResultsPhase
import com.yfuse.feature.search.rememberSearchResultsHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class SearchDisclosureMotionInstrumentedTest {
    @Test
    fun search_handoff_keeps_one_content_tree_and_layer_frames_do_not_recompose_it() {
        val phase = mutableStateOf(SearchResultsPhase.Loading)
        val revision = mutableIntStateOf(0)
        val reduced = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val metrics = SearchDisclosureMetrics()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            SearchHandoffProbe(phase.value, revision.intValue, metrics)
                        }
                    }
                }
            }
            awaitDraw(scenario)
            val compositions = metrics.compositions.get()
            val contentCompositions = metrics.contentCompositions.get()
            val frames = observeAnimation(scenario) { phase.value = SearchResultsPhase.Results }
            assertTrue("The layer transition did not produce multiple host draws", frames >= 3)
            assertEquals("Animation frames recomposed the host", compositions + 1, metrics.compositions.get())
            assertTrue(
                "Animation frames recomposed content",
                metrics.contentCompositions.get() <= contentCompositions + 1,
            )
            assertEquals(1, metrics.created.get())
            assertEquals(1, metrics.maximumLive.get())
            assertOpaque(scenario, metrics)

            // A real recomposition with a new page/count payload keeps the same Results phase.
            awaitDraw(scenario) { revision.intValue++ }
            assertOpaque(scenario, metrics)
            assertEquals(1, metrics.created.get())

            awaitDraw(scenario) { phase.value = SearchResultsPhase.Loading }
            awaitDraw(scenario) { phase.value = SearchResultsPhase.Empty }
            awaitDraw(scenario) { visible.value = false }
            assertOpaque(scenario, metrics)
            awaitDraw(scenario) { phase.value = SearchResultsPhase.Loading }
            awaitDraw(scenario) { phase.value = SearchResultsPhase.Results }
            awaitDraw(scenario) { visible.value = true }
            assertOpaque(scenario, metrics)

            awaitDraw(scenario) { phase.value = SearchResultsPhase.Loading }
            awaitDraw(scenario) { phase.value = SearchResultsPhase.Error }
            awaitDraw(scenario) { reduced.value = true }
            assertOpaque(scenario, metrics)
            awaitDraw(scenario) { phase.value = SearchResultsPhase.Results }
            awaitDraw(scenario) { reduced.value = false }
            assertOpaque(scenario, metrics)
            assertEquals(1, metrics.maximumLive.get())
        }
        assertEquals("The content tree leaked after Activity disposal", 0, metrics.live.get())
    }

    @Test
    fun disclosure_reverses_one_body_without_remeasuring_it_and_policy_changes_snap_to_the_target() {
        val expanded = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val metrics = SearchDisclosureMetrics()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            DisclosureProbe(expanded.value, metrics)
                        }
                    }
                }
            }
            awaitDraw(scenario)
            assertDisclosureEnd(metrics, expanded = false)
            awaitPartial(scenario, metrics) { expanded.value = true }
            val measures = metrics.measures.get()
            repeat(4) { index -> awaitDraw(scenario) { expanded.value = index % 2 == 1 } }
            observeAnimation(scenario) {}
            assertDisclosureEnd(metrics, expanded = true)
            assertEquals("Reversal duplicated or recreated the body", 1, metrics.created.get())
            assertEquals("Height animation remeasured the unchanged body", measures, metrics.measures.get())
            assertNull(metrics.layoutViolation.get(), metrics.layoutViolation.get())

            awaitPartial(scenario, metrics) { expanded.value = false }
            awaitDraw(scenario) { reduced.value = true }
            assertDisclosureEnd(metrics, expanded = false)
            awaitDraw(scenario) { reduced.value = false }
            awaitPartial(scenario, metrics) { expanded.value = true }
            awaitDraw(scenario) { visible.value = false }
            assertDisclosureEnd(metrics, expanded = true)
            awaitDraw(scenario) { expanded.value = false }
            assertDisclosureEnd(metrics, expanded = false)
            awaitDraw(scenario) { visible.value = true }
            assertDisclosureEnd(metrics, expanded = false)
            assertEquals("Two disclosure bodies existed at once", 1, metrics.maximumLive.get())
            assertNull(metrics.layoutViolation.get(), metrics.layoutViolation.get())
        }
        assertEquals(0, metrics.live.get())
    }

    private fun assertDisclosureEnd(
        metrics: SearchDisclosureMetrics,
        expanded: Boolean,
    ) {
        assertEquals(if (expanded) 1f else 0f, metrics.progress.get(), 0.001f)
        assertEquals(if (expanded) metrics.bodyHeight.get() else 0, metrics.viewportHeight.get())
        assertEquals(if (expanded) 1 else 0, metrics.live.get())
    }

    private fun awaitPartial(
        scenario: ActivityScenario<MainActivity>,
        metrics: SearchDisclosureMetrics,
        update: () -> Unit,
    ) {
        val partial = CountDownLatch(1)
        metrics.partial.set(partial)
        try {
            scenario.onActivity { update() }
            assertTrue("No intermediate disclosure frame was rendered", partial.await(5, TimeUnit.SECONDS))
        } finally {
            metrics.partial.set(null)
        }
    }

    /** Observe actual animation-driven View draws; the frame clock only bounds the observation window. */
    private fun observeAnimation(
        scenario: ActivityScenario<MainActivity>,
        update: () -> Unit,
    ): Int {
        val frames = AtomicInteger()
        val finished = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { frames.incrementAndGet() }
        var startedNs = 0L
        val clock =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (startedNs == 0L) startedNs = frameTimeNanos
                    if (frameTimeNanos - startedNs >= 260_000_000L) {
                        finished.countDown()
                    } else {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
        scenario.onActivity {
            it.window.decorView.viewTreeObserver
                .addOnDrawListener(listener)
            update()
            Choreographer.getInstance().postFrameCallback(clock)
        }
        try {
            assertTrue("Animation observation timed out", finished.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                it.window.decorView.viewTreeObserver
                    .removeOnDrawListener(listener)
                Choreographer.getInstance().removeFrameCallback(clock)
            }
        }
        return frames.get()
    }

    private fun awaitDraw(
        scenario: ActivityScenario<MainActivity>,
        update: () -> Unit = {},
    ) {
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        scenario.onActivity {
            it.window.decorView.viewTreeObserver
                .addOnDrawListener(listener)
            update()
            it.window.decorView.postInvalidateOnAnimation()
        }
        try {
            assertTrue("The test did not receive a View draw", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                it.window.decorView.viewTreeObserver
                    .removeOnDrawListener(listener)
            }
        }
    }

    private fun assertOpaque(
        scenario: ActivityScenario<MainActivity>,
        metrics: SearchDisclosureMetrics,
    ) {
        val bounds = requireNotNull(metrics.bounds.get())
        val x = bounds.center.x.roundToInt()
        val y = bounds.center.y.roundToInt()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        val result = AtomicInteger(-1)
        try {
            scenario.onActivity {
                PixelCopy.request(
                    it.window,
                    android.graphics.Rect(x - 2, y - 2, x + 2, y + 2),
                    bitmap,
                    { code ->
                        result.set(code)
                        copied.countDown()
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            assertTrue("PixelCopy did not finish", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result.get())
            assertTrue(
                "A stable or motion-disabled result was faded",
                android.graphics.Color.red(bitmap.getPixel(2, 2)) > 240,
            )
        } finally {
            bitmap.recycle()
        }
    }
}

private class SearchDisclosureMetrics {
    val compositions = AtomicInteger()
    val contentCompositions = AtomicInteger()
    val measures = AtomicInteger()
    val created = AtomicInteger()
    val live = AtomicInteger()
    val maximumLive = AtomicInteger()
    val progress = AtomicReference(0f)
    val bodyHeight = AtomicInteger()
    val viewportHeight = AtomicInteger()
    val layoutViolation = AtomicReference<String?>()
    val partial = AtomicReference<CountDownLatch?>()
    val bounds = AtomicReference<Rect?>()
}

@Composable
private fun SearchHandoffProbe(
    phase: SearchResultsPhase,
    revision: Int,
    metrics: SearchDisclosureMetrics,
) {
    val handoff = rememberSearchResultsHandoff(phase)
    SideEffect {
        check(revision >= 0)
        metrics.compositions.incrementAndGet()
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        SingleMotionBody(
            metrics,
            Modifier.size(64.dp).then(handoff).onGloballyPositioned { metrics.bounds.set(it.boundsInWindow()) },
        )
    }
}

@Composable
private fun DisclosureProbe(
    expanded: Boolean,
    metrics: SearchDisclosureMetrics,
) {
    val progress = rememberDisclosureProgress(expanded)
    Box(
        Modifier.fillMaxSize().background(Color.Black).drawWithContent {
            val fraction = progress.value
            metrics.progress.set(fraction)
            val height = metrics.viewportHeight.get()
            val expected = (metrics.bodyHeight.get() * fraction).roundToInt()
            if (abs(height - expected) > 1) {
                metrics.layoutViolation.compareAndSet(
                    null,
                    "Body viewport $height did not follow $fraction ($expected)",
                )
            }
            drawContent()
            if (fraction > 0f && fraction < 1f) metrics.partial.get()?.countDown()
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(240.dp).onSizeChanged { metrics.viewportHeight.set(it.height) }) {
            DisclosureContent(expanded, progress) {
                SingleMotionBody(
                    metrics,
                    Modifier.fillMaxWidth().height(120.dp).onSizeChanged { metrics.bodyHeight.set(it.height) },
                )
            }
        }
    }
}

@Composable
private fun SingleMotionBody(
    metrics: SearchDisclosureMetrics,
    modifier: Modifier,
) {
    DisposableEffect(metrics) {
        metrics.created.incrementAndGet()
        val count = metrics.live.incrementAndGet()
        metrics.maximumLive.updateAndGet { maxOf(it, count) }
        onDispose { metrics.live.decrementAndGet() }
    }
    SideEffect { metrics.contentCompositions.incrementAndGet() }
    Box(
        modifier
            .layout { measurable, constraints ->
                metrics.measures.incrementAndGet()
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }.background(Color.White),
    )
}
