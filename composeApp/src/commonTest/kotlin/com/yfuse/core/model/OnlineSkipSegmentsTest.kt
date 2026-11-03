package com.yfuse.core.model

import com.yfuse.core.network.TheOtherDbEpisode
import com.yfuse.core.network.TheOtherDbMarker
import com.yfuse.core.network.TheOtherDbQuery
import com.yfuse.core.network.TheOtherDbSeason
import com.yfuse.core.network.TheOtherDbVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineSkipSegmentsTest {
    private val query = TheOtherDbQuery(1, 1, 2)
    private val version =
        TheOtherDbVersion(
            id = 1899,
            runtime = 2752.0,
            videoType = "tv",
            season = TheOtherDbSeason(1),
            episode = TheOtherDbEpisode(2),
            markers = listOf(TheOtherDbMarker("intro", 0.0, 92.0), TheOtherDbMarker("credits", 2589.0, 2752.0)),
        )

    @Test
    fun verified_response_maps_intro_and_end_relative_credits() {
        val segments = matchOnlineSkipSegments(listOf(version), query, 2_753_000)
        assertEquals(PlaybackSegment(PlaybackSegmentType.Intro, 0, 92_000), segments[0])
        assertEquals(PlaybackSegment(PlaybackSegmentType.Credits, 2_590_000, null), segments[1])
    }

    @Test
    fun runtime_or_coordinates_must_match() {
        assertTrue(matchOnlineSkipSegments(listOf(version), query, 0).isEmpty())
        assertTrue(matchOnlineSkipSegments(listOf(version), query, 2_760_000).isEmpty())
        assertTrue(matchOnlineSkipSegments(listOf(version), query.copy(episode = 3), 2_752_000).isEmpty())
        assertTrue(matchOnlineSkipSegments(listOf(version), query.copy(season = 2), 2_752_000).isEmpty())
        assertTrue(matchOnlineSkipSegments(listOf(version), TheOtherDbQuery(1), 2_752_000).isEmpty())
    }

    @Test
    fun same_length_conflicting_cuts_are_not_combined_or_guessed() {
        val conflicting = version.copy(id = 1900, markers = listOf(TheOtherDbMarker("intro", 30.0, 122.0)))
        assertTrue(matchOnlineSkipSegments(listOf(version, conflicting), query, 2_752_000).isEmpty())
        assertEquals(2, matchOnlineSkipSegments(listOf(version, version.copy(id = 1900)), query, 2_752_000).size)
    }

    @Test
    fun chapters_missing_ends_short_credits_and_post_credit_scenes_are_not_skipped() {
        val invalid =
            version.copy(
                markers =
                    listOf(
                        TheOtherDbMarker("chapter", 223.0, null),
                        TheOtherDbMarker("credits", 2751.0, 2752.0),
                        TheOtherDbMarker("credits", 2589.0, null),
                        TheOtherDbMarker("credits", 2500.0, 2650.0),
                        TheOtherDbMarker("intro", -10.0, 90.0),
                        TheOtherDbMarker("intro", 0.0, 3000.0),
                        TheOtherDbMarker("intro", 20.0, 10.0),
                        TheOtherDbMarker("intro", Double.NaN, 90.0),
                    ),
            )
        assertTrue(matchOnlineSkipSegments(listOf(invalid), query, 2_752_000).isEmpty())
    }

    @Test
    fun conflicting_markers_of_one_type_disable_only_that_type() {
        val conflict = version.copy(markers = version.markers + TheOtherDbMarker("intro", 5.0, 97.0))
        assertEquals(
            listOf(
                PlaybackSegmentType.Credits,
            ),
            matchOnlineSkipSegments(listOf(conflict), query, 2_752_000).map {
                it.type
            },
        )
    }

    @Test
    fun server_markers_win_and_online_fills_each_missing_type() {
        val server = listOf(PlaybackSegment(PlaybackSegmentType.Intro, 5_000, 95_000))
        val online = matchOnlineSkipSegments(listOf(version), query, 2_752_000)
        assertEquals(listOf(server.single(), online.last()), mergeOnlineSkipSegments(server, online))
    }
}
