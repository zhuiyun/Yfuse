package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MissedLineRewindTest {
    private fun track(
        id: String,
        language: String?,
        selected: Boolean = false,
        label: String = id,
    ) = EngineTrack(id = id, label = label, language = language, selected = selected)

    private val englishAudio = listOf(track("a1", "eng", selected = true), track("a2", "jpn"))
    private val subtitles = listOf(track("s-zh", "chi"), track("s-en", "en"), track("s-ja", "ja"))

    private fun rewind(
        positionMs: Long = 600_000L,
        subtitleTracks: List<EngineTrack> = subtitles,
        audioTracks: List<EngineTrack> = englishAudio,
        secondary: String? = null,
        running: SubtitlePeek? = null,
        allowed: Boolean = true,
    ) = missedLineRewind(positionMs, subtitleTracks, audioTracks, secondary, running, allowed)

    @Test
    fun prefers_a_subtitle_in_the_spoken_language_across_code_spellings() {
        assertEquals("s-en", missedLineSubtitleTrack(subtitles, englishAudio)?.id)
        val japanese = listOf(track("a", "jpn", selected = true))
        assertEquals("s-ja", missedLineSubtitleTrack(subtitles, japanese)?.id)
        val mandarin = listOf(track("a", "zh-CN", selected = true))
        assertEquals("s-zh", missedLineSubtitleTrack(subtitles, mandarin)?.id)
        val cantonese = listOf(track("a", "yue", selected = true))
        assertEquals("s-zh", missedLineSubtitleTrack(subtitles, cantonese)?.id)
    }

    @Test
    fun falls_back_to_the_first_track_and_puts_forced_tracks_last() {
        val french = listOf(track("a", "fre", selected = true))
        assertEquals("s-zh", missedLineSubtitleTrack(subtitles, french)?.id)
        assertEquals("s-zh", missedLineSubtitleTrack(subtitles, listOf(track("a", "und", selected = true)))?.id)
        val withForced = listOf(track("f", "en", label = "English (Forced)"), track("full", "en"))
        assertEquals("full", missedLineSubtitleTrack(withForced, englishAudio)?.id)
        assertEquals("f", missedLineSubtitleTrack(listOf(track("f", "en", label = "强制")), englishAudio)?.id)
        assertNull(missedLineSubtitleTrack(emptyList(), englishAudio))
    }

    @Test
    fun turns_subtitles_on_when_they_are_off_and_remembers_off() {
        val plan = rewind()
        assertEquals(590_000L, plan.targetMs)
        assertEquals(SubtitlePeek(trackId = "s-en", restoreTrackId = EngineTrack.OFF, untilMs = 600_000L), plan.peek)
        assertEquals("没听清：倒回 10 秒，临时打开字幕", plan.message)
    }

    @Test
    fun a_forced_track_counts_as_off_and_is_what_comes_back() {
        val forcedOn = listOf(track("f", "en", selected = true, label = "Forced"), track("s-en", "en"))
        val plan = rewind(subtitleTracks = forcedOn)
        assertEquals("s-en", plan.peek?.trackId)
        assertEquals("f", plan.peek?.restoreTrackId)
    }

    @Test
    fun only_rewinds_when_subtitles_are_up_or_not_this_devices_to_change() {
        val on = subtitles.map { if (it.id == "s-zh") it.copy(selected = true) else it }
        assertNull(rewind(subtitleTracks = on).peek)
        assertEquals("没听清：倒回 10 秒", rewind(subtitleTracks = on).message)
        assertNull(rewind(secondary = "s-ja").peek)
        assertNull(rewind(allowed = false).peek)
        assertNull(rewind(positionMs = 1_500L).peek)
        assertEquals(0L, rewind(positionMs = 1_500L).targetMs)
        val none = rewind(subtitleTracks = emptyList())
        assertNull(none.peek)
        assertEquals("没听清：倒回 10 秒（没有可用的字幕）", none.message)
    }

    @Test
    fun a_second_press_keeps_the_subtitle_until_the_later_position() {
        val running = SubtitlePeek("s-en", EngineTrack.OFF, untilMs = 600_000L, rewound = true, confirmed = true)
        val earlier = rewind(positionMs = 595_000L, running = running)
        assertEquals(585_000L, earlier.targetMs)
        assertEquals(running, earlier.peek)
        val later = rewind(positionMs = 605_000L, running = running)
        assertEquals(605_000L, later.peek?.untilMs)
    }

    @Test
    fun holds_until_the_rewind_lands_and_restores_once_the_press_point_is_passed() {
        val peek = SubtitlePeek("s-en", EngineTrack.OFF, untilMs = 600_000L)
        val shown = subtitles.map { if (it.id == "s-en") it.copy(selected = true) else it }
        // Samples from before the seek landed are at or past the press, and must not restore.
        val beforeSeek = peek.step(600_400L, subtitles)
        assertIs<SubtitlePeekStep.Hold>(beforeSeek)
        assertEquals(false, beforeSeek.peek.rewound)
        val landed = (beforeSeek.peek.step(590_100L, shown) as SubtitlePeekStep.Hold).peek
        assertEquals(true, landed.rewound)
        assertEquals(true, landed.confirmed)
        assertIs<SubtitlePeekStep.Hold>(landed.step(599_900L, shown))
        assertEquals(SubtitlePeekStep.Restore(EngineTrack.OFF), landed.step(600_000L, shown))
    }

    @Test
    fun restores_the_exact_earlier_track() {
        val peek = SubtitlePeek("s-en", restoreTrackId = "s-zh", untilMs = 600_000L, rewound = true, confirmed = true)
        val shown = subtitles.map { if (it.id == "s-en") it.copy(selected = true) else it }
        assertEquals(SubtitlePeekStep.Restore("s-zh"), peek.step(601_000L, shown))
        // A track the engine no longer lists is not restored into nothing.
        val gone = shown.filterNot { it.id == "s-zh" }
        assertEquals(SubtitlePeekStep.Release, peek.step(601_000L, gone))
    }

    @Test
    fun someone_elses_choice_after_the_peek_took_hold_releases_it() {
        val peek = SubtitlePeek("s-en", EngineTrack.OFF, untilMs = 600_000L, rewound = true, confirmed = true)
        val picked = subtitles.map { if (it.id == "s-ja") it.copy(selected = true) else it }
        assertEquals(SubtitlePeekStep.Release, peek.step(595_000L, picked))
        assertEquals(SubtitlePeekStep.Release, peek.step(595_000L, subtitles))
        // Before the engine has confirmed the peek, the old selection is simply not caught up yet.
        val unconfirmed = peek.copy(confirmed = false)
        assertIs<SubtitlePeekStep.Hold>(unconfirmed.step(595_000L, subtitles))
    }

    @Test
    fun a_rewind_that_never_lands_still_ends_the_peek() {
        val peek = SubtitlePeek("s-en", EngineTrack.OFF, untilMs = 600_000L)
        assertIs<SubtitlePeekStep.Hold>(peek.step(609_000L, subtitles))
        assertEquals(SubtitlePeekStep.Restore(EngineTrack.OFF), peek.step(610_000L, subtitles))
    }

    @Test
    fun a_handover_carries_the_viewers_choice_not_the_peek() {
        val shown = subtitles.map { if (it.id == "s-en") it.copy(selected = true) else it }
        assertEquals("s-en", viewerSubtitleChoice(shown, peek = null)?.id)
        assertNull(viewerSubtitleChoice(shown, SubtitlePeek("s-en", EngineTrack.OFF, 600_000L)))
        assertEquals("s-zh", viewerSubtitleChoice(shown, SubtitlePeek("s-en", "s-zh", 600_000L))?.id)
    }
}
