package com.yfuse.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = BuildConfig.TARGET_PACKAGE
private const val READY_TIMEOUT_MS = 15_000L

/**
 * The caption of a tab in the production dock; tapping it taps the tab around it.
 *
 * What UiAutomator reads of a tab is not one node. Compose hands a merged node's text to the node
 * that draws it, so a tab is a node without text whose one child, a TextView, carries the caption;
 * and a Tab reports itself clickable only while it is not the selected one. Neither the glyph's old
 * content description nor clickable text with the caption finds the dock, which is why every
 * journey on the production app failed at its first step.
 *
 * The caption is known to be the dock's by its row: its tab sits beside the tab captioned 我的 (首页
 * for 我的 itself). A page title or a setting with the same words has no such row around it. The
 * captions are there while the dock is open; a scroll on a root page collapses it into one key,
 * so no journey looks for a tab after scrolling one.
 */
internal fun dockTab(label: String): BySelector {
    val row = By.hasChild(By.hasChild(By.text(if (label == "我的") "首页" else "我的")))
    return By.text(label).hasParent(By.hasParent(row))
}

/**
 * MainActivity of the benchmark package, once its dock is on screen.
 *
 * Nothing of the app can be read before the launch splash has gone. Where no server is saved —
 * CI's emulator — a cold start opens on 服务器 rather than 首页 (see RootComponent.startupTab); with
 * one it opens on 库. The dock is there either way, and any of its captions says so.
 */
internal fun MacrobenchmarkScope.startProductionApp() {
    check(TARGET_PACKAGE.endsWith(".benchmark"))
    startActivityAndWait(Intent().setClassName(TARGET_PACKAGE, "com.yfuse.MainActivity"))
    assertOnScreen(
        "Production navigation never appeared",
        device.wait(Until.hasObject(dockTab("首页")), READY_TIMEOUT_MS),
    )
}

internal fun MacrobenchmarkScope.startHomeFixture() {
    check(TARGET_PACKAGE.endsWith(".benchmark"))
    startActivityAndWait(
        Intent()
            .setClassName(TARGET_PACKAGE, "com.yfuse.performance.HomeFixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    assertOnScreen(
        "Local artwork did not decode or production home did not draw",
        device.wait(Until.hasObject(By.desc("home-fixture-v1-ready")), READY_TIMEOUT_MS),
    )
    assertOnScreen("Production LazyColumn is missing", device.hasObject(By.res("home-feed")))
}

/** Scroll the actual tagged LazyColumn and prove that later fixture shelves became visible. */
internal fun MacrobenchmarkScope.scrollHomeJourney() {
    val feed = device.findObject(By.res("home-feed")) ?: failOnScreen("Production home list is missing")
    val bounds = feed.visibleBounds
    assertOnScreen("Production home viewport is empty", bounds.width() > 0 && bounds.height() > 0)
    val x = bounds.centerX()
    val lower = bounds.top + (bounds.height() * 0.8f).toInt()
    val upper = bounds.top + (bounds.height() * 0.35f).toInt()
    var reachedThirdShelf = false
    repeat(6) {
        // Compose rebuilds accessibility nodes during image/colour transitions. The viewport
        // remains fixed, so inject into its captured bounds instead of retaining a stale node.
        device.swipe(x, lower, x, upper, 120)
        device.waitForIdle()
        if (device.hasObject(By.text("本地片架 3"))) reachedThirdShelf = true
    }
    assertOnScreen("Gestures never reached the third populated shelf", reachedThirdShelf)
    repeat(2) {
        device.swipe(x, upper, x, lower, 120)
        device.waitForIdle()
    }
}
