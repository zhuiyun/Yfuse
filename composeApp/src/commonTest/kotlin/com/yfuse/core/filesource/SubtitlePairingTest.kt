package com.yfuse.core.filesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitlePairingTest {
    @Test
    fun same_name_sidecars_pair_and_simplified_chinese_is_the_default() {
        val folder =
            files(
                "Dune.Part.Two.2024.2160p.mkv",
                "Dune.Part.Two.2024.2160p.eng.srt",
                "Dune.Part.Two.2024.2160p.cht.ass",
                "Dune.Part.Two.2024.2160p.chs.ass",
                "Dune.Part.Two.2024.2160p.zh-CN.forced.srt",
                "Oppenheimer.2023.mkv",
                "Oppenheimer.2023.chs.srt",
            )

        val paired = pairSubtitles(folder.first(), folder)

        assertEquals(
            listOf(
                Triple("Dune.Part.Two.2024.2160p.chs.ass", "简体中文", true),
                Triple("Dune.Part.Two.2024.2160p.zh-CN.forced.srt", "简体中文（SRT）", false),
                Triple("Dune.Part.Two.2024.2160p.cht.ass", "繁体中文", false),
                Triple("Dune.Part.Two.2024.2160p.eng.srt", "英语", false),
            ),
            paired.map { Triple(it.entry.name, it.language, it.default) },
        )
        assertTrue(paired.single { it.entry.name.contains("forced") }.forced)
    }

    @Test
    fun a_longer_video_name_keeps_its_own_subtitles() {
        val folder =
            files(
                "Dune.2024.mkv",
                "Dune.2024.Extended.mkv",
                "Dune.2024.srt",
                "Dune.2024.Extended.chs.srt",
            )

        assertEquals(listOf("Dune.2024.srt"), pairSubtitles(folder[0], folder).map { it.entry.name })
        assertEquals(listOf("Dune.2024.Extended.chs.srt"), pairSubtitles(folder[1], folder).map { it.entry.name })
    }

    @Test
    fun a_lone_video_takes_every_subtitle_in_its_folder() {
        val folder = files("流浪地球2.2023.mkv", "简体.srt", "English.ass", "poster.jpg")

        val paired = pairSubtitles(folder.first(), folder)

        assertEquals(listOf("简体.srt" to "简体中文", "English.ass" to "英语"), paired.map { it.entry.name to it.language })
        assertEquals(listOf(true, false), paired.map { it.default })
    }

    @Test
    fun an_episode_folder_does_not_hand_other_episodes_subtitles_around() {
        val folder = files("S01E01.mkv", "S01E02.mkv", "S01E02.chs.srt", "notes.srt")

        assertEquals(emptyList(), pairSubtitles(folder[0], folder))
        assertEquals(listOf("S01E02.chs.srt"), pairSubtitles(folder[1], folder).map { it.entry.name })
    }

    @Test
    fun an_untagged_same_name_subtitle_is_the_default_when_nothing_is_chinese() {
        val folder = files("Movie.mkv", "Movie.srt", "Movie.en.srt")

        val paired = pairSubtitles(folder.first(), folder)

        assertEquals(listOf("Movie.srt" to true, "Movie.en.srt" to false), paired.map { it.entry.name to it.default })
    }

    @Test
    fun tags_read_whole_tokens_only() {
        assertEquals("简英双语", subtitleTags("chs&eng").language)
        assertEquals("简英双语", subtitleTags("简英").language)
        assertEquals("繁体中文", subtitleTags("zh-TW").language)
        assertEquals("繁体中文", subtitleTags("zh_hant").language)
        assertEquals("简体中文", subtitleTags("zh-Hans").language)
        assertEquals("中文", subtitleTags("zh").language)
        assertEquals("日语", subtitleTags("jpn").language)
        assertEquals(null, subtitleTags("tension").language)
        assertEquals(null, subtitleTags("").language)
        assertTrue(subtitleTags("eng.forced").forced)
    }

    private fun files(vararg names: String): List<FileSourceEntry> =
        names.map { FileSourceEntry(it, directory = false) }
}
