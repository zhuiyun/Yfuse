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

    @Test
    fun search_is_a_control_beside_the_bar_rather_than_a_cell_in_it() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/app/App.kt").readText()
        val tabList =
            source
                .substringAfter("private val tabs =")
                .substringBefore("val TabBarInset")

        // The pill is sized as equal cells and the indicator is positioned by index, so a
        // fifth entry here silently narrows every target and re-points the pill.
        assertFalse("Tab.Search" in tabList)
        assertTrue("Tab.Home" in tabList)
        assertTrue("Tab.Browse" in tabList)
        assertTrue("Tab.Servers" in tabList)
        assertTrue("Tab.Profile" in tabList)
        assertTrue("private fun SearchButton(" in source)
    }

    @Test
    fun the_bar_collapses_under_scroll_and_can_always_be_brought_back() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/app/App.kt").readText()
        val dock =
            source
                .substringAfter("private fun BottomNavigationDock(")
                .substringBefore("private fun CollapsedNavButton(")

        assertTrue("private fun rememberNavCollapseConnection(" in source)
        assertTrue(".nestedScroll(navScroll)" in source)
        // Collapsed, the only way back to the other three destinations is this button, so it
        // must stay in the tree rather than the row simply disappearing.
        assertTrue("CollapsedNavButton(" in dock)
        assertTrue("onExpand" in dock)
        // 搜索 is drawn outside the collapse branch: it is reachable in both states.
        val collapseBranch = dock.substringAfter("if (collapsed)").substringBefore("SearchButton(")
        assertTrue("GlassTabBar(" in collapseBranch)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
