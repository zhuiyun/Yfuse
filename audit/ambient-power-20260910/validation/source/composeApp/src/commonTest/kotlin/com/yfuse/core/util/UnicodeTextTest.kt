package com.yfuse.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UnicodeTextTest {
    @Test
    fun emoji_sequences_count_as_visible_characters() {
        assertEquals(1, "😀".graphemeCount())
        assertEquals(1, "👍🏽".graphemeCount())
        assertEquals(1, "👨‍👩‍👧‍👦".graphemeCount())
        assertEquals(1, "🇨🇳".graphemeCount())
        assertEquals(1, "1️⃣".graphemeCount())
    }

    @Test
    fun truncation_never_splits_an_emoji_sequence() {
        val text = "你好👨‍👩‍👧‍👦🍿"
        assertEquals(4, text.graphemeCount())
        assertEquals("你好👨‍👩‍👧‍👦", text.takeGraphemes(3))
        assertEquals("😀😀", "😀😀😀".takeGraphemesWithinUtf8Bytes(8))
    }
}
