package com.yfuse.core2.android

import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.invalidateOutputEvidence
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeBase
import com.yfuse.core2.subtitle.YSubtitleTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidAdaptivePresentationStateTest {
    /**
     * The router as the generated-DASH test drives it: every seek across a Period rebuilds the
     * child. An enhanced child counts its own resets from zero: two opens when it starts a Period
     * at its beginning, and open, initial seek and resume seek, twice, when it starts inside one.
     */
    private class Router {
        private val sequence = ChildOutputEvidenceSequence()
        var published = YPlayerState()
        val generations = mutableListOf<Long>()

        fun rebuild(resets: List<Long>): Any {
            val reason = YOutputEvidenceResetReason.DecoderReconfigured
            published = published.copy(diagnostics = published.diagnostics.invalidateOutputEvidence(reason))
            generations += published.diagnostics.outputEvidenceGeneration
            val child = Any()
            resets.forEach { publish(child, it, verified = false) }
            publish(child, resets.last(), verified = true)
            return child
        }

        fun publish(
            child: Any,
            generation: Long,
            verified: Boolean,
        ) {
            val diagnostics = YPlayerDiagnostics(outputEvidenceGeneration = generation, videoOutputVerified = verified)
            published = sequence.continued(child, YPlayerState(diagnostics = diagnostics), published)
            generations += published.diagnostics.outputEvidenceGeneration
        }
    }

    @Test
    fun `a seek back into a Period shows its picture under a newer output generation`() {
        val periodStart = listOf(0L, 1L, 2L)
        val insidePeriod = listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L)
        val router = Router()
        router.rebuild(periodStart)
        router.rebuild(insidePeriod)
        // What the test's freshVideo records before seeking from the second Period back to 8.8 s.
        val beforeSeek = router.published.diagnostics.outputEvidenceGeneration
        router.rebuild(insidePeriod)
        // The backward child resets exactly as often as the forward one did. Republished as-is,
        // its picture came out under the generation recorded before the seek (6 after 6), and the
        // seek looked as if it had produced nothing while the Period played out and the title ended.
        assertTrue(router.published.diagnostics.videoOutputVerified)
        assertTrue(router.published.diagnostics.outputEvidenceGeneration > beforeSeek)
        val beforeTransition = router.published.diagnostics.outputEvidenceGeneration
        router.rebuild(periodStart)
        assertTrue(router.published.diagnostics.outputEvidenceGeneration > beforeTransition)
        assertEquals(router.generations.sorted(), router.generations)
    }

    @Test
    fun `a child keeps its place in the sequence and never starts behind the player`() {
        val router = Router()
        val child = router.rebuild(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L))
        val beforeRecovery = router.published.diagnostics.outputEvidenceGeneration
        // An in-place recovery of the same child continues its own count, and its offset with it.
        router.publish(child, 7L, verified = false)
        assertEquals(beforeRecovery + 1L, router.published.diagnostics.outputEvidenceGeneration)
        router.publish(child, 7L, verified = true)
        val beforeRebuild = router.published.diagnostics.outputEvidenceGeneration
        // A replacement that renders before its first reset still reports output newer than the
        // rebuild that attached it, which itself is newer than the last picture of its predecessor.
        router.rebuild(listOf(0L))
        assertTrue(router.published.diagnostics.videoOutputVerified)
        assertTrue(router.published.diagnostics.outputEvidenceGeneration > beforeRebuild + 1L)
        assertEquals(router.generations.sorted(), router.generations)
    }

    @Test
    fun `output generations saturate instead of wrapping`() {
        val sequence = ChildOutputEvidenceSequence()
        val published =
            YPlayerState(diagnostics = YPlayerDiagnostics(outputEvidenceGeneration = Long.MAX_VALUE - 1L))
        val child = YPlayerState(diagnostics = YPlayerDiagnostics(outputEvidenceGeneration = 5L))
        assertEquals(Long.MAX_VALUE, sequence.continued(Any(), child, published).diagnostics.outputEvidenceGeneration)
    }

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

    @Test
    fun `a Period played to its end continues the title at its boundary`() {
        val first = YAdaptivePlaybackTarget("root", "period-one", 0L, 0L, 120_000L, 1L, 60_000L, 1L)
        val ended = YPlayerState(phase = YPlaybackPhase.Ended, positionMs = 60_000L, durationMs = 60_000L)
        assertEquals(60_000L, first.nextPeriodStartMs(ended))
        // The child reports its own clock; a last frame a little before the Period's end still ends it.
        assertEquals(60_000L, first.nextPeriodStartMs(ended.copy(positionMs = 58_000L)))
        assertNull(first.nextPeriodStartMs(ended.copy(phase = YPlaybackPhase.Ready)))
    }

    @Test
    fun `a Period that ended short is left to transport recovery`() {
        val first = YAdaptivePlaybackTarget("root", "period-one", 0L, 0L, 120_000L, 1L, 60_000L, 1L)
        // Crossing to the next Period from half-way would skip half a minute of this one.
        val cut = YPlayerState(phase = YPlaybackPhase.Ended, positionMs = 30_000L, durationMs = 60_000L)
        assertNull(first.nextPeriodStartMs(cut))
    }

    @Test
    fun `the last Period and a title of unknown length end where they end`() {
        val ended = YPlayerState(phase = YPlaybackPhase.Ended, positionMs = 60_000L, durationMs = 60_000L)
        val last = YAdaptivePlaybackTarget("root", "period-two", 0L, 60_000L, 120_000L, 2L, 120_000L, 3L)
        val unknownLength = YAdaptivePlaybackTarget("root", "period-one", 0L, 0L, 0L, 1L, 60_000L, 1L)
        val singlePeriod = YAdaptivePlaybackTarget("root", "variant", 0L, 0L, 60_000L, 1L, null, 1L)
        assertNull(last.nextPeriodStartMs(ended))
        assertNull(unknownLength.nextPeriodStartMs(ended))
        assertNull(singlePeriod.nextPeriodStartMs(ended))
    }
}
