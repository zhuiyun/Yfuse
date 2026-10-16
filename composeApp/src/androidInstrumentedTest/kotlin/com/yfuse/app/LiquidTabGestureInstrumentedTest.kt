package com.yfuse.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.designsystem.LocalPulseSweepEnabled
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LiquidTabGestureInstrumentedTest {
    @Test(timeout = 45_000L)
    fun drag_commits_once_on_release_cancel_rolls_back_and_switch_restores_taps() {
        // Browse and Watching are the first two persistent cells; Home is a discovery route.
        val active = mutableStateOf(Tab.Browse)
        val enabled = mutableStateOf(true)
        val selections = AtomicInteger()
        val bounds = AtomicReference(Rect.Zero)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The tab bar below can be laid out before MainActivity's window is on screen, and the
        // first drag was once injected into the window still in front of it.
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = false) {
                        CompositionLocalProvider(LocalPulseSweepEnabled provides enabled.value) {
                            Box(Modifier.padding(top = 100.dp, start = 16.dp)) {
                                GlassTabBar(
                                    active = active.value,
                                    onSelect = {
                                        active.value = it
                                        selections.incrementAndGet()
                                    },
                                    backdrop = rememberBackdropState(),
                                    modifier =
                                        Modifier
                                            .width(
                                                320.dp,
                                            ).onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                                )
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (bounds.get().width == 0f && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue(bounds.get().width > 0f)

            fun cell(index: Int): Offset =
                bounds.get().let { Offset(it.left + it.width * (index + 0.5f) / 4f, it.center.y) }

            var down = SystemClock.uptimeMillis()
            send(scenario, MotionEvent.ACTION_DOWN, cell(0), down)
            move(scenario, cell(0), cell(3), down)
            assertEquals("Dragging must not navigate before release", 0, selections.get())
            send(scenario, MotionEvent.ACTION_UP, cell(3), down)
            instrumentation.waitForIdleSync()
            assertEquals(Tab.Profile, active.value)
            assertEquals("A drag also triggered a child click", 1, selections.get())

            down = SystemClock.uptimeMillis()
            send(scenario, MotionEvent.ACTION_DOWN, cell(3), down)
            move(scenario, cell(3), cell(0), down)
            send(scenario, MotionEvent.ACTION_CANCEL, cell(0), down)
            instrumentation.waitForIdleSync()
            assertEquals(Tab.Profile, active.value)
            assertEquals(1, selections.get())

            scenario.onActivity { enabled.value = false }
            instrumentation.waitForIdleSync()
            down = SystemClock.uptimeMillis()
            send(scenario, MotionEvent.ACTION_DOWN, cell(3), down)
            move(scenario, cell(3), cell(0), down)
            send(scenario, MotionEvent.ACTION_UP, cell(0), down)
            instrumentation.waitForIdleSync()
            assertEquals("Disabled motion retained its drag handler", 1, selections.get())

            down = SystemClock.uptimeMillis()
            send(scenario, MotionEvent.ACTION_DOWN, cell(1), down)
            send(scenario, MotionEvent.ACTION_UP, cell(1), down)
            instrumentation.waitForIdleSync()
            assertEquals(Tab.Watching, active.value)
            assertEquals(2, selections.get())
        }
    }

    private fun move(
        scenario: ActivityScenario<MainActivity>,
        from: Offset,
        to: Offset,
        down: Long,
    ) {
        for (step in 1..12) {
            val fraction = step / 12f
            send(scenario, MotionEvent.ACTION_MOVE, from + (to - from) * fraction, down)
            SystemClock.sleep(16)
        }
    }

    private fun send(
        scenario: ActivityScenario<MainActivity>,
        action: Int,
        position: Offset,
        down: Long,
    ) {
        // Test component gestures without the emulator Taskbar intercepting window-targeted input.
        // Dedicated system-navigation tests continue to inject through the real OS dispatcher.
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val origin = IntArray(2)
            decor.getLocationInWindow(origin)
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    position.x - origin[0],
                    position.y - origin[1],
                    0,
                )
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                decor.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
