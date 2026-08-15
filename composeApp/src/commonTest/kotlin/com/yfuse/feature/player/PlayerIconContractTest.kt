package com.yfuse.feature.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerIconContractTest {
    @Test
    fun transport_has_distinct_seek_controls_with_one_visual_size() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()

        assertTrue("AppIcons.SeekBackward10" in chrome)
        assertTrue("AppIcons.SeekForward10" in chrome)
        assertTrue("private val TransportKeySize = 28.dp" in chrome)
        assertTrue("private val TransportIconSize = 14.dp" in chrome)
        assertFalse("private val PlayKeySize" in chrome)
    }

    @Test
    fun player_actions_use_semantic_icons_instead_of_generic_substitutes() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()

        assertTrue("AppIcons.EpisodeList" in chrome)
        assertTrue("AppIcons.PlaybackSource" in chrome)
        assertTrue("AppIcons.AudioTrack" in chrome)
        assertTrue("AppIcons.SkipMarkers" in chrome)
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp/$moduleRelativePath"),
        ).first(File::exists)
}
