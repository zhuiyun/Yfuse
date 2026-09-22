package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YInitialTrackSelectionTest {
    private val english = YTrack("audio:1", YTrackType.Audio, "Commentary", "eng", "audio/aac", true)
    private val chinese = YTrack("audio:4", YTrackType.Audio, "Commentary", "zho", "audio/aac")

    @Test fun initial_language_uses_container_track_identity_and_language_aliases() {
        val tracks = listOf(english, chinese)
        assertEquals(chinese, tracks.matchingPreference(YTrackPreference(language = "中文")))
        assertEquals(chinese, tracks.matchingPreference(YTrackPreference(language = "zh-CN")))
        assertEquals(english, tracks.matchingPreference(YTrackPreference(language = "en-US")))
    }

    @Test fun a_language_code_never_matches_another_languages_label() {
        val french = english.copy(label = "French", language = "fr")
        assertNull(listOf(french).matchingPreference(YTrackPreference(language = "en")))
        assertNull(listOf(french.copy(language = null)).matchingPreference(YTrackPreference(language = "en")))
        val englishLabel = english.copy(language = null, label = "English AAC")
        assertEquals(englishLabel, listOf(englishLabel).matchingPreference(YTrackPreference(language = "en")))
        assertEquals(
            englishLabel.copy(label = "英语 AAC"),
            listOf(englishLabel.copy(label = "英语 AAC")).matchingPreference(YTrackPreference(language = "en")),
        )
        assertNull(listOf(chinese).matchingPreference(YTrackPreference(language = "en", label = "Commentary")))
        assertEquals(
            english.copy(language = null, label = "英语 AAC"),
            listOf(english.copy(language = null, label = "英语 AAC"))
                .matchingPreference(YTrackPreference(language = "英语")),
        )
    }

    @Test fun duplicate_labels_and_codecs_preserve_the_requested_language_ordinal() {
        val second = chinese.copy(id = "audio:9")
        assertEquals(
            second,
            listOf(english, chinese, second).matchingPreference(
                YTrackPreference("zh", "Commentary", "audio/aac", languageOrdinal = 1),
            ),
        )
    }

    @Test fun trusted_handoff_ordinal_still_checks_metadata() {
        val tracks = listOf(english, chinese)
        assertEquals(chinese, tracks.matchingPreference(YTrackPreference(trackOrdinal = 1)))
        assertEquals(english, tracks.matchingPreference(YTrackPreference(language = "en", trackOrdinal = 1)))
    }

    @Test fun selected_audio_disabled_subtitles_and_invalid_ids_do_not_require_a_new_graph() {
        val state = YPlayerState(audioTracks = listOf(english, chinese))
        assertEquals("already_selected", state.trackSelectionSkipReason(YTrackType.Audio, english.id))
        assertEquals("already_disabled", state.trackSelectionSkipReason(YTrackType.Subtitle, "off"))
        assertEquals("track_not_discovered", state.trackSelectionSkipReason(YTrackType.Audio, "audio:unknown"))
        assertNull(state.trackSelectionSkipReason(YTrackType.Audio, chinese.id))
        val subtitle = YTrack("subtitle:2", YTrackType.Subtitle, "English", selected = true)
        assertNull(state.copy(subtitleTracks = listOf(subtitle)).trackSelectionSkipReason(YTrackType.Subtitle, "off"))
    }

    @Test fun a_route_change_restores_the_same_language_when_demux_ids_change() {
        val preference = chinese.preferenceIn(listOf(english, chinese))
        val enhancedChinese = chinese.copy(id = "audio:25")
        assertEquals(
            enhancedChinese,
            listOf(english.copy(id = "audio:4"), enhancedChinese).matchingPreference(preference),
        )
    }

    @Test fun mixed_language_aliases_share_the_same_ordinal_on_both_sides_of_a_route_change() {
        val first = english.copy(id = "audio:2", language = "en")
        val second = english.copy(id = "audio:4", language = "eng")
        val preference = second.preferenceIn(listOf(first, second))
        assertEquals(1, preference.languageOrdinal)
        val enhancedSecond = second.copy(id = "audio:8", language = "en-US")
        assertEquals(enhancedSecond, listOf(first, enhancedSecond).matchingPreference(preference))
    }

    @Test fun no_intent_has_one_canonical_representation() {
        assertNull(YInitialTrackSelection().orNull())
        assertEquals(true, YInitialTrackSelection(subtitlesDisabled = true).orNull()?.subtitlesDisabled)
    }
}
