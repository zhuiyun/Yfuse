package com.yfuse.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellLayoutContractTest {

    @Test
    fun compact_tab_bar_has_a_bounded_height() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/app/App.kt").readText()
        val tabBar = source.substringAfter("private fun GlassTabBar(")
            .substringBefore("private fun RowScope.TabButton(")
        val tabButton = source.substringAfter("private fun RowScope.TabButton(")
            .substringBefore("private fun GlassNavigationRail(")

        assertTrue(".height(Dimens.tabBarHeight)" in tabBar)
        assertFalse(".heightIn(min = Dimens.tabBarHeight)" in tabBar)
        assertTrue("LiquidGlassTabIcon(" in tabButton)
        assertTrue("contentDescription = item.label" in source)
        assertFalse("Text(\n            item.label" in tabButton)
    }

    private fun projectFile(moduleRelativePath: String): File = sequenceOf(
        File(moduleRelativePath),
        File("composeApp", moduleRelativePath),
    ).firstOrNull(File::isFile)
        ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
