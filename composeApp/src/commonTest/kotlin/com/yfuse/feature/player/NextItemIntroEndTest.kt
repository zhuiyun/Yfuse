package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextItemIntroEndTest {
    private val preferences = SkipSegmentPreferences(MapSettings())

    private fun episode(
        segments: List<PlaybackSegment> = emptyList(),
        seriesKey: String? = "provider:tvdb-1",
        seriesId: String? = "series-1",
    ) = PlayerMediaItem(
        id = "episode-2",
        url = "https://media.invalid/2.mkv",
        transcodeUrl = "",
        title = "E2",
        serverId = "server-1",
        playbackSegments = segments,
        seriesId = seriesId,
        seriesKey = seriesKey,
    )

    @Test
    fun server_intro_marker_is_where_the_next_episode_will_be_after_skipping() {
        val next = episode(listOf(PlaybackSegment(PlaybackSegmentType.Intro, 5_000L, 95_000L)))

        for (mode in listOf(SkipMode.Button, SkipMode.Auto)) {
            assertEquals(95_000L, nextItemIntroEndMs(next, mode, preferences.bySeries.value, preferences))
        }
    }

    @Test
    fun series_times_set_by_hand_override_the_server_marker() {
        preferences.set("provider:tvdb-1", SkipTimes(introStartSeconds = 0L, introEndSeconds = 80L))
        val next = episode(listOf(PlaybackSegment(PlaybackSegmentType.Intro, 5_000L, 95_000L)))

        assertEquals(80_000L, nextItemIntroEndMs(next, SkipMode.Auto, preferences.bySeries.value, preferences))
    }

    @Test
    fun nothing_is_warmed_when_skipping_is_off_or_there_is_no_intro() {
        val withIntro = episode(listOf(PlaybackSegment(PlaybackSegmentType.Intro, 0L, 90_000L)))
        assertNull(nextItemIntroEndMs(withIntro, SkipMode.Off, preferences.bySeries.value, preferences))
        assertNull(nextItemIntroEndMs(episode(), SkipMode.Auto, preferences.bySeries.value, preferences))
        assertNull(nextItemIntroEndMs(null, SkipMode.Auto, preferences.bySeries.value, preferences))
        // Films have no series, and intro skipping is episode-only.
        assertNull(
            nextItemIntroEndMs(
                episode(withIntro.playbackSegments, seriesKey = null, seriesId = null),
                SkipMode.Auto,
                preferences.bySeries.value,
                preferences,
            ),
        )
    }
}
