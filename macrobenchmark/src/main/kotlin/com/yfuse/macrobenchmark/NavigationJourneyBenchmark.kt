package com.yfuse.macrobenchmark

import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

/**
 * 搜索 at the end of the open dock: its magnifier carries the description, not the key around it.
 * A dock collapsed under a scroll takes 搜索 into its one key, out of reach; this journey scrolls
 * no root page, so the dock stays open.
 */
private val SEARCH_KEY = By.desc("搜索")

/** The search page's field, described by its placeholder. */
private val SEARCH_FIELD = By.desc("搜索电影、剧集、演员")

private val KEYBOARD = By.Window.type(AccessibilityWindowInfo.TYPE_INPUT_METHOD)

private const val NAVIGATION_TIMEOUT_MS = 10_000L

internal fun MacrobenchmarkScope.navigateSearchJourney() {
    repeat(3) {
        awaitObject(SEARCH_KEY, NAVIGATION_TIMEOUT_MS, "Search tab missing").click()
        assertOnScreen(
            "Production search field did not appear",
            device.wait(Until.hasObject(SEARCH_FIELD), NAVIGATION_TIMEOUT_MS),
        )
        putKeyboardAway()
        awaitObject(dockTab("我的"), NAVIGATION_TIMEOUT_MS, "Profile tab missing").click()
        assertOnScreen("Search route stayed visible", device.wait(Until.gone(SEARCH_FIELD), NAVIGATION_TIMEOUT_MS))
        awaitObject(dockTab("首页"), NAVIGATION_TIMEOUT_MS, "Home tab missing").click()
        device.waitForIdle()
    }
}

/**
 * Puts away the keyboard the search field asks for as its page opens with nothing searched. It
 * rises over the dock, where a tap meant for 我的 lands on a key, so a person closes it first too.
 * It is given a moment to come up: the field is on screen before its page has settled and asked.
 * Where a hardware keyboard stands in for it, it never shows, and this only waits.
 */
private fun MacrobenchmarkScope.putKeyboardAway() {
    if (device.wait(Condition<UiDevice, Boolean> { it.hasWindow(KEYBOARD) }, NAVIGATION_TIMEOUT_MS) != true) return
    device.pressBack()
    assertOnScreen(
        "The keyboard did not close",
        device.wait(Condition<UiDevice, Boolean> { !it.hasWindow(KEYBOARD) }, NAVIGATION_TIMEOUT_MS) == true,
    )
    assertOnScreen("Closing the keyboard also left the search page", device.hasObject(SEARCH_FIELD))
}
