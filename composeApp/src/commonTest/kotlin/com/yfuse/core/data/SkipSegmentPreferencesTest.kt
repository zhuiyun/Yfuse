package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipSegmentPreferencesTest {

    private val fortyFiveMinutes = 45 * 60 * 1000L

    private fun credits(segments: List<PlaybackSegment>) =
        segments.firstOrNull { it.type == PlaybackSegmentType.Credits }

    /**
     * The point of storing 片尾 as a distance from the end: the same entry has to land in
     * the right place on episodes whose runtimes differ, which is every show ever.
     */
    @Test
    fun credits_are_placed_back_from_the_end_of_whatever_is_playing() {
        val prefs = SkipSegmentPreferences(MapSettings())
        prefs.set("series", SkipTimes(creditsLeadSeconds = 90, seriesName = "剧"))

        val short = prefs.applyTo("series", emptyList(), durationMs = fortyFiveMinutes)
        val long = prefs.applyTo("series", emptyList(), durationMs = fortyFiveMinutes + 120_000L)

        assertEquals(fortyFiveMinutes - 90_000L, credits(short)?.startMs)
        assertEquals(fortyFiveMinutes + 120_000L - 90_000L, credits(long)?.startMs)
        // Credits run to the end of the file, which the player supplies.
        assertNull(credits(short)?.endMs)
    }

    @Test
    fun credits_wait_for_a_duration_rather_than_landing_at_zero() {
        val prefs = SkipSegmentPreferences(MapSettings())
        prefs.set("series", SkipTimes(creditsLeadSeconds = 90))

        assertNull(credits(prefs.applyTo("series", emptyList(), durationMs = 0L)))
    }

    /** A lead longer than the item would make the whole thing 片尾 — a typo, not an order. */
    @Test
    fun a_lead_longer_than_the_item_is_ignored() {
        val prefs = SkipSegmentPreferences(MapSettings())
        prefs.set("series", SkipTimes(creditsLeadSeconds = 60 * 60))

        assertNull(credits(prefs.applyTo("series", emptyList(), durationMs = fortyFiveMinutes)))
    }

    @Test
    fun clearing_the_last_boundary_drops_the_entry() {
        val settings = MapSettings()
        val prefs = SkipSegmentPreferences(settings)
        prefs.set("series", SkipTimes(introStartSeconds = 5, introEndSeconds = 95, seriesName = "剧"))

        // What 取消 does to the last boundary standing: zero everything.
        prefs.set("series", SkipTimes(seriesName = "剧"))

        assertNull(prefs.timesFor("series"))
        assertTrue(SkipSegmentPreferences(settings).bySeries.value.isEmpty())
    }

    @Test
    fun clearing_one_boundary_leaves_the_others_alone() {
        val prefs = SkipSegmentPreferences(MapSettings())
        prefs.set(
            "series",
            SkipTimes(introStartSeconds = 5, introEndSeconds = 95, creditsLeadSeconds = 90),
        )

        val kept = prefs.timesFor("series")!!
        prefs.set("series", kept.copy(creditsLeadSeconds = 0))

        val after = prefs.timesFor("series")
        assertEquals(5L, after?.introStartSeconds)
        assertEquals(95L, after?.introEndSeconds)
        assertEquals(0L, after?.creditsLeadSeconds)
        // The server's own credits come back into force once the override is gone.
        val serverCredits = PlaybackSegment(PlaybackSegmentType.Credits, 1_000L, null)
        assertEquals(
            listOf(serverCredits),
            prefs.applyTo("series", listOf(serverCredits), durationMs = fortyFiveMinutes)
                .filter { it.type == PlaybackSegmentType.Credits },
        )
    }

    /** A custom segment replaces the server's of the same type, and only that type. */
    @Test
    fun an_override_replaces_only_its_own_segment_type() {
        val prefs = SkipSegmentPreferences(MapSettings())
        prefs.set("series", SkipTimes(introStartSeconds = 0, introEndSeconds = 90))
        val serverIntro = PlaybackSegment(PlaybackSegmentType.Intro, 0L, 30_000L)
        val serverCredits = PlaybackSegment(PlaybackSegmentType.Credits, 1_000L, null)

        val applied = prefs.applyTo(
            "series",
            listOf(serverIntro, serverCredits),
            durationMs = fortyFiveMinutes,
        )

        assertEquals(90_000L, applied.first { it.type == PlaybackSegmentType.Intro }.endMs)
        assertTrue(serverCredits in applied)
    }

    @Test
    fun a_series_without_an_entry_keeps_the_server_segments_untouched() {
        val prefs = SkipSegmentPreferences(MapSettings())
        val server = listOf(PlaybackSegment(PlaybackSegmentType.Intro, 0L, 30_000L))

        assertEquals(server, prefs.applyTo("other", server, durationMs = fortyFiveMinutes))
        assertEquals(server, prefs.applyTo(null, server, durationMs = fortyFiveMinutes))
    }
}
