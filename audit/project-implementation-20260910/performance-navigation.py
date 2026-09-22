from pathlib import Path
p=Path('macrobenchmark/build.gradle.kts');s=p.read_text(encoding='utf-8').replace('android {','kotlin { jvmToolchain(17) }\n\nandroid {',1);p.write_text(s,encoding='utf-8')
p=Path('macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/NavigationJourneyBenchmark.kt');p.write_text('''package com.yfuse.macrobenchmark

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

    @Test fun searchAndTabTransitions() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome(); startProductionApp() },
        measureBlock = { navigateSearchJourney() },
    )
}

internal fun MacrobenchmarkScope.navigateSearchJourney() {
    repeat(3) {
        device.findObject(By.desc("搜索"))?.click() ?: error("Search tab missing")
        assertTrue("Production search field did not appear", device.wait(Until.hasObject(By.desc("搜索电影、剧集、演员")), 10_000))
        device.findObject(By.desc("我的"))?.click() ?: error("Profile tab missing")
        assertTrue("Search route stayed visible", device.wait(Until.gone(By.desc("搜索电影、剧集、演员")), 10_000))
        device.findObject(By.desc("首页"))?.click() ?: error("Home tab missing")
        device.waitForIdle()
    }
}
''',encoding='utf-8')
p=Path('macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/BaselineProfileGenerator.kt');s=p.read_text(encoding='utf-8');i=s.rfind('}');s=s[:i]+'''    @Test
    fun navigationJourney() = baselineProfileRule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
        startProductionApp()
        navigateSearchJourney()
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
p=Path('scripts/android_performance.py');s=p.read_text(encoding='utf-8').replace('    "HomeJourneyBenchmark": ("homeScrollFrames", "frameDurationCpuMs", "sampledMetrics"),','    "HomeJourneyBenchmark": ("homeScrollFrames", "frameDurationCpuMs", "sampledMetrics"),\n    "NavigationJourneyBenchmark": ("searchAndTabTransitions", "frameDurationCpuMs", "sampledMetrics"),');s=s.replace('"HomeJourney.kt", "HomeJourneyBenchmark.kt", "StartupBenchmark.kt",','"HomeJourney.kt", "HomeJourneyBenchmark.kt", "StartupBenchmark.kt", "NavigationJourneyBenchmark.kt",');p.write_text(s,encoding='utf-8')
p=Path('scripts/test_android_performance.py');s=p.read_text(encoding='utf-8').replace('''    ]}]
''','''        {"className": "com.yfuse.macrobenchmark.NavigationJourneyBenchmark", "name": "searchAndTabTransitions", "sampledMetrics": {
            "frameDurationCpuMs": {"runs": [[7, 9, 11]] * 5},
        }},
    ]}]
''',1);p.write_text(s,encoding='utf-8')
p=Path('audit/project-implementation-20260910/performance.ps1');s=p.read_text(encoding='utf-8').replace('/performance-baseline ', '/performance-expanded ');p.write_text(s,encoding='utf-8')
