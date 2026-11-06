package com.yfuse.feature.player

import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleRendererPlacementTest {
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
