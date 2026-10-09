package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeLabelsTest {
    @Test
    fun names_that_only_repeat_the_number_are_dropped() {
        listOf("第1集", "第01集", "第 1 集", "1", "01", "1集", "第一集", "EP01", "Ep.1", "E01", "Episode 1", "S01E01", "第1话")
            .forEach { name -> assertNull(episodeOwnName(name, 1), name) }
        assertNull(episodeOwnName("第十二集", 12))
        assertNull(episodeOwnName("第一百零五集", 105))
        assertNull(episodeOwnName("一零五", 105))
        assertNull(episodeOwnName("  ", 3))
        assertNull(episodeOwnName(null, 3))
    }

    @Test
    fun names_that_say_more_or_another_number_are_kept() {
        assertEquals("第1集（上）", episodeOwnName("第1集（上）", 1))
        assertEquals("婚礼", episodeOwnName(" 婚礼 ", 1))
        assertEquals("第2集", episodeOwnName("第2集", 1))
        assertEquals("Episode 1 - Pilot", episodeOwnName("Episode 1 - Pilot", 1))
        assertEquals("第1集", episodeOwnName("第1集", null))
    }

    @Test
    fun titles_join_the_number_and_a_name_that_adds_something() {
        assertEquals("第1集", episodeTitle(1, "第1集"))
        assertEquals("第1集 · 婚礼", episodeTitle(1, "婚礼"))
        assertEquals("第1集", episodeTitle(1, ""))
        assertEquals("E3.", episodeTitle(3, "03", separator = " ") { "E$it." })
        assertEquals("婚礼", episodeTitle(null, "婚礼"))
        assertEquals("", episodeTitle(null, " "))
    }

    @Test
    fun short_runtimes_keep_their_seconds() {
        assertEquals("45 秒", episodeRuntimeLabel(450_000_000L))
        assertEquals("1 分 35 秒", episodeRuntimeLabel(950_000_000L))
        assertEquals("2 分钟", episodeRuntimeLabel(1_200_000_000L))
        assertEquals("9 分 59 秒", episodeRuntimeLabel(5_990_000_000L))
        assertEquals("24 分钟", episodeRuntimeLabel(14_690_000_000L))
    }

    @Test
    fun an_unknown_runtime_falls_back_to_whole_minutes() {
        assertEquals("42 分钟", episodeRuntimeLabel(null, 42))
        assertNull(episodeRuntimeLabel(null, null))
        assertNull(episodeRuntimeLabel(0L, 0))
    }
}
