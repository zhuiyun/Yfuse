package com.yfuse.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Protects the most common post-start interaction from scroll and image-loading jank. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class HomeJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun homeScrollFrames() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.waitForIdle()
            },
            measureBlock = {
                repeat(3) {
                    device.swipe(
                        device.displayWidth / 2,
                        device.displayHeight * 3 / 4,
                        device.displayWidth / 2,
                        device.displayHeight / 4,
                        SWIPE_STEPS,
                    )
                    device.waitForIdle()
                }
            },
        )

    private companion object {
        const val TARGET_PACKAGE = "com.yfuse"
        const val SWIPE_STEPS = 18
    }
}
