package com.yfuse.feature.profile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileAppearanceUiContractTest {
    @Test
    fun theme_and_visual_material_are_on_the_root_settings_page_only() {
        val profile = projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt").readText()
        val root = profile.substringAfter("ProfilePage.Root ->").substringBefore("offlineToPlay?.takeIf")
        val appearance =
            projectFile("src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt")
                .readText()
                .substringAfter("internal fun AppearanceSettingsScreen(")

        assertTrue("title = \"主题\"" in root)
        assertTrue("title = \"视觉效果\"" in root)
        assertTrue("ThemeModeDisplayOrder" in root)
        assertTrue("GlassStyle.entries" in root)
        assertFalse("title = \"主题\"" in appearance)
        assertFalse("title = \"视觉效果\"" in appearance)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(File(moduleRelativePath), File("composeApp", moduleRelativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
