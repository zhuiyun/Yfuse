package com.yfuse.core.designsystem

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ImageRevealInstrumentedTest {
    @Test
    fun loading_redraws_without_frame_recomposition_and_a_new_request_starts_hidden() {
        val input = mutableStateOf(RevealInput())
        val counters = RevealCounters()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            install(scenario, input, counters)
            assertFirstDraw(counters, 0, 0f)
            val loadedVersion = change(scenario, input) { it.copy(loaded = true) }
            waitUntil("The image never entered its fade") { counters.hasIntermediate(loadedVersion) }
            val compositions = counters.compositions.get()
            waitUntil("The image did not finish its fade") { counters.hasValue(loadedVersion, 1f) }
            assertEquals("Image progress recomposed its host", compositions, counters.compositions.get())
            assertTrue(
                "The reveal did not produce multiple real draws",
                counters.draws.count { it.version == loadedVersion } >= 3,
            )
            val newRequest = change(scenario, input) { it.copy(requestKey = "second-request", loaded = false) }
            assertFirstDraw(counters, newRequest, 0f)
        }
    }

    @Test
    fun policy_changes_bypass_the_current_fade_on_the_next_draw_and_loaded_images_do_not_replay() {
        val input = mutableStateOf(RevealInput())
        val counters = RevealCounters()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            install(scenario, input, counters)
            assertFirstDraw(counters, 0, 0f)

            fun loadNextRequest(key: String) {
                val pending = change(scenario, input) { it.copy(requestKey = key, loaded = false) }
                assertFirstDraw(counters, pending, 0f)
                val loading = change(scenario, input) { it.copy(loaded = true) }
                waitUntil("Request $key did not start animating") { counters.hasIntermediate(loading) }
            }

            loadNextRequest("hidden-policy")
            val hidden = change(scenario, input) { it.copy(visible = false) }
            assertFirstDraw(counters, hidden, 1f)
            assertStaysVisible(counters, hidden)
            val returned = change(scenario, input) { it.copy(visible = true) }
            assertFirstDraw(counters, returned, 1f)
            assertStaysVisible(counters, returned)

            loadNextRequest("reduced-policy")
            val reduced = change(scenario, input) { it.copy(reduced = true) }
            assertFirstDraw(counters, reduced, 1f)
            val motionRestored = change(scenario, input) { it.copy(reduced = false) }
            assertFirstDraw(counters, motionRestored, 1f)
            assertStaysVisible(counters, motionRestored)

            loadNextRequest("instant-policy")
            val instant = change(scenario, input) { it.copy(instant = true) }
            assertFirstDraw(counters, instant, 1f)
            val instantCleared = change(scenario, input) { it.copy(instant = false) }
            assertFirstDraw(counters, instantCleared, 1f)
            assertStaysVisible(counters, instantCleared)

            val disabled = change(scenario, input) { it.copy(enabled = false, loaded = false, requestKey = "disabled") }
            assertFirstDraw(counters, disabled, 1f)
        }
    }

    private fun install(
        scenario: ActivityScenario<MainActivity>,
        input: MutableState<RevealInput>,
        counters: RevealCounters,
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                val current = input.value
                YfuseTheme(dark = true, accessibility = AccessibilityOptions(reduceMotion = current.reduced)) {
                    CompositionLocalProvider(LocalRouteVisible provides current.visible) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            RevealProbe(current, counters)
                        }
                    }
                }
            }
        }
    }

    private fun change(
        scenario: ActivityScenario<MainActivity>,
        input: MutableState<RevealInput>,
        update: (RevealInput) -> RevealInput,
    ): Int {
        var version = 0
        scenario.onActivity {
            version = input.value.version + 1
            input.value = update(input.value).copy(version = version)
        }
        return version
    }

    private fun assertFirstDraw(
        counters: RevealCounters,
        version: Int,
        expected: Float,
    ) {
        waitUntil("Image policy $version did not draw") { counters.draws.any { it.version == version } }
        assertEquals(
            "The first draw after policy $version used the previous animation value",
            expected,
            counters.draws.first { it.version == version }.value,
            0f,
        )
    }

    private fun assertStaysVisible(
        counters: RevealCounters,
        version: Int,
    ) {
        // The first draw was checked above; finish its traversal before measuring idle output.
        SystemClock.sleep(50)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val compositions = counters.compositions.get()
        val draws = counters.draws.size
        SystemClock.sleep(180)
        assertTrue(
            "A settled image replayed its entrance",
            counters.draws.filter { it.version == version }.all {
                it.value ==
                    1f
            },
        )
        assertEquals("A bypassed image kept recomposing", compositions, counters.compositions.get())
        assertEquals("A bypassed image kept redrawing", draws, counters.draws.size)
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

private data class RevealInput(
    val version: Int = 0,
    val requestKey: String = "initial-request",
    val loaded: Boolean = false,
    val instant: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val reduced: Boolean = false,
)

private data class RevealDraw(
    val version: Int,
    val value: Float,
)

private class RevealCounters {
    val compositions = AtomicInteger()
    val draws = ConcurrentLinkedQueue<RevealDraw>()

    fun hasIntermediate(version: Int): Boolean = draws.any { it.version == version && it.value > 0f && it.value < 1f }

    fun hasValue(
        version: Int,
        value: Float,
    ): Boolean = draws.any { it.version == version && it.value == value }
}

@Composable
private fun RevealProbe(
    input: RevealInput,
    counters: RevealCounters,
) {
    val progress =
        rememberImageRevealProgress(
            requestKey = input.requestKey,
            loaded = input.loaded,
            instant = input.instant,
            enabled = input.enabled,
            durationMillis = 480,
        )
    SideEffect { counters.compositions.incrementAndGet() }
    Box(
        Modifier.size(96.dp).drawBehind {
            val value = progress.value
            counters.draws.add(RevealDraw(input.version, value))
            drawRect(Color.White, alpha = value)
        },
    )
}
