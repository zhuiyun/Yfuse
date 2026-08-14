package com.yfuse.feature.player

import com.yfuse.core.data.RememberedPlaybackTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTrackRestoreTest {
    @Test
    fun track_ids_may_change_but_language_and_label_restore_the_same_track() {
        val preference =
            EngineTrack(
                id = "old-4",
                label = "国语",
                language = "zho",
                selected = true,
                codec = "aac",
            ).toRestorePreference()
        val rebuilt =
            listOf(
                EngineTrack("new-1", "English", "eng", false, "aac"),
                EngineTrack("new-9", "国语", "zho", false, "eac3"),
            )

        assertEquals("new-9", rebuilt.bestRestoreMatch(preference)?.id)
    }

    @Test
    fun ambiguous_codec_without_language_or_matching_label_is_not_restored() {
        val preference = TrackRestorePreference(language = null, label = "导演评论", codec = "aac")
        val rebuilt =
            listOf(
                EngineTrack("1", "主音轨", null, true, "aac"),
                EngineTrack("2", "备用音轨", null, false, "aac"),
            )

        assertNull(rebuilt.bestRestoreMatch(preference))
    }

    @Test
    fun persisted_track_identity_round_trips_without_engine_local_id() {
        val remembered =
            EngineTrack("mpv-17", " 简体中文 ", "zho", true, " srt ")
                .toRememberedPlaybackTrack()

        assertEquals(RememberedPlaybackTrack("zho", "简体中文", "srt"), remembered)
        assertEquals(
            TrackRestorePreference("zho", "简体中文", "srt"),
            remembered.toRestorePreference(),
        )
    }
}
