package com.yfuse.core.filesource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaNameParserTest {
    @Test
    fun the_sample_library_reads_as_a_person_would_read_it() {
        val misses =
            MEDIA_NAME_SAMPLES.filterNot { sample ->
                val parsed = parseMediaPath(sample.path)
                parsed != null &&
                    parsed.titles.any { it.sameTitleAs(sample.title) } &&
                    parsed.year == sample.year &&
                    parsed.season == sample.season &&
                    parsed.episode == sample.episode &&
                    (parsed.kind == ParsedMediaKind.Episode) == (sample.episode != null)
            }
        val report =
            misses.joinToString("\n") { sample ->
                "${sample.path.joinToString("/")} -> ${parseMediaPath(sample.path)}"
            }

        // The acceptance bar is 90% matched against TMDB; the names have to read better than that.
        assertTrue(misses.size <= MEDIA_NAME_SAMPLES.size / 50, "${misses.size} misses:\n$report")
        assertEquals(200, MEDIA_NAME_SAMPLES.size)
    }

    @Test
    fun a_chinese_and_a_latin_title_are_both_offered_in_the_order_written() {
        val parsed = parseMediaPath(listOf("流浪地球2.The.Wandering.Earth.II.2023.2160p.WEB-DL.H265.mkv"))

        assertEquals(listOf("流浪地球2", "The Wandering Earth II"), parsed?.titles)
        assertEquals(2023, parsed?.year)
        assertEquals(ParsedMediaKind.Movie, parsed?.kind)
    }

    @Test
    fun a_bare_episode_takes_its_title_and_year_from_the_folders_above() {
        val parsed = parseMediaPath(listOf("剧集", "三体 (2023)", "第一季", "第03集.mp4"))

        assertEquals("三体", parsed?.title)
        assertEquals(2023, parsed?.year)
        assertEquals(1, parsed?.season)
        assertEquals(3, parsed?.episode)
        assertEquals(ParsedMediaKind.Episode, parsed?.kind)
    }

    @Test
    fun a_tmdb_tag_in_a_folder_name_is_kept_as_the_exact_answer() {
        val parsed = parseMediaPath(listOf("Movies", "Oppenheimer (2023) [tmdbid-872585]", "Oppenheimer.mkv"))

        assertEquals(872_585, parsed?.tmdbId)
        assertEquals("Oppenheimer", parsed?.title)
    }

    @Test
    fun samples_extras_and_disc_insides_are_not_titles_of_their_own() {
        assertNull(parseMediaPath(listOf("The.Matrix.1999.Sample.mkv")))
        assertNull(parseMediaPath(listOf("Dune (2021)", "Dune-trailer.mkv")))
        assertNull(parseMediaPath(listOf("Inception (2010)", "Extras", "Inception - Behind the Scenes.mkv")))
        assertNull(parseMediaPath(listOf("Avatar.2009.BluRay.1080p", "BDMV", "STREAM", "00000.m2ts")))
        assertNull(parseMediaPath(listOf("狂飙", "狂飙 花絮 01.mp4")))
    }

    @Test
    fun a_film_named_like_a_number_or_an_edition_is_still_that_film() {
        assertEquals("300", parseMediaPath(listOf("300.2006.1080p.BluRay.mkv"))?.title)
        assertEquals("Apollo 13", parseMediaPath(listOf("Apollo 13 (1995)", "Apollo 13.mkv"))?.title)
        assertEquals(ParsedMediaKind.Movie, parseMediaPath(listOf("Apollo 13 (1995)", "Apollo 13.mkv"))?.kind)
        assertEquals("Dual", parseMediaPath(listOf("Dual.2022.1080p.WEB-DL.mkv"))?.title)
    }

    @Test
    fun chinese_counts_read_as_numbers() {
        assertEquals(12, parseCountNumber("十二"))
        assertEquals(23, parseCountNumber("二十三"))
        assertEquals(105, parseCountNumber("一百零五"))
        assertEquals(2, parseCountNumber("两"))
        assertEquals(7, parseCountNumber("07"))
        assertNull(parseCountNumber("第"))
    }

    @Test
    fun titles_compare_without_case_width_or_punctuation() {
        assertTrue("你好，李焕英".sameTitleAs("你好,李焕英"))
        assertTrue("Spider-Man: Across the Spider-Verse".sameTitleAs("spider man across the spider verse"))
        assertTrue("ＳＰＹ×ＦＡＭＩＬＹ".sameTitleAs("Spy Family"))
    }
}
