package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPopupRoutingContractTest {
    @Test
    fun playback_actions_open_single_purpose_popups() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()
        val controls = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt").readText()

        assertTrue("onOpenSubtitles" in chrome)
        assertTrue("onOpenAudio" in chrome)
        assertTrue("onOpenSkipSettings" in chrome)
        assertTrue("onOpenCast" in chrome)
        assertTrue("onOpenMore" in chrome)
        assertFalse("字幕与音轨" in chrome)
        assertFalse("onOpenTab" in chrome)

        assertTrue("TrackPanelMode.Subtitle" in controls)
        assertTrue("TrackPanelMode.Audio" in controls)
        assertTrue("SettingsPanelKind.Skip" in controls)
        assertFalse("onTab =" in controls)
        assertTrue(controls.split("modifier = functionPopupModifier").size - 1 == 3)
    }

    @Test
    fun skip_is_not_nested_in_more_and_time_uses_digit_roll_transition() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()
        val panel = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt").readText()

        assertTrue("RollingTimeText(shownPosition)" in chrome)
        assertTrue("player-time-digit-roll" in chrome)
        assertTrue("SettingsPanelKind.Skip ->" in panel)
        assertFalse("AdvancedPage.Skip" in panel)
        assertTrue("将当前时间标记为片头结束" in panel)
        assertTrue("将当前时间标记为片尾开始" in panel)
    }

    @Test
    fun function_popups_keep_the_picture_visible() {
        val panel = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerPanel.kt").readText()

        assertTrue("PlayerPopupWidth = 320.dp" in panel)
        assertTrue("PlayerPopupCompactMinHeight = 124.dp" in panel)
        assertTrue("PlayerPopupMaxHeight = 308.dp" in panel)
        assertTrue("copy(alpha = 0.86f)" in panel)
        assertFalse("PlayerPopupWidth = 378.dp" in panel)
        assertFalse("max = if (compact) 310.dp else 390.dp" in panel)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
