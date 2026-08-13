package com.yfuse.feature.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSafeAreaContractTest {
    @Test
    fun root_settings_leave_the_dynamic_floating_dock_clearance() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt").readText()
        val root = source.substringAfter("ProfilePage.Root ->").substringBefore("offlineToPlay?.takeIf")

        assertTrue("rootBottomContentInset" in root)
        assertTrue("bottom = rootBottomContentInset" in root)
        assertFalse("bottom = TabBarInset" in root)
    }

    @Test
    fun pushed_settings_pages_do_not_reserve_a_hidden_floating_dock() {
        val source = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt").readText()
        val settingsPage =
            source
                .substringAfter("internal fun SettingsPage(")
                .substringBefore("private fun SettingsPageHeader(")

        assertTrue("systemNavigationContentInset()" in settingsPage)
        assertTrue("bottom = bottomContentInset" in settingsPage)
        assertFalse("bottom = TabBarInset" in settingsPage)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
