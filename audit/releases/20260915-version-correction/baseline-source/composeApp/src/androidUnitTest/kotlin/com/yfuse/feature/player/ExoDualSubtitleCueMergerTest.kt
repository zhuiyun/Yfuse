package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@UnstableApi
class ExoDualSubtitleCueMergerTest {
    @Test fun dual_preserves_channels_and_suppresses_native_duplicate() {
        val merger = ExoDualSubtitleCueMerger()
        val output = mutableListOf<CueGroup>()
        val primary = merger.primaryOutput(TextOutput { output += it })
        val authored =
            Cue
                .Builder()
                .setText("主字幕\n第二行")
                .setLine(0.2f, Cue.LINE_TYPE_FRACTION)
                .build()
        primary.onCues(CueGroup(listOf(authored), 10L))
        assertEquals(authored, output.last().cues.single())
        merger.setDual(true)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("副字幕").build()), 20L))
        assertTrue(output.last().cues.isEmpty())
        assertEquals(
            authored,
            merger.channels.value.primary.cues
                .single(),
        )
        assertEquals(
            "副字幕",
            merger.channels.value.secondary.cues
                .single()
                .text
                .toString(),
        )
    }

    @Test fun clearing_cue_does_not_exit_dual_mode() {
        val merger = ExoDualSubtitleCueMerger()
        merger.setDual(true)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("旧字幕").build()), 10L))
        merger.clearSecondary()
        assertTrue(merger.channels.value.dual)
        assertTrue(
            merger.channels.value.secondary.cues
                .isEmpty(),
        )
    }

    @Test fun disabling_restores_primary_and_rejects_late_secondary_callback() {
        val merger = ExoDualSubtitleCueMerger()
        val output = mutableListOf<CueGroup>()
        merger
            .primaryOutput(
                TextOutput { output += it },
            ).onCues(CueGroup(listOf(Cue.Builder().setText("主字幕").build()), 10L))
        merger.setDual(true)
        merger.setDual(false)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("旧副字幕").build()), 10L))
        assertEquals(
            "主字幕",
            output
                .last()
                .cues
                .single()
                .text
                .toString(),
        )
        assertTrue(
            merger.channels.value.secondary.cues
                .isEmpty(),
        )
    }
}
