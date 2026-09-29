package com.yfuse.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 详情 opened from a grid poster and pulled back into it (跟手返回). Needs a signed-in server. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DetailJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun pullDownBack() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            setupBlock = {
                killProcess()
                openLibraryGrid()
            },
            measureBlock = { detailPullBackJourney() },
        )
}

/**
 * Five times: open the first poster's 详情, then pull the page down from near its top. The pull
 * commits past 0.3 of an extent 0.45 of the page high, so half the screen is well past it.
 */
internal fun MacrobenchmarkScope.detailPullBackJourney() {
    val x = device.displayWidth / 2
    repeat(5) {
        openFirstDetail()
        device.waitForIdle()
        device.swipe(x, (device.displayHeight * 0.3f).toInt(), x, (device.displayHeight * 0.8f).toInt(), 50)
        assertOnScreen("跟手返回 did not leave 详情", device.wait(Until.gone(DETAIL_PLAY), SERVER_CONTENT_TIMEOUT_MS))
        assertOnScreen(
            "跟手返回 did not land on the grid",
            device.wait(Until.hasObject(GRID_COUNT), SERVER_CONTENT_TIMEOUT_MS),
        )
        device.waitForIdle()
    }
}
