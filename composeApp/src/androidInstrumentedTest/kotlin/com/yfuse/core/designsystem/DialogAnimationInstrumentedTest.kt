package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DialogAnimationInstrumentedTest {
    @Test
    fun all_styles_dismiss_once_even_when_entrance_is_interrupted_or_motion_is_reduced() {
        val shown = mutableStateOf(false)
        val style = mutableStateOf(DialogAnimation.Lift)
        val reduced = mutableStateOf(false)
        val dismiss = AtomicReference<(() -> Unit)?>(null)
        val exits = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(
                        dark = true,
                        dialogAnimation = style.value,
                        accessibility = AccessibilityOptions(reduceMotion = reduced.value),
                    ) {
                        Text("弹窗动画测试")
                        if (shown.value) {
                            GlassDialog(onDismiss = {
                                exits.incrementAndGet()
                                shown.value = false
                            }) {
                                val close = overlayDismiss { error("Missing shared dismiss handler") }
                                SideEffect { dismiss.set(close) }
                                OverlayHeader(style.value.label, style.value.description)
                                Text("独立玻璃、标题、正文与扫描光线的开关验证")
                                OverlayButton("关闭", onClick = close)
                            }
                        }
                    }
                }
            }
            var expected = 0
            for (mode in 0..2) {
                for (animation in DialogAnimation.entries) {
                    dismiss.set(null)
                    scenario.onActivity {
                        reduced.value = mode == 2
                        style.value = animation
                        shown.value = true
                    }
                    waitUntil { dismiss.get() != null }
                    if (mode == 0) SystemClock.sleep(animation.enterMillis + 100L)
                    scenario.onActivity {
                        dismiss.get()!!.invoke()
                        dismiss.get()!!.invoke()
                    }
                    expected++
                    waitUntil { exits.get() == expected }
                    SystemClock.sleep(80)
                    assertEquals(expected, exits.get())
                }
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        assertTrue("Dialog did not complete its transition", condition())
    }
}
