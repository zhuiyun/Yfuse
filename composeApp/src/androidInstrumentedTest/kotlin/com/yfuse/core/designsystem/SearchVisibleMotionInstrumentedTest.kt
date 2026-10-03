package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.MainActivity
import com.yfuse.feature.search.SearchField
import com.yfuse.feature.search.SearchResultsPhase
import com.yfuse.feature.search.rememberSearchResultsHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SearchVisibleMotionInstrumentedTest {
    @Test(timeout = 45_000)
    fun real_search_field_has_visible_motion_in_both_themes_and_switches_remove_it() {
        val enabled = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val bounds = AtomicReference(Rect.Zero)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    YfuseTheme(dark = dark.value, accessibility = AccessibilityOptions(reduceMotion = reduced.value)) {
                        CompositionLocalProvider(LocalPulseSweepEnabled provides enabled.value) {
                            val motion = rememberSearchResultsHandoff(SearchResultsPhase.Results, loading = true)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        if (dark.value) Color.Black else Color.White,
                                    ).padding(top = 100.dp),
                            ) {
                                Box(Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                                    SearchField(
                                        query = "沙丘",
                                        onQueryChange = {},
                                        onSubmit = {},
                                        onClear = {},
                                        focusRequester = remember { FocusRequester() },
                                        motion = motion.field,
                                        iconMotion = motion.icon,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (bounds.get().width == 0f && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            for (isDark in listOf(false, true)) {
                scenario.onActivity {
                    dark.value = isDark
                    enabled.value = false
                    reduced.value = false
                }
                val prefix = if (isDark) "dark" else "light"
                // Every switch below is judged against this still field, so it has to be the field
                // after the theme switch, not during it: each glass plate crossfades its fill and
                // edge for Motion.THEME_CROSSFADE. Taken 150 ms into the dark crossfade, it still
                // held part of the light plate, no settled frame could match it, and 减少动态效果
                // failed for a highlight that was not drawn.
                val off = captureStill(scenario, bounds.get(), "$prefix-off.png", Motion.THEME_CROSSFADE.toLong())
                scenario.onActivity { enabled.value = true }
                SystemClock.sleep(100)
                val early = capture(scenario, bounds.get(), "$prefix-early.png")
                SystemClock.sleep(170)
                // A capture shows the frame the window queued last, and the pulse's clock starts a
                // frame after the highlight first appears. The sleeps assume frames every 16 ms; on
                // the software-rendered CI emulator both captures once showed the highlight in one
                // place. Later frames are sampled for a full pulse instead; a highlight that does
                // not move never differs.
                val later =
                    captureDiffering(scenario, bounds.get(), "$prefix-later.png", early, 2L * Motion.WAIT_HALF_CYCLE)
                // Switching the motion off meets the same frame lag: on a head that changed no motion
                // code, a capture 120 ms after 减少动态效果 still showed the highlight. Each switch is
                // sampled until the field is still, then for a full pulse, and judged by the frame
                // that differs most, so a highlight that comes back fails just as one that stayed.
                scenario.onActivity { reduced.value = true }
                val reducedFrame = captureSettled(scenario, bounds.get(), "$prefix-reduced.png", off, PULSE_MS)
                scenario.onActivity {
                    enabled.value = false
                    reduced.value = false
                }
                val disabledAgain = captureSettled(scenario, bounds.get(), "$prefix-disabled.png", off, PULSE_MS)
                try {
                    assertTrue("$prefix loading highlight is imperceptible", difference(off, early) > 0.025)
                    assertTrue("$prefix loading highlight does not move", difference(early, later) > MOVED)
                    assertTrue("Reduced motion left an animated decoration", difference(off, reducedFrame) < STILL)
                    assertTrue("Disabling motion did not restore the field", difference(off, disabledAgain) < STILL)
                } finally {
                    listOf(off, early, later, reducedFrame, disabledAgain).forEach(Bitmap::recycle)
                }
            }
        }
    }

    /**
     * Waits [motionMs] for the motion a switch starts, then captures until two frames in a row
     * match within [STILL], for up to [motionMs] more, and returns the later one. A field that
     * is still moving by then fails here rather than as a decoration one of the switches left.
     */
    private fun captureStill(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
        name: String,
        motionMs: Long,
    ): Bitmap {
        SystemClock.sleep(motionMs)
        val deadline = SystemClock.uptimeMillis() + motionMs
        var previous = capture(scenario, bounds, name)
        while (true) {
            SystemClock.sleep(50)
            val frame = capture(scenario, bounds, name)
            val still = difference(previous, frame) < STILL
            previous.recycle()
            if (still) return frame
            assertTrue("$name never settled with every motion off", SystemClock.uptimeMillis() < deadline)
            previous = frame
        }
    }

    /** Captures until a frame differs from [reference] as a moved highlight does, for up to [withinMs]. */
    private fun captureDiffering(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
        name: String,
        reference: Bitmap,
        withinMs: Long,
    ): Bitmap {
        val deadline = SystemClock.uptimeMillis() + withinMs
        while (true) {
            val frame = capture(scenario, bounds, name)
            if (difference(reference, frame) > MOVED || SystemClock.uptimeMillis() >= deadline) return frame
            frame.recycle()
            SystemClock.sleep(50)
        }
    }

    /**
     * Captures until a frame matches [still] within [STILL], for up to [holdMs], then keeps capturing
     * for another [holdMs] and returns the frame that differs from [still] the most. A decoration
     * that stopped stays gone for the whole second window; one that was only between two sweeps
     * shows again and is what comes back.
     */
    private fun captureSettled(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
        name: String,
        still: Bitmap,
        holdMs: Long,
    ): Bitmap {
        val settleBy = SystemClock.uptimeMillis() + holdMs
        var worst = capture(scenario, bounds, name)
        var worstDifference = difference(still, worst)
        while (worstDifference >= STILL) {
            if (SystemClock.uptimeMillis() >= settleBy) return worst
            worst.recycle()
            SystemClock.sleep(50)
            worst = capture(scenario, bounds, name)
            worstDifference = difference(still, worst)
        }
        val holdUntil = SystemClock.uptimeMillis() + holdMs
        while (SystemClock.uptimeMillis() < holdUntil) {
            SystemClock.sleep(50)
            val frame = capture(scenario, bounds, name)
            val frameDifference = difference(still, frame)
            if (frameDifference > worstDifference) {
                worst.recycle()
                worst = frame
                worstDifference = frameDifference
            } else {
                frame.recycle()
            }
        }
        return worst
    }

    private fun difference(
        a: Bitmap,
        b: Bitmap,
    ): Double {
        var changed = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val left = a.getPixel(x, y)
                val right = b.getPixel(x, y)
                if (abs(android.graphics.Color.red(left) - android.graphics.Color.red(right)) > 10 ||
                    abs(android.graphics.Color.blue(left) - android.graphics.Color.blue(right)) > 10
                ) {
                    changed++
                }
            }
        }
        return changed.toDouble() / (a.width * a.height)
    }

    private fun capture(
        scenario: ActivityScenario<MainActivity>,
        bounds: Rect,
        name: String,
    ): Bitmap {
        val rect =
            android.graphics.Rect(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            )
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        scenario.onActivity { activity ->
            PixelCopy.request(activity.window, rect, bitmap, {
                result = it
                done.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
}

/** Share of the field's pixels that must change between two positions of the loading highlight. */
private const val MOVED = 0.015

/** Share of the field's pixels that may differ from the still field once no decoration moves. */
private const val STILL = 0.005

/** One full loading pulse: out and back. */
private const val PULSE_MS = 2L * Motion.WAIT_HALF_CYCLE
