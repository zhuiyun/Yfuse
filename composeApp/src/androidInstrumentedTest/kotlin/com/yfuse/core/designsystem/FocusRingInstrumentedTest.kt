package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FocusRingInstrumentedTest {
    @Test
    fun real_focus_ring_redraws_without_recomposing_each_frame_and_settles_after_interruptions() {
        val reduced = mutableStateOf(false)
        val requester = FocusRequester()
        val parkingRequester = FocusRequester()
        val parkingFocused = AtomicBoolean()
        val counters = FocusRingCounters()
        val focusManager = AtomicReference<FocusManager>()
        val inputManager = AtomicReference<InputModeManager>()
        val originalInputMode = AtomicReference<InputMode>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        val focus = LocalFocusManager.current
                        val input = LocalInputModeManager.current
                        SideEffect {
                            focusManager.set(focus)
                            inputManager.set(input)
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FocusRingProbe(requester, counters)
                                Box(
                                    Modifier
                                        .size(80.dp, 48.dp)
                                        .focusRequester(parkingRequester)
                                        .onFocusChanged { parkingFocused.set(it.isFocused) }
                                        .focusable()
                                        .background(Color.DarkGray),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("停车焦点", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            waitUntil("The focus target was not drawn") { counters.draws.get() > 0 && inputManager.get() != null }
            scenario.onActivity {
                originalInputMode.set(inputManager.get().inputMode)
                inputManager.get().requestInputMode(InputMode.Keyboard)
                parkingRequester.requestFocus()
            }
            waitUntil("The parking target did not receive initial focus") { parkingFocused.get() }
            settle()
            try {
                fun focus(value: Boolean) {
                    scenario.onActivity {
                        if (value) requester.requestFocus() else parkingRequester.requestFocus()
                    }
                    waitUntil("The target did not ${if (value) "gain" else "lose"} focus") {
                        counters.focused.get() == value && parkingFocused.get() != value
                    }
                }

                val compositions = counters.compositions.get()
                val draws = counters.draws.get()
                focus(true)
                settle()
                focus(false)
                settle()
                val animatedDraws = counters.draws.get() - draws
                val boundaryCompositions = counters.compositions.get() - compositions
                assertTrue("Focus transitions produced only $animatedDraws content draws", animatedDraws >= 6)
                assertTrue(
                    "Focus animation recomposed $boundaryCompositions times for $animatedDraws draws",
                    boundaryCompositions <= 8 && boundaryCompositions < animatedDraws,
                )

                // Reversing before the 120ms ring fade completes must still return to a quiet control.
                repeat(6) { index ->
                    focus(index % 2 == 0)
                    SystemClock.sleep(24)
                }
                focus(false)
                settle()
                assertQuiet(counters)

                focus(true)
                scenario.onActivity { reduced.value = true }
                settle()
                assertTrue("Changing motion policy stole focus", counters.focused.get())
                assertQuiet(counters)
                val reducedCompositions = counters.compositions.get()
                val reducedDraws = counters.draws.get()
                focus(false)
                settle()
                focus(true)
                settle()
                assertTrue("Reduced motion kept recomposing", counters.compositions.get() - reducedCompositions <= 8)
                assertTrue("Reduced motion kept redrawing", counters.draws.get() - reducedDraws <= 8)
                focus(false)
                settle()
                assertQuiet(counters)
            } finally {
                scenario.onActivity {
                    focusManager.get().clearFocus(force = true)
                    inputManager.get().requestInputMode(originalInputMode.get())
                }
            }
        }
    }

    private fun settle() {
        SystemClock.sleep(650)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertQuiet(counters: FocusRingCounters) {
        val compositions = counters.compositions.get()
        val draws = counters.draws.get()
        SystemClock.sleep(180)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("A settled focus target kept recomposing", compositions, counters.compositions.get())
        assertEquals("A settled focus target kept redrawing", draws, counters.draws.get())
    }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }
}

private class FocusRingCounters {
    val compositions = AtomicInteger()
    val draws = AtomicInteger()
    val focused = AtomicBoolean()
}

@Composable
private fun FocusRingProbe(
    requester: FocusRequester,
    counters: FocusRingCounters,
) {
    SideEffect { counters.compositions.incrementAndGet() }
    Box(
        Modifier
            .size(180.dp, 80.dp)
            .focusRequester(requester)
            .onFocusChanged { counters.focused.set(it.isFocused) }
            .focusProperties { canFocus = true }
            .pressable(focusShape = GlassShapes.chip, onClick = {})
            .drawWithContent {
                counters.draws.incrementAndGet()
                drawContent()
            }.background(Color.DarkGray, GlassShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        Text("键盘焦点测试", color = Color.White)
    }
}
