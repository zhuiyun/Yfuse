package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
class SubtitleRendererPlacementTest {
    @Test
    fun normal_webvtt_bottom_line_numbers_are_not_mistaken_for_positioned_signs() {
        val cue = webvttCue("Dialog", -1f)
        assertTrue(exoCanPlaceInBlackBar(cue))
        assertFalse(exoCanPlaceInBlackBar(cue.buildUpon().setLine(2f, Cue.LINE_TYPE_NUMBER).build()))
        assertFalse(exoCanPlaceInBlackBar(cue.buildUpon().setSize(0.5f).build()))
    }

    @Test
    fun overlapping_webvtt_lines_keep_their_top_to_bottom_reading_order() {
        val lowest = webvttCue("Bottom", -1f)
        val upper = webvttCue("Top", -2f)
        assertEquals(listOf(upper, lowest), exoBottomCueOrder(listOf(lowest, upper)))
    }

    private fun webvttCue(
        text: String,
        line: Float,
    ): Cue =
        Cue
            .Builder()
            .setText(text)
            .setLine(line, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setSize(1f)
            .build()

    @Test
    fun plain_media3_captions_can_use_the_shared_measured_overlay() {
        assertTrue(exoCanPlaceInBlackBar(Cue.Builder().setText("First line\nSecond line").build()))
    }

    @Test
    fun explicit_positions_and_vertical_webvtt_keep_their_authored_renderer() {
        assertFalse(
            exoCanPlaceInBlackBar(
                Cue
                    .Builder()
                    .setText("Sign")
                    .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
                    .build(),
            ),
        )
        assertFalse(
            exoCanPlaceInBlackBar(
                Cue
                    .Builder()
                    .setText("Sign")
                    .setPosition(0.7f)
                    .build(),
            ),
        )
        assertFalse(
            exoCanPlaceInBlackBar(
                Cue
                    .Builder()
                    .setText("縦")
                    .setVerticalType(Cue.VERTICAL_TYPE_RL)
                    .build(),
            ),
        )
    }

    @Test
    fun ordinary_ass_dialogue_keeps_style_but_may_move_as_a_bitmap_block() {
        assertFalse(core2HasAuthoredSubtitlePosition(listOf(cue("{\\b1}Dialogue", packet = true))))
    }

    @Test
    fun signs_motion_clipping_and_transforms_are_not_relocated() {
        for (tag in listOf("\\pos(10,20)", "\\move(0,0,10,10)", "\\clip(0,0,20,20)", "\\t(0,500,\\fs40)")) {
            assertTrue(core2HasAuthoredSubtitlePosition(listOf(cue("{$tag}Sign", packet = true))))
            assertTrue(core2HasAuthoredSubtitlePosition(listOf(cue("{$tag}Sign", packet = false))))
        }
    }

    private fun cue(
        text: String,
        packet: Boolean,
    ): YSubtitleCue {
        val source = YAssSubtitleSource(if (packet) byteArrayOf() else text.encodeToByteArray(), fullScript = !packet)
        return YSubtitleCue(
            "cue",
            0,
            1_000_000,
            YSubtitlePayload.AssEvent(source, text.encodeToByteArray().takeIf { packet }),
        )
    }
}
