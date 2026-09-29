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
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 浮起菜单 on the home fixture: the lift, the blurred page under it, and the settle back. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiftMenuJourneyBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun liftMenuOpenClose() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Disable, warmupIterations = 3),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startHomeFixture()
                liftPosterInView()
            },
            measureBlock = { liftMenuJourney() },
        )
}

/**
 * The first poster of 本地片架 2. The hero rotates through the first eight fixture titles, so a
 * poster from the second shelf is never also the hero's.
 */
private const val LIFT_POSTER = "本地影片 12"

/** A row of the TMDB pick's menu, present only while the lift is up. */
private const val LIFT_MENU_ROW = "加入收藏"

private const val LIFT_TIMEOUT_MS = 5_000L

/** Scrolls the fixture's feed until [LIFT_POSTER] sits clear of both of its edges. */
internal fun MacrobenchmarkScope.liftPosterInView(): UiObject2 {
    val feed = (device.findObject(By.res("home-feed")) ?: error("Production home list is missing")).visibleBounds
    val x = feed.centerX()
    repeat(6) {
        device.findObject(By.desc(LIFT_POSTER))?.let { poster ->
            val bounds = poster.visibleBounds
            if (bounds.top > feed.top + feed.height() / 8 && bounds.bottom < feed.bottom - feed.height() / 8) {
                return poster
            }
        }
        device.swipe(x, feed.top + (feed.height() * 0.8f).toInt(), x, feed.top + (feed.height() * 0.45f).toInt(), 120)
        device.waitForIdle()
    }
    error("$LIFT_POSTER never came fully into view")
}

/** Ten lifts: long-press until the menu is up, then back until the poster has settled. */
internal fun MacrobenchmarkScope.liftMenuJourney() {
    repeat(10) {
        // Found again every time: the settle re-composes the tile and retires the old node.
        (device.findObject(By.desc(LIFT_POSTER)) ?: error("$LIFT_POSTER left the screen")).longClick()
        assertTrue("浮起菜单 did not open", device.wait(Until.hasObject(By.text(LIFT_MENU_ROW)), LIFT_TIMEOUT_MS))
        device.pressBack()
        assertTrue("浮起菜单 did not close", device.wait(Until.gone(By.text(LIFT_MENU_ROW)), LIFT_TIMEOUT_MS))
    }
}
