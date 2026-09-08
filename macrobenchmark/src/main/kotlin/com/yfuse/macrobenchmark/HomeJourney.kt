package com.yfuse.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
    val feed = device.findObject(By.res("home-feed")) ?: error("Production home list is missing")
    feed.setGestureMargin(device.displayWidth / 5)
    var reachedThirdShelf = false
    repeat(6) {
        // Nested Compose shelves can report a child scroll boundary for this gesture.
        // Keep the journey bounded and validate the visible content, not that return value.
        feed.scroll(Direction.DOWN, 0.5f)
        if (device.hasObject(By.text("本地片架 3"))) reachedThirdShelf = true
    }
    assertTrue("Gestures never reached the third populated shelf", reachedThirdShelf)
    repeat(2) { feed.scroll(Direction.UP, 0.65f) }
}
