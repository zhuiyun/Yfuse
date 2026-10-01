package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlayerDanmakuCoordinatorTest {
    private fun at(vararg lines: Pair<Long, String>) = lines.map { (timeMs, text) -> DanmakuComment(timeMs, text) }

    @Test
    fun a_sent_line_goes_in_at_its_moment_after_what_is_already_there() {
        val loaded = at(1_000L to "早", 2_000L to "同时", 3_000L to "晚")
        assertEquals(
            listOf("早", "同时", "我说的", "晚"),
            loaded.withSentLines(at(2_000L to "我说的")).map { it.text },
        )
        // Before everything, after everything, and into nothing at all.
        assertEquals(
            listOf("先", "早", "同时", "晚", "后"),
            loaded.withSentLines(at(0L to "先", 9_000L to "后")).map { it.text },
        )
        assertEquals(listOf("独一句"), emptyList<DanmakuComment>().withSentLines(at(5L to "独一句")).map { it.text })
        assertSame(loaded, loaded.withSentLines(emptyList()))
    }

    @Test
    fun the_servers_copy_of_a_sent_line_gives_way_to_the_line_already_on_screen() {
        // The server rounds the time it was given to hundredths of a second.
        val refetched = at(12_000L to "前面", 12_340L to "我来了", 12_500L to "别人", 20_000L to "我来了")
        val sent = at(12_345L to "我来了")
        assertEquals(listOf("前面", "别人", "我来了"), refetched.withoutEchoesOf(sent).map { it.text })
        assertEquals(listOf(12_000L, 12_500L, 20_000L), refetched.withoutEchoesOf(sent).map { it.timeMs })
        // A refetch that does not carry it yet loses nothing.
        val stale = at(12_000L to "前面")
        assertSame(stale, stale.withoutEchoesOf(sent))
    }

    @Test
    fun two_identical_lines_sent_take_away_one_copy_each() {
        val refetched = at(5_000L to "哈哈", 5_010L to "哈哈", 5_020L to "哈哈")
        val sent = at(5_000L to "哈哈", 5_004L to "哈哈")
        assertEquals(listOf(5_020L), refetched.withoutEchoesOf(sent).map { it.timeMs })
    }
}
