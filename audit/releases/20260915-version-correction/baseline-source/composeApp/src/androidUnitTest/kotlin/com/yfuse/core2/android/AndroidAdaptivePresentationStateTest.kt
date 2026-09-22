package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeBase
import com.yfuse.core2.subtitle.YSubtitleTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AndroidAdaptivePresentationStateTest {
    @Test
    fun `whole-title sidecar text and ASS keep their presentation clock in either channel`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 2_000L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val script = YAssSubtitleSource(byteArrayOf(), fullScript = true)
        val text =
            YSubtitleCue(
                "external-srt",
                61_000_000,
                63_000_000,
                YSubtitlePayload.Text("whole title"),
                timeBase = YSubtitleTimeBase.Presentation,
            )
        val ass =
            YSubtitleCue(
                "external-ass",
                61_000_000,
                64_000_000,
                YSubtitlePayload.AssEvent(script),
                timeBase = YSubtitleTimeBase.Presentation,
            )
        for ((primary, secondary) in listOf(text to ass, ass to text)) {
            val local =
                YPlayerState(
                    positionMs = 2_000,
                    subtitleCues = listOf(primary),
                    secondarySubtitleCues = listOf(secondary),
                )
            val mapped = mapAdaptivePresentationState(local, target)
            assertSame(primary, mapped.subtitleCues.single())
            assertSame(secondary, mapped.secondarySubtitleCues.single())
            assertEquals(primary.id, YSubtitleTimeline(mapped.subtitleCues).activeAt(62_000_000).single().id)
            assertEquals(secondary.id, YSubtitleTimeline(mapped.secondarySubtitleCues).activeAt(62_000_000).single().id)
            assertEquals(0L, mapped.subtitleCues.single().sourceTimeOffsetUs)
            assertEquals(0L, mapped.secondarySubtitleCues.single().sourceTimeOffsetUs)
        }
    }

    @Test
    fun `subtitle presentation translation is idempotent`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 2_000L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val cue = YSubtitleCue("embedded", 1_000_000, 3_000_000, YSubtitlePayload.Text("source"))
        val mapped = mapAdaptivePresentationState(YPlayerState(subtitleCues = listOf(cue)), target)
        assertEquals(YSubtitleTimeBase.Presentation, mapped.subtitleCues.single().timeBase)
        assertEquals(mapped.subtitleCues, mapAdaptivePresentationState(mapped, target).subtitleCues)
    }

    @Test
    fun `both subtitle channels follow the global period clock and preserve authored ASS time`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 2_000L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val source = YAssSubtitleSource(byteArrayOf(), fullScript = true)
        val local =
            YPlayerState(
                positionMs = 2_000L,
                subtitleCues = listOf(YSubtitleCue("text", 1_000_000, 3_000_000, YSubtitlePayload.Text("first"))),
                secondarySubtitleCues =
                    listOf(
                        YSubtitleCue("ass", 1_000_000, 4_000_000, YSubtitlePayload.AssEvent(source)),
                    ),
            )
        val mapped = mapAdaptivePresentationState(local, target)
        assertEquals("text", YSubtitleTimeline(mapped.subtitleCues).activeAt(62_000_000).single().id)
        assertEquals("ass", YSubtitleTimeline(mapped.secondarySubtitleCues).activeAt(62_000_000).single().id)
        assertEquals(60_000_000L, mapped.secondarySubtitleCues.single().sourceTimeOffsetUs)
        assertSame(source, (mapped.secondarySubtitleCues.single().payload as YSubtitlePayload.AssEvent).source)
        assertEquals(1_000_000L, local.subtitleCues.single().startUs)
    }

    @Test
    fun `open ended bitmap cues do not overflow when translated`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 0L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val cue = YSubtitleCue("open", 1_000_000, Long.MAX_VALUE, YSubtitlePayload.Text("open"))
        val mapped = mapAdaptivePresentationState(YPlayerState(subtitleCues = listOf(cue)), target)
        assertEquals(Long.MAX_VALUE, mapped.subtitleCues.single().endUs)
        assertEquals(61_000_000L, mapped.subtitleCues.single().startUs)
    }

    @Test
    fun `second period maps local progress and buffering to whole title`() {
        val target = YAdaptivePlaybackTarget("root", "period-two", 2_000L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val local = YPlayerState(positionMs = 2_000L, bufferedPositionMs = 8_000L, durationMs = 60_000L)
        val mapped = mapAdaptivePresentationState(local, target)
        assertEquals(62_000L, mapped.positionMs)
        assertEquals(68_000L, mapped.bufferedPositionMs)
        assertEquals(120_000L, mapped.durationMs)
        assertEquals(local.diagnostics, mapped.diagnostics)
    }

    @Test
    fun `unknown presentation duration uses period end and ordinary sources retain state`() {
        val local = YPlayerState(positionMs = 11_000L, durationMs = 10_000L)
        val target = YAdaptivePlaybackTarget("root", "period", 0L, 60_000L, 0L, 1L, null, 1L)
        assertEquals(70_000L, mapAdaptivePresentationState(local, target).positionMs)
        assertEquals(70_000L, mapAdaptivePresentationState(local, target).durationMs)
        assertSame(local, mapAdaptivePresentationState(local, null))
    }
}
