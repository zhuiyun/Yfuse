package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DualSubtitlePreferencesTest {
    private val tracks =
        listOf(
            EngineTrack("zh", "中文", language = "ZH-Hans", selected = false),
            EngineTrack("en", "英文", language = "eng", selected = false),
        )

    @Test fun aliases_and_regions_select_distinct_tracks_in_role_order() {
        assertEquals(
            "zh" to "en",
            selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.ChineseEnglish)?.let {
                it.first.id to
                    it.second.id
            },
        )
        assertEquals(
            "en" to "zh",
            selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.EnglishChinese)?.let {
                it.first.id to
                    it.second.id
            },
        )
    }

    @Test fun missing_language_keeps_existing_selection() {
        assertNull(selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.JapaneseChinese))
        assertNull(selectDualSubtitleLanguagePair(tracks.take(1), DualSubtitleLanguagePair.ChineseEnglish))
    }
}
