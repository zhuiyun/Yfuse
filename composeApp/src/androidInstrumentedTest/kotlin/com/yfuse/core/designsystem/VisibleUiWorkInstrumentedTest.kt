package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.launchMainActivityForInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VisibleUiWorkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 90_000L)
    fun retained_page_work_stops_in_background_and_restarts_once_when_visible() {
        val visible = mutableStateOf(false)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val ticks = AtomicInteger()
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                        VisibleUiEffect(Unit) {
                            starts.incrementAndGet()
                            try {
                                while (isActive) {
                                    ticks.incrementAndGet()
                                    delay(50)
                                }
                            } finally {
                                stops.incrementAndGet()
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            assertEquals("A retained hidden page must not start its job", 0, starts.get())
            scenario.onActivity { visible.value = true }
            await("Visible page starts") { ticks.get() >= 3 }
            scenario.moveToState(Lifecycle.State.CREATED)
            await("Background cancels the page job") { stops.get() == 1 }
            val backgroundTicks = ticks.get()
            SystemClock.sleep(500)
            assertEquals(backgroundTicks, ticks.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("Foreground refreshes immediately") { ticks.get() > backgroundTicks }
            assertEquals(2, starts.get())
            scenario.onActivity { visible.value = false }
            await("Hidden route cancels its job") { stops.get() == 2 }
            val hiddenTicks = ticks.get()
            SystemClock.sleep(500)
            assertEquals(hiddenTicks, ticks.get())
            scenario.onActivity { visible.value = true }
            await("Returning route starts one fresh job") { ticks.get() > hiddenTicks }
            assertEquals(3, starts.get())
        }
        await("Disposal cleans up the final page job") { stops.get() == 3 }
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(description)
    }
}
