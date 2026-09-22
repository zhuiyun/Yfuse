package com.yfuse.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NavigationMotionInstrumentedTest {
    @Test(timeout = 30_000L)
    fun an_interrupted_dock_resize_does_not_recompose_or_remeasure_tab_content() {
        val width = mutableStateOf(300.dp)
        val compositions = AtomicInteger()
        val contentMeasures = AtomicInteger()
        val viewportWidth = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SideEffect { compositions.incrementAndGet() }
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .onSizeChanged { viewportWidth.set(it.width) }
                                .navigationDockViewport(width, 300.dp)
                                .height(62.dp),
                        ) {
                            // Probe an actual child node, like the tab row inside AnimatedContent.
                            // Another layout modifier on the viewport would count the viewport itself.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .layout { measurable, constraints ->
                                        contentMeasures.incrementAndGet()
                                        val child = measurable.measure(constraints)
                                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                                    },
                            )
                        }
                    }
                }
            }
            waitUntil { viewportWidth.get() > 0 }
            instrumentation.waitForIdleSync()
            val fullWidth = viewportWidth.get()
            val baselineCompositions = compositions.get()
            val baselineMeasures = contentMeasures.get()
            // Reverse direction before either endpoint, just like a tap interrupting a scroll collapse.
            for (next in listOf(250, 160, 220, 62, 180, 300)) {
                scenario.onActivity { width.value = next.dp }
                val expected = (fullWidth * next / 300f).roundToInt()
                waitUntil { abs(viewportWidth.get() - expected) <= 1 }
            }
            instrumentation.waitForIdleSync()
            assertEquals("Animation recomposed the tab subtree", baselineCompositions, compositions.get())
            assertEquals("Animation remeasured the tab subtree", baselineMeasures, contentMeasures.get())
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10L)
        }
        assertTrue("Navigation viewport did not update", condition())
    }
}
