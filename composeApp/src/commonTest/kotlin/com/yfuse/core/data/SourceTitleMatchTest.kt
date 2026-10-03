package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceTitleMatchTest {
    @Test
    fun the_same_words_match_whatever_their_punctuation_or_case() {
        assertTrue(sameSourceTitle("总裁，请签字！", "总裁 请签字"))
        assertTrue(sameSourceTitle("The Office", "the office"))
    }

    @Test
    fun a_similar_name_is_another_show() {
        assertFalse(sameSourceTitle("总裁请签字2", "总裁请签字"))
        assertFalse(sameSourceTitle("闪婚后，傅先生马甲藏不住了", "闪婚"))
        assertFalse(sameSourceTitle(null, "总裁请签字"))
        assertFalse(sameSourceTitle("！！", "？？"))
    }
}
