from pathlib import Path
p=Path('macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/HomeJourney.kt');s=p.read_text(encoding='utf-8').replace('import androidx.test.uiautomator.Direction\n','');a=s.index('    val feed = device.findObject',s.index('internal fun MacrobenchmarkScope.scrollHomeJourney'));s=s[:a]+'''    val bounds = (device.findObject(By.res("home-feed")) ?: error("Production home list is missing")).visibleBounds
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
    repeat(2) { device.swipe(x, upper, x, lower, 120); device.waitForIdle() }
}
''';p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt');s=p.read_text(encoding='utf-8').replace('import androidx.compose.foundation.', 'import androidx.compose.foundation.',1)
if 'import androidx.compose.foundation.background\n' not in s: s=s.replace('package com.yfuse.feature.player\n','package com.yfuse.feature.player\n\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.layout.height\n')
p.write_text(s,encoding='utf-8')
