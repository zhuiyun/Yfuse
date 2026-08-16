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

    @Test
    fun aspect_ratio_key_does_not_borrow_the_fullscreen_brackets() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()

        assertTrue("AppIcons.AspectFit" in chrome && "AppIcons.AspectFill" in chrome)
        assertFalse(
            "AppIcons.Expand" in chrome || "AppIcons.Collapse" in chrome,
            "The four-corner brackets are the enter/exit-fullscreen mark; on 切换画面比例 " +
                "they answer a question nobody asked",
        )
    }

    @Test
    fun the_paused_centre_key_offers_the_action_rather_than_naming_the_state() {
        val controls = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt").readText()

        assertTrue(
            "AppIcons.Play" in controls,
            "Paused shows 播放 — a transport key says what the tap does",
        )
        assertFalse(
            "AppIcons.Pause" in controls,
            "A 暂停 glyph over a paused frame is a readout, and it used to be drawn " +
                "underneath the 播放 disc rather than instead of it",
        )
    }

    @Test
    fun only_one_control_occupies_the_centre_of_the_frame() {
        val controls = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt").readText()
        val centred = Regex("""Alignment\.Center\b(?!End|Start|Horizontally|Vertically)""")

        // The paused key and the gesture readout share this anchor, and the readout is
        // already suppressed while the key is up. A third would be a stack, not a layout.
        assertTrue(
            centred.findAll(controls).count() <= 2,
            "More than two things anchored to the centre of the frame will overlap",
        )
    }

    @Test
    fun the_function_strip_leads_with_the_keys_used_mid_scene() {
        val chrome = projectFile("src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt").readText()
        val strip = chrome.substringAfter("horizontalArrangement = Arrangement.spacedBy(5.dp)")
        fun at(marker: String) = strip.indexOf(marker).also { require(it >= 0) { "missing $marker" } }

        // 字幕 and 音轨 are changed while watching; 播放服务器 and 片头片尾 are set once and
        // left, and both are conditional — near the front they would shift every key behind
        // them between one episode and the next.
        assertTrue(at("\"字幕\"") < at("\"弹幕\""))
        assertTrue(at("\"音轨\"") < at("\"弹幕\""))
        assertTrue(at("\"投屏\"") < at("\"播放服务器\""))
        assertTrue(at("\"播放服务器\"") < at("\"标记片头片尾\""))
        assertTrue(at("\"标记片头片尾\"") < at("\"更多\""), "更多 is the overflow and stays last")
    }

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp/$moduleRelativePath"),
        ).first(File::exists)
}
