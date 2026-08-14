package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlin.test.Test
import kotlin.test.assertEquals

@UnstableApi
class ExoDualSubtitleCueMergerTest {
    @Test
    fun secondary_unpositioned_text_is_merged_above_primary() {
        val merger = ExoDualSubtitleCueMerger()
        val outputs = mutableListOf<CueGroup>()
        val primaryOutput = merger.primaryOutput(TextOutput { outputs += it })
        val secondaryOutput = merger.secondaryOutput()

        primaryOutput.onCues(CueGroup(listOf(Cue.Builder().setText("主字幕").build()), 100L))
        secondaryOutput.onCues(CueGroup(listOf(Cue.Builder().setText("Secondary").build()), 110L))

        val merged = outputs.last()
        assertEquals(listOf("主字幕", "Secondary"), merged.cues.map { it.text.toString() })
        assertEquals(Cue.DIMEN_UNSET, merged.cues[0].line)
        assertEquals(-3f, merged.cues[1].line)
        assertEquals(Cue.LINE_TYPE_NUMBER, merged.cues[1].lineType)
    }

    @Test
    fun authored_secondary_position_is_preserved() {
        val merger = ExoDualSubtitleCueMerger()
        val outputs = mutableListOf<CueGroup>()
        merger.primaryOutput(TextOutput { outputs += it })
        val secondaryOutput = merger.secondaryOutput()
        val authored =
            Cue
                .Builder()
                .setText("定位字幕")
                .setLine(0.2f, Cue.LINE_TYPE_FRACTION)
                .build()

        secondaryOutput.onCues(CueGroup(listOf(authored), 200L))

        assertEquals(
            0.2f,
            outputs
                .last()
                .cues
                .single()
                .line,
        )
        assertEquals(
            Cue.LINE_TYPE_FRACTION,
            outputs
                .last()
                .cues
                .single()
                .lineType,
        )
    }

    @Test
    fun clearing_secondary_restores_primary_only() {
        val merger = ExoDualSubtitleCueMerger()
        val outputs = mutableListOf<CueGroup>()
        val primaryOutput = merger.primaryOutput(TextOutput { outputs += it })
        val secondaryOutput = merger.secondaryOutput()
        primaryOutput.onCues(CueGroup(listOf(Cue.Builder().setText("主字幕").build()), 10L))
        secondaryOutput.onCues(CueGroup(listOf(Cue.Builder().setText("副字幕").build()), 10L))

        merger.clearSecondary()

        assertEquals(listOf("主字幕"), outputs.last().cues.map { it.text.toString() })
    }
}
