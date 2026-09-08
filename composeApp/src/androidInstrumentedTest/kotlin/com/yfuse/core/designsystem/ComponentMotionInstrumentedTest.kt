package com.yfuse.core.designsystem

import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
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

@RunWith(AndroidJUnit4::class)
class ComponentMotionInstrumentedTest {
    @Test
    fun fractional_indicator_progress_redraws_without_recomposing_or_relaying_out() {
        val offset = mutableFloatStateOf(0f)
        val counters = IndicatorCounters()
        val indicator = MotionBounds("indicator")
        val neighbor = MotionBounds("indicator neighbor")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IndicatorProbe(offset, counters, indicator)
                                Text("固定相邻内容", Modifier.onGloballyPositioned { neighbor.record(it.boundsInWindow()) })
                            }
                        }
                    }
                }
            }
            repeat(3) { awaitDraw(scenario) }
            indicator.freeze()
            neighbor.freeze()
            assertTrue("The indicator provider was never drawn", counters.providerReads.get() > 0)
            assertEquals("Provider read during initial composition", 0, counters.compositionReads.get())
            val compositions = counters.compositions.get()
            val measures = counters.measures.get()
            val placements = counters.placements.get()
            repeat(30) { frame ->
                val reads = counters.providerReads.get()
                scenario.onActivity { offset.floatValue = -0.75f + frame * 1.5f / 29f }
                awaitDraw(scenario)
                assertTrue("Progress did not reach drawing at frame $frame", counters.providerReads.get() > reads)
                assertEquals("Progress recomposed the probe", compositions, counters.compositions.get())
                assertEquals("Progress triggered measurement", measures, counters.measures.get())
                assertEquals("Progress triggered placement", placements, counters.placements.get())
                assertEquals("The component consumed the provider in composition", 0, counters.compositionReads.get())
                indicator.assertStable()
                neighbor.assertStable()
            }
        }
    }

    @Test
    fun burst_quick_toggles_and_mid_animation_policy_changes_keep_icon_and_neighbors_in_place() {
        val active = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val expectedIconPixels = AtomicReference(0f)
        val parent = MotionBounds("burst row")
        val icon = MotionBounds("burst icon")
        val neighbor = MotionBounds("burst neighbor")
        val probes = listOf(parent, icon, neighbor)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            val pixels = with(LocalDensity.current) { 24.dp.toPx() }
                            SideEffect { expectedIconPixels.set(pixels) }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Row(
                                    Modifier.onGloballyPositioned { parent.record(it.boundsInWindow()) },
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(24.dp).background(Color.Gray))
                                    BurstIcon(
                                        icon = if (active.value) AppIcons.HeartFilled else AppIcons.Heart,
                                        active = active.value,
                                        contentDescription = "收藏测试",
                                        tint = Color.White,
                                        burstColor = Color.Cyan,
                                        iconSize = 24.dp,
                                        modifier = Modifier.onGloballyPositioned { icon.record(it.boundsInWindow()) },
                                    )
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .onGloballyPositioned { neighbor.record(it.boundsInWindow()) }
                                            .background(Color.Gray),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            repeat(3) { awaitDraw(scenario) }
            probes.forEach(MotionBounds::freeze)
            val iconBounds = requireNotNull(icon.latest.get())
            assertEquals(
                "The burst ring enlarged the icon's layout width",
                expectedIconPixels.get(),
                iconBounds.width,
                1f,
            )
            assertEquals(
                "The burst ring enlarged the icon's layout height",
                expectedIconPixels.get(),
                iconBounds.height,
                1f,
            )

            fun change(update: () -> Unit) {
                scenario.onActivity { update() }
                awaitDraw(scenario)
                probes.forEach(MotionBounds::assertStable)
            }
            repeat(8) { index -> change { active.value = index % 2 == 0 } }
            change { active.value = true }
            change { reduced.value = true }
            repeat(3) { awaitDraw(scenario) }
            probes.forEach(MotionBounds::assertStable)
            change { reduced.value = false }
            change { active.value = false }
            change { active.value = true }
            change { visible.value = false }
            change { active.value = false }
            change { active.value = true }
            change { visible.value = true }
            repeat(3) { awaitDraw(scenario) }
            probes.forEach(MotionBounds::assertStable)
        }
    }

    /** Wait for an actual decor draw, not an arbitrary sleep or just an idle main-thread queue. */
    private fun awaitDraw(scenario: ActivityScenario<MainActivity>) {
        val drawn = CountDownLatch(1)
        val view = AtomicReference<View>()
        val observer = AtomicReference<ViewTreeObserver.OnDrawListener>()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val listener = ViewTreeObserver.OnDrawListener { decor.post { drawn.countDown() } }
            view.set(decor)
            observer.set(listener)
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try {
            assertTrue("The motion test did not receive a rendered frame", drawn.await(5, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity {
                view
                    .get()
                    ?.viewTreeObserver
                    ?.takeIf { it.isAlive }
                    ?.removeOnDrawListener(observer.get())
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

private class IndicatorCounters {
    val compositions = AtomicInteger()
    val measures = AtomicInteger()
    val placements = AtomicInteger()
    val providerReads = AtomicInteger()
    val compositionReads = AtomicInteger()
}

@OptIn(InternalComposeApi::class)
@Composable
private fun IndicatorProbe(
    offset: MutableFloatState,
    counters: IndicatorCounters,
    bounds: MotionBounds,
) {
    // The provider also detects reads inside the child composable; a parent SideEffect alone would miss them.
    val composition = currentComposer.composition
    val provider =
        remember(offset, composition, counters) {
            {
                counters.providerReads.incrementAndGet()
                if (composition.isComposing) counters.compositionReads.incrementAndGet()
                offset.floatValue
            }
        }
    SideEffect { counters.compositions.incrementAndGet() }
    HeroPageIndicator(
        pageCount = 5,
        selectedPage = 2,
        onPageSelected = {},
        pageOffsetProvider = provider,
        modifier =
            Modifier
                .layout { measurable, constraints ->
                    counters.measures.incrementAndGet()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        counters.placements.incrementAndGet()
                        placeable.placeRelative(0, 0)
                    }
                }.onGloballyPositioned { bounds.record(it.boundsInWindow()) },
    )
}

private class MotionBounds(
    private val name: String,
) {
    val latest = AtomicReference<Rect?>()
    private val baseline = AtomicReference<Rect?>()
    private val violation = AtomicReference<String?>()

    fun record(value: Rect) {
        latest.set(value)
        val expected = baseline.get() ?: return
        if (abs(value.left - expected.left) > 0.5f ||
            abs(value.top - expected.top) > 0.5f ||
            abs(value.right - expected.right) > 0.5f ||
            abs(value.bottom - expected.bottom) > 0.5f
        ) {
            violation.compareAndSet(null, "$name moved from $expected to $value")
        }
    }

    fun freeze() {
        baseline.set(requireNotNull(latest.get()) { "$name was never laid out" })
    }

    fun assertStable() {
        assertNull(violation.get(), violation.get())
    }
}
