package com.yfuse.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production navigation without server accounts; measures page transitions, not network search. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class NavigationJourneyBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun searchAndTabTransitions() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startProductionApp()
            },
            measureBlock = { navigateSearchJourney() },
        )
}

internal fun MacrobenchmarkScope.navigateSearchJourney() {
    repeat(3) {
        device.wait(Until.findObject(By.desc("搜索")), 10_000)?.click() ?: error("Search tab missing")
        assertTrue(
            "Production search field did not appear",
            device.wait(Until.hasObject(By.desc("搜索电影、剧集、演员")), 10_000),
        )
        device.wait(Until.findObject(By.desc("我的")), 10_000)?.click() ?: error("Profile tab missing")
        assertTrue("Search route stayed visible", device.wait(Until.gone(By.desc("搜索电影、剧集、演员")), 10_000))
        device.wait(Until.findObject(By.desc("首页")), 10_000)?.click() ?: error("Home tab missing")
        device.waitForIdle()
    }
}
