package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CarouselEffectsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 30_000L)
    fun page_colour_frames_only_invalidate_drawing_and_hidden_or_reduced_pages_snap() {
        val target = mutableStateOf(Color(0xFF050505))
        val visible = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        val output = AtomicReference<State<Color>>()
        val drawn = AtomicReference<Color>()
        val firstOutputForTarget = AtomicReference<Pair<Color, Color>>()
        val compositions = AtomicInteger()
        val draws = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            val currentTarget = target.value
                            val colour = rememberCarouselPageColor(currentTarget)
                            SideEffect {
                                output.set(colour)
                                compositions.incrementAndGet()
                                if (firstOutputForTarget.get()?.first != currentTarget) {
                                    firstOutputForTarget.set(currentTarget to colour.value)
                                }
                            }
                            Box(
                                Modifier.fillMaxSize().drawBehind {
                                    drawn.set(colour.value)
                                    draws.incrementAndGet()
                                    drawRect(colour.value)
                                },
                            )
                        }
                    }
                }
            }
            waitUntil { drawn.get() == target.value }
            val first = target.value
            val second = Color(0xFF101010)
            scenario.onActivity { target.value = second }
            waitUntil { drawn.get() != first && drawn.get() != second }
            val animationCompositions = compositions.get()
            val animationDraws = draws.get()
            waitUntil { drawn.get() == second }
            instrumentation.waitForIdleSync()
            assertEquals("Colour frames recomposed their host", animationCompositions, compositions.get())
            assertTrue("Colour animation did not draw subsequent frames", draws.get() > animationDraws)

            val third = Color(0xFF202020)
            scenario.onActivity { target.value = third }
            waitUntil { drawn.get() != second && drawn.get() != third }
            scenario.onActivity { visible.value = false }
            waitUntil { output.get()?.value == third }
            instrumentation.waitForIdleSync()
            val hiddenCompositions = compositions.get()
            SystemClock.sleep(150L)
            assertEquals(third, output.get().value)
            assertEquals(hiddenCompositions, compositions.get())
            scenario.onActivity { visible.value = true }
            instrumentation.waitForIdleSync()
            assertEquals("Returning to the page replayed its colour animation", third, output.get().value)

            val fourth = Color(0xFF303030)
            scenario.onActivity {
                reduced.value = true
                target.value = fourth
            }
            waitUntil { output.get()?.value == fourth }
            assertEquals(fourth to fourth, firstOutputForTarget.get())
            assertEquals(fourth, output.get().value)
            scenario.onActivity {
                reduced.value = false
                target.value = Color.White
            }
            // A light target uses dark text: retaining even the first dark-background frame is unsafe.
            waitUntil { output.get()?.value == Color.White }
            assertEquals(Color.White to Color.White, firstOutputForTarget.get())
            assertEquals(Color.White, output.get().value)
        }
    }

    @Test
    fun caption_animates_on_selection_but_not_when_returning_or_reducing_motion() {
        val selected = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        val progressRef = AtomicReference<State<Float>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            val progress = rememberCarouselCaptionProgress(selected.value)
                            SideEffect { progressRef.set(progress) }
                            Text("轮播动画测试", modifier = Modifier.carouselCaptionEntry(progress, 0))
                        }
                    }
                }
            }
            waitUntil { progressRef.get()?.value == 0f }
            scenario.onActivity { selected.value = true }
            waitUntil { (progressRef.get()?.value ?: 0f) in 0.01f..0.99f }
            waitUntil { progressRef.get()?.value == 1f }
            scenario.onActivity { visible.value = false }
            instrumentation.waitForIdleSync()
            scenario.onActivity { visible.value = true }
            instrumentation.waitForIdleSync()
            assertEquals(1f, progressRef.get().value)
            scenario.onActivity {
                reduced.value = true
                selected.value = false
            }
            instrumentation.waitForIdleSync()
            assertEquals(1f, progressRef.get().value)
        }
    }

    @Test
    fun saved_pager_returns_to_the_same_card_and_preserves_identity_after_refresh() {
        val shown = mutableStateOf(true)
        val ids = mutableStateOf(listOf("a", "b", "c", "d"))
        val pagerRef = AtomicReference<PagerState>()
        val scopeRef = AtomicReference<CoroutineScope>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val holder = rememberSaveableStateHolder()
                    val scope = rememberCoroutineScope()
                    SideEffect { scopeRef.set(scope) }
                    if (shown.value) {
                        holder.SaveableStateProvider("carousel") {
                            val pager = rememberLoopingCarouselState(ids.value)
                            SideEffect { pagerRef.set(pager) }
                            HorizontalPager(state = pager, modifier = Modifier.height(160.dp)) {
                                Box(Modifier.fillMaxSize()) { Text("轮播位置测试") }
                            }
                        }
                    } else {
                        SideEffect { pagerRef.set(null) }
                    }
                }
            }
            waitUntil { pagerRef.get() != null }
            scenario.onActivity {
                scopeRef.get().launch {
                    pagerRef.get().scrollToPage(loopingCarouselStartPage(4) + 2)
                }
            }
            waitUntil { pagerRef.get()?.settledPage?.mod(4) == 2 }
            val savedPage = pagerRef.get().settledPage
            scenario.onActivity { shown.value = false }
            waitUntil { pagerRef.get() == null }
            scenario.onActivity { shown.value = true }
            waitUntil { pagerRef.get() != null }
            assertEquals(savedPage, pagerRef.get().currentPage)
            scenario.onActivity { ids.value = listOf("d", "c", "b", "a") }
            waitUntil { pagerRef.get()?.settledPage?.mod(4) == 1 }
            assertEquals("c", ids.value[pagerRef.get().settledPage.mod(4)])
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        assertTrue("Carousel state did not settle", condition())
    }
}
