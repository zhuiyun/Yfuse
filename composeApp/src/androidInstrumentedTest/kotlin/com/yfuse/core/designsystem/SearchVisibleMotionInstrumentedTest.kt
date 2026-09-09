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
                SystemClock.sleep(150)
                val prefix = if (isDark) "dark" else "light"
                val off = capture(scenario, bounds.get(), "$prefix-off.png")
                scenario.onActivity { enabled.value = true }
                SystemClock.sleep(100)
                val early = capture(scenario, bounds.get(), "$prefix-early.png")
                SystemClock.sleep(170)
                val later = capture(scenario, bounds.get(), "$prefix-later.png")
                scenario.onActivity { reduced.value = true }
                SystemClock.sleep(120)
                val reducedFrame = capture(scenario, bounds.get(), "$prefix-reduced.png")
                scenario.onActivity {
                    enabled.value = false
                    reduced.value = false
                }
                SystemClock.sleep(120)
                val disabledAgain = capture(scenario, bounds.get(), "$prefix-disabled.png")
                try {
                    assertTrue("$prefix loading highlight is imperceptible", difference(off, early) > 0.025)
                    assertTrue("$prefix loading highlight does not move", difference(early, later) > 0.015)
                    assertTrue("Reduced motion left an animated decoration", difference(off, reducedFrame) < 0.005)
                    assertTrue("Disabling motion did not restore the field", difference(off, disabledAgain) < 0.005)
                } finally {
                    listOf(off, early, later, reducedFrame, disabledAgain).forEach(Bitmap::recycle)
                }
            }
        }
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
