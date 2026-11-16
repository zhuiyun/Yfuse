package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SkeletonSweepLifecycleInstrumentedTest {
    @Test(timeout = 60_000L)
    fun loaded_and_hidden_content_stop_the_sweep_clock_and_idle_redraws() {
        val loading = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val draws = AtomicInteger()
        val readPhase = AtomicReference<() -> Float>({ -2f })
        launchMainActivityForInput().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = false, accessibility = AccessibilityOptions(reduceMotion = false)) {
                        CompositionLocalProvider(LocalRouteVisible provides visible.value) {
                            SkeletonPulseProvider {
                                val phase = rememberSkeletonSweepReader()
                                SideEffect { readPhase.set(phase) }
                                Box(
                                    Modifier.fillMaxSize().skeletonSweep(enabled = loading.value).drawWithContent {
                                        draws.incrementAndGet()
                                        drawContent()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            await("Loaded content leaves the clock stopped") { readPhase.get().invoke() == -1f }
            scenario.onActivity { loading.value = true }
            await("Loading activates the sweep") { readPhase.get().invoke() >= 0f }
            val loadingDraws = draws.get()
            await("Loading renders animated frames") { draws.get() > loadingDraws + 2 }
            scenario.onActivity { loading.value = false }
            await("Completing loading stops the clock") { readPhase.get().invoke() == -1f }
            SystemClock.sleep(300)
            val settledDraws = draws.get()
            SystemClock.sleep(1_000)
            assertEquals("Loaded content must not keep redrawing", settledDraws, draws.get())
            scenario.onActivity { loading.value = true }
            await("A later request restores the sweep") { readPhase.get().invoke() >= 0f }
            scenario.onActivity { visible.value = false }
            await("Hidden loading content does not animate") { readPhase.get().invoke() == -1f }
            scenario.onActivity { visible.value = true }
            await("Returning to an unfinished request restores loading feedback") { readPhase.get().invoke() >= 0f }
        }
    }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}
