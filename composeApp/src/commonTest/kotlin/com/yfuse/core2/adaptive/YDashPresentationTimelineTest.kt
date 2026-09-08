package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YDashPresentationTimelineTest {
    @Test
    fun repeated_representation_ids_stay_scoped_to_period_and_seek_crosses_exact_boundary() {
        val presentation = parseYDashManifest(mpd(), "https://example.test/title/main.mpd")
        assertEquals(listOf("first", "second"), presentation.periods.map { it.id })
        assertEquals(listOf(0L, 10_000_000L), presentation.periods.map { it.startUs })
        assertEquals(listOf(10_000_000L, 20_000_000L), presentation.periods.map { it.durationUs })
        assertEquals("first", presentation.periodForPositionUs(9_999_999L).id)
        val second = presentation.periodForPositionUs(10_000_000L)
        assertEquals("second", second.id)
        assertEquals("second", presentation.periodForPositionUs(30_000_000L).id)
        val target = presentation.manifestForPeriod(second)
        assertEquals(0L, target.periodStartUs)
        assertEquals(20_000_000L, target.mediaPresentationDurationUs)
        val video = target.representations.single()
        assertEquals("v1", video.id)
        assertEquals("https://example.test/title/second/", video.baseUri)
        assertEquals(10_000L, video.segmentTemplate?.presentationTimeOffset)
        val output =
            buildYDashPlaybackManifest(target, YDashPlaybackSelection(video, null)) { _, template, _ -> template }
        assertEquals(1, Regex("<Period(?:\\s|>)").findAll(output).count())
        assertTrue("profiles=\"urn:mpeg:dash:profile:isoff-live:2011\"" in output)
        assertTrue("presentationTimeOffset=\"10000\"" in output)
        assertTrue("init-second.mp4" in output)
        assertTrue("init-first.mp4" !in output)
    }

    @Test
    fun period_duration_infers_next_start_and_last_duration_but_overlap_is_rejected() {
        val implicit =
            mpd()
                .replace("id=\"first\" start=\"PT0S\"", "id=\"first\" duration=\"PT10S\"")
                .replace("id=\"second\" start=\"PT10S\"", "id=\"second\"")
        assertEquals(10_000_000L, parseYDashManifest(implicit, "https://example.test/x.mpd").periods[1].startUs)
        assertFailsWith<IllegalArgumentException> {
            parseYDashManifest(
                mpd().replace("id=\"first\" start=\"PT0S\"", "id=\"first\" duration=\"PT15S\""),
                "https://example.test/x.mpd",
            )
        }
    }

    private fun mpd(): String =
        """
        <MPD type="static" mediaPresentationDuration="PT30S">
          <Period id="first" start="PT0S"><BaseURL>first/</BaseURL>
            <SegmentTemplate timescale="1000" duration="2000" initialization="init-first.mp4"
              media="segment-${'$'}Number${'$'}.m4s"/>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <Representation id="v1" bandwidth="1000000" codecs="avc1.64001f"/>
            </AdaptationSet>
          </Period>
          <Period id="second" start="PT10S"><BaseURL>second/</BaseURL>
            <SegmentTemplate timescale="1000" presentationTimeOffset="10000" initialization="init-second.mp4"
              media="segment-${'$'}Time${'$'}.m4s"><SegmentTimeline><S t="10000" d="2000" r="9"/></SegmentTimeline>
            </SegmentTemplate>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <Representation id="v1" bandwidth="2000000" codecs="avc1.640028"/>
            </AdaptationSet>
          </Period>
        </MPD>
        """.trimIndent()
}
