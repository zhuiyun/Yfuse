package com.yfuse.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.Direction
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 库's poster grid: 捏合换密度 and a fast fling through a library. Needs a signed-in server. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LibraryGridJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun pinchDensity() = measureGrid { pinchGridDensityJourney() }

    @Test
    fun fastFling() = measureGrid { flingGridJourney() }

    private fun measureGrid(journey: MacrobenchmarkScope.() -> Unit) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            // Each iteration starts a fresh process: the grid is only reached through a server load.
            setupBlock = {
                killProcess()
                openLibraryGrid()
            },
            measureBlock = journey,
        )
}

/** Share of the grid the fingers cross: enough to reach either end of the 2..5 column range. */
private const val PINCH_PERCENT = 0.8f

/** Well above the default 7500 px/s: the page and its images have to keep up with the list. */
private const val FAST_FLING_PX_PER_SECOND = 12_000

/** Spread to 2 columns, pinch to 5, spread back to 2; more posters show at 5 than at 2. */
internal fun MacrobenchmarkScope.pinchGridDensityJourney() {
    (libraryGrid() ?: error("Library grid missing")).pinchOpen(PINCH_PERCENT)
    device.waitForIdle()
    val atTwo = libraryGrid()?.childCount ?: error("Library grid missing after the spread")
    (libraryGrid() ?: error("Library grid missing")).pinchClose(PINCH_PERCENT)
    device.waitForIdle()
    val atFive = libraryGrid()?.childCount ?: error("Library grid missing after the pinch")
    assertTrue("Pinching did not make the grid denser ($atTwo -> $atFive posters)", atFive > atTwo)
    (libraryGrid() ?: error("Library grid missing")).pinchOpen(PINCH_PERCENT)
    device.waitForIdle()
}

/** Four flings down the library and four back up. */
internal fun MacrobenchmarkScope.flingGridJourney() {
    repeat(4) {
        (libraryGrid() ?: error("Library grid missing")).fling(Direction.DOWN, FAST_FLING_PX_PER_SECOND)
        device.waitForIdle()
    }
    repeat(4) {
        (libraryGrid() ?: error("Library grid missing")).fling(Direction.UP, FAST_FLING_PX_PER_SECOND)
        device.waitForIdle()
    }
}
