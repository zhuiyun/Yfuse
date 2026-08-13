package com.yfuse.feature.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySafeAreaContractTest {
    @Test
    fun root_content_uses_dynamic_dock_clearance_and_clips_below_the_status_bar() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt").readText()

        assertTrue("floatingNavigationContentInset()" in source)
        assertTrue(".windowInsetsTopHeight(WindowInsets.statusBars)" in source)
        assertTrue("if (lightPageReached)" in source)
        assertFalse("PaddingValues(bottom = TabBarInset)" in source)
    }

    @Test
    fun pushed_grid_reserves_only_dynamic_system_navigation_clearance() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/library/LibraryGridScreen.kt").readText()

        assertTrue("systemNavigationContentInset()" in source)
        assertTrue("bottom = bottomContentInset" in source)
        assertFalse("bottom = Dimens.contentBottom" in source)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
