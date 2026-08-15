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
        assertTrue("onOpenCast" in chrome)
        assertTrue("onOpenMore" in chrome)
        assertFalse("字幕与音轨" in chrome)
        assertFalse("onOpenTab" in chrome)

        assertTrue("TrackPanelMode.Subtitle" in controls)
        assertTrue("TrackPanelMode.Audio" in controls)
        assertFalse("onTab =" in controls)
        assertTrue(controls.split("modifier = functionPopupModifier").size - 1 == 3)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
