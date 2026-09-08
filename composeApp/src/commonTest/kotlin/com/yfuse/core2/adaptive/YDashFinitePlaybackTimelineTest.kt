package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YDashFinitePlaybackTimelineTest {
    @Test
    fun an_exact_single_segment_period_has_one_timeline_entry_and_keeps_its_authored_number() {
        val template = fixedTemplate().copy(startNumber = 7L)
        val rendered = render(template, 10_000_000L)
        val parsed = parseYDashManifest(rendered, "https://fixture.test/movie.mpd")
        val output = parsed.representations.single().segmentTemplate!!
        assertNull(output.duration)
        assertEquals(7L, output.startNumber)
        assertEquals(listOf(YDashTimelineEntry(0L, 10_000L, 0)), output.timeline)
        assertEquals(10_000_000L, parsed.periods.single().durationUs)
        // An inclusive last segment is first + repeat, so a native demuxer never requests segment 8.
        assertEquals(7L, output.startNumber + output.timeline.single().repeat)
    }

    @Test
    fun a_partial_final_segment_uses_exact_ceiling_without_expanding_every_segment() {
        val output = outputTemplate(fixedTemplate(), 20_000_001L)
        assertEquals(listOf(YDashTimelineEntry(0L, 10_000L, 2)), output.timeline)
        assertEquals(3L, output.startNumber + output.timeline.single().repeat)
        val exact = outputTemplate(fixedTemplate(), 20_000_000L)
        assertEquals(1, exact.timeline.single().repeat)
    }

    @Test
    fun live_offset_and_authored_timeline_addressing_are_preserved() {
        val offset = fixedTemplate().copy(presentationTimeOffset = 12_345L)
        assertEquals(offset, outputTemplate(offset, 20_000_000L))
        val live = fixedTemplate()
        assertEquals(live, outputTemplate(live, 20_000_000L, live = true))
        val authored =
            fixedTemplate().copy(
                duration = null,
                timeline = listOf(YDashTimelineEntry(5_000L, 2_500L, 3)),
            )
        assertEquals(authored, outputTemplate(authored, 20_000_000L))
    }

    @Test
    fun unrepresentable_tick_products_keep_authored_addressing_instead_of_overflowing() {
        val template = fixedTemplate().copy(timescale = Long.MAX_VALUE)
        val output = outputTemplate(template, 20_000_000L)
        assertEquals(template, output)
        assertTrue(output.timeline.isEmpty())
    }

    private fun fixedTemplate() =
        YDashSegmentTemplate(
            initialization = "init.mp4",
            media = "segment-${'$'}Number${'$'}.m4s",
            timescale = 1_000L,
            duration = 10_000L,
        )

    private fun outputTemplate(
        template: YDashSegmentTemplate,
        durationUs: Long,
        live: Boolean = false,
    ) = parseYDashManifest(render(template, durationUs, live), "https://fixture.test/movie.mpd")
        .representations
        .single()
        .segmentTemplate!!

    private fun render(
        template: YDashSegmentTemplate,
        durationUs: Long,
        live: Boolean = false,
    ): String {
        val video =
            YDashRepresentation(
                id = "video",
                baseUri = "https://fixture.test/",
                bandwidthBitsPerSecond = 250_000L,
                contentType = YDashContentType.Video,
                mimeType = "video/mp4",
                codecs = listOf("avc1.42001e"),
                segmentTemplate = template,
            )
        val manifest =
            YDashManifest(
                isLive = live,
                mediaPresentationDurationUs = durationUs,
                representations = listOf(video),
            )
        return buildYDashPlaybackManifest(manifest, YDashPlaybackSelection(video, null)) { _, value, _ -> value }
    }
}
