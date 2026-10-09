package com.yfuse.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures frame time while the real overlay renders a crowded, moving timeline. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DanmakuJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun denseDanmakuFrames() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(
                    Intent()
                        .setClassName(TARGET_PACKAGE, "com.yfuse.performance.DanmakuFixtureActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                check(device.wait(Until.hasObject(By.desc("danmaku-fixture-v1-ready")), 15_000L)) {
                    "Dense danmaku fixture never became ready"
                }
            },
            measureBlock = {
                Thread.sleep(8_000L)
            },
        )
}
