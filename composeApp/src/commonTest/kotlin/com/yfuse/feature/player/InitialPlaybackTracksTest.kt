package com.yfuse.feature.player

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.handoff.HandoffMedia
import com.yfuse.core.sync.playback.PlaybackTrackPreference
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YTrackPreference
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialPlaybackTracksTest {
    private val item =
        PlayerMediaItem(
            "movie",
            "https://media/movie",
            "",
            "Movie",
            serverId = "server",
            watchKey = "identity",
            versionId = "file",
        )
    private val original = YInitialTrackSelection(audio = YTrackPreference(language = "en"))
    private val handoff =
        HandoffMedia(
            mediaKey = "identity",
            title = "Movie",
            serverId = "server",
            itemId = "movie",
            positionMs = 0,
            durationMs = 1_000,
            mediaSourceId = "file",
            profileId = "profile",
            preference = PlaybackTrackPreference(audioLanguage = "zh", subtitlesEnabled = false),
            audioTrackIndex = 1,
        )

    @Test fun matching_handoff_supplies_tracks_before_the_first_player_is_built() {
        val initial = original.withInitialHandoff(item, handoff, "profile")
        assertEquals("zh", initial?.audio?.language)
        assertEquals(1, initial?.audio?.trackOrdinal)
        assertEquals(true, initial?.subtitlesDisabled)
    }

    @Test fun stale_handoff_never_changes_a_different_source_or_profile() {
        assertEquals(original, original.withInitialHandoff(item, handoff.copy(itemId = "other"), "profile"))
        assertEquals(original, original.withInitialHandoff(item, handoff.copy(mediaSourceId = "other"), "profile"))
        assertEquals(original, original.withInitialHandoff(item, handoff, "other-profile"))
        assertEquals(original, original.withInitialHandoff(item, null, "profile"))
    }

    @Test fun a_detail_page_pick_among_one_language_survives_the_rematch_after_discovery() {
        val hint = PlaybackTrackRequest.TrackHint(label = "简英双语", codec = "ass", languageOrdinal = 1)
        val initial =
            item.initialPlaybackTracks(
                PlaybackPreferences(MapSettings()),
                PlaybackTrackRequest.Tracks(audioLanguage = null, subtitleLanguage = "中文", subtitleHint = hint),
            )
        assertEquals(YTrackPreference("中文", "简英双语", "ass", 1), initial?.subtitle)

        val tracks =
            listOf(
                EngineTrack("s1", "简体", "chi", selected = false, codec = "x-ssa"),
                EngineTrack("s2", "简英双语", "chi", selected = true, codec = "x-ssa"),
            )
        assertEquals("s2", tracks.matchingRequestedTrack("中文", hint))
        // An engine that names tracks by language alone still gets the place among them.
        assertEquals("s2", tracks.map { it.copy(label = "chi") }.matchingRequestedTrack("中文", hint))
        // Without a hint the language's first track, exactly as before.
        assertEquals("s1", tracks.matchingRequestedTrack("中文", null))
    }
}
