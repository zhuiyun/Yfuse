package com.yfuse.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal const val TARGET_PACKAGE = BuildConfig.TARGET_PACKAGE
private const val READY_TIMEOUT_MS = 15_000L

internal fun MacrobenchmarkScope.startProductionApp() {
    check(TARGET_PACKAGE.endsWith(".benchmark"))
    startActivityAndWait(Intent().setClassName(TARGET_PACKAGE, "com.yfuse.MainActivity"))
    assertTrue("Production navigation never appeared", device.wait(Until.hasObject(By.desc("首页")), READY_TIMEOUT_MS))
}

internal fun MacrobenchmarkScope.startHomeFixture() {
    check(TARGET_PACKAGE.endsWith(".benchmark"))
    startActivityAndWait(
        Intent()
            .setClassName(TARGET_PACKAGE, "com.yfuse.performance.HomeFixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    assertTrue(
        "Local artwork did not decode or production home did not draw",
        device.wait(Until.hasObject(By.desc("home-fixture-v1-ready")), READY_TIMEOUT_MS),
    )
    assertNotNull("Production LazyColumn is missing", device.findObject(By.res("home-feed")))
}

/** Scroll the actual tagged LazyColumn and prove that later fixture shelves became visible. */
internal fun MacrobenchmarkScope.scrollHomeJourney() {
    val bounds = (device.findObject(By.res("home-feed")) ?: error("Production home list is missing")).visibleBounds
    assertTrue("Production home viewport is empty", bounds.width() > 0 && bounds.height() > 0)
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
    assertTrue("Gestures never reached the third populated shelf", reachedThirdShelf)
    repeat(2) {
        device.swipe(x, upper, x, lower, 120)
        device.waitForIdle()
    }
}
