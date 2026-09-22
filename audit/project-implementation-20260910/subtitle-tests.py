from pathlib import Path
p=Path('composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/ExoDualSubtitleCueMergerTest.kt')
p.write_text('''package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@UnstableApi
class ExoDualSubtitleCueMergerTest {
    @Test fun dual_preserves_channels_and_suppresses_native_duplicate() {
        val merger = ExoDualSubtitleCueMerger()
        val output = mutableListOf<CueGroup>()
        val primary = merger.primaryOutput(TextOutput { output += it })
        val authored = Cue.Builder().setText("主字幕\\n第二行").setLine(0.2f, Cue.LINE_TYPE_FRACTION).build()
        primary.onCues(CueGroup(listOf(authored), 10L))
        assertEquals(authored, output.last().cues.single())
        merger.setDual(true)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("副字幕").build()), 20L))
        assertTrue(output.last().cues.isEmpty())
        assertEquals(authored, merger.channels.value.primary.cues.single())
        assertEquals("副字幕", merger.channels.value.secondary.cues.single().text.toString())
    }

    @Test fun clearing_cue_does_not_exit_dual_mode() {
        val merger = ExoDualSubtitleCueMerger()
        merger.setDual(true)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("旧字幕").build()), 10L))
        merger.clearSecondary()
        assertTrue(merger.channels.value.dual)
        assertTrue(merger.channels.value.secondary.cues.isEmpty())
    }

    @Test fun disabling_restores_primary_and_rejects_late_secondary_callback() {
        val merger = ExoDualSubtitleCueMerger()
        val output = mutableListOf<CueGroup>()
        merger.primaryOutput(TextOutput { output += it }).onCues(CueGroup(listOf(Cue.Builder().setText("主字幕").build()), 10L))
        merger.setDual(true)
        merger.setDual(false)
        merger.secondaryOutput().onCues(CueGroup(listOf(Cue.Builder().setText("旧副字幕").build()), 10L))
        assertEquals("主字幕", output.last().cues.single().text.toString())
        assertTrue(merger.channels.value.secondary.cues.isEmpty())
    }
}
''',encoding='utf-8')
p=Path('composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/MpvSubtitleTextTest.kt')
p.write_text('''package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvSubtitleTextTest {
    @Test fun only_two_known_text_tracks_use_the_measured_stack() {
        val tracks = listOf(EngineTrack("1", "主", codec = "ass"), EngineTrack("2", "副", codec = "subrip"), EngineTrack("3", "图", codec = "hdmv_pgs_subtitle"))
        assertTrue(mpvCanStackSubtitles(tracks, "1", "2"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "3"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "1"))
        assertFalse(mpvCanStackSubtitles(tracks, "1", null))
        assertFalse(mpvCanStackSubtitles(tracks, "1", "missing"))
    }
}
''',encoding='utf-8')
p=Path('composeApp/src/commonTest/kotlin/com/yfuse/feature/player/DualSubtitlePreferencesTest.kt')
p.write_text('''package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DualSubtitlePreferencesTest {
    private val tracks = listOf(EngineTrack("zh", "中文", language = "ZH-Hans"), EngineTrack("en", "英文", language = "eng"))
    @Test fun aliases_and_regions_select_distinct_tracks_in_role_order() {
        assertEquals("zh" to "en", selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.ChineseEnglish)?.let { it.first.id to it.second.id })
        assertEquals("en" to "zh", selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.EnglishChinese)?.let { it.first.id to it.second.id })
    }
    @Test fun missing_language_keeps_existing_selection() {
        assertNull(selectDualSubtitleLanguagePair(tracks, DualSubtitleLanguagePair.JapaneseChinese))
        assertNull(selectDualSubtitleLanguagePair(tracks.take(1), DualSubtitleLanguagePair.ChineseEnglish))
    }
}
''',encoding='utf-8')
