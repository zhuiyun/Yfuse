package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DanmakuSimilarRuleTest {
    private fun shown(
        texts: List<String>,
        blocked: List<String>,
    ): List<String> =
        DanmakuFilter
            .apply(
                comments = texts.mapIndexed { index, text -> DanmakuComment(index * 1_000L, text) },
                merge = false,
                blockedWords = blocked,
            ).map { it.text }

    @Test
    fun the_ways_of_typing_one_line_share_a_key() {
        assertEquals("前方高能", DanmakuFilter.similarKey("前方高能！！！"))
        assertEquals("前方高能", DanmakuFilter.similarKey("前方 高能"))
        assertEquals("awsl", DanmakuFilter.similarKey("ＡＷＳＬ!!!"))
        assertEquals("哈", DanmakuFilter.similarKey("哈哈哈哈哈"))
        assertEquals("哈", DanmakuFilter.similarKey("哈！哈！哈！"))
        assertEquals("23", DanmakuFilter.similarKey("2333333"))
        // Nothing but symbols keeps the symbols, one of each run: an emoji repeated is a run too.
        assertEquals("?", DanmakuFilter.similarKey("？？？"))
        assertEquals("😂", DanmakuFilter.similarKey("😂😂😂"))
    }

    @Test
    fun a_similar_rule_blocks_the_variants_and_nothing_that_merely_contains_them() {
        val rule = DanmakuFilter.similarRule("哈哈哈哈")
        assertEquals("≈哈", rule)
        assertEquals(
            listOf("哈哈哈这个角色太好笑了", "好看"),
            shown(listOf("哈哈", "哈哈哈哈哈哈！", "哈 哈 哈", "哈哈哈这个角色太好笑了", "好看"), listOf(rule!!)),
        )
    }

    @Test
    fun a_word_still_blocks_anywhere_in_the_line_beside_a_similar_rule() {
        assertEquals(
            listOf("画面真好"),
            shown(
                listOf("前方高能！！", "前方高能", "前面有剧透注意", "画面真好"),
                listOf("剧透", DanmakuFilter.similarRule("前方高能!")!!),
            ),
        )
    }

    @Test
    fun a_long_line_is_cut_to_fit_one_stored_word_and_still_blocks_its_copies() {
        val long = "这是一条非常非常长的刷屏弹幕内容用来测试屏蔽同类的规则是否仍然可以正确匹配复制粘贴的版本"
        val rule = DanmakuFilter.similarRule(long)!!
        assertEquals(MAX_DANMAKU_SYNC_BLOCKED_WORD_CHARS, rule.length)
        assertEquals(listOf("别的"), shown(listOf(long, "$long！！", "别的"), listOf(rule)))
    }

    @Test
    fun a_rule_for_nothing_is_not_made() {
        assertNull(DanmakuFilter.similarRule("   "))
    }
}
