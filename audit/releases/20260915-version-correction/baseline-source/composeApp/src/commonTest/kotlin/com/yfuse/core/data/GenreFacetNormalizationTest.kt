package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class GenreFacetNormalizationTest {
    @Test
    fun bilingual_genres_prefer_real_chinese_server_entry() {
        assertEquals(
            listOf("动作", "Comedy", "剧情", "纪录片"),
            dedupeBilingualGenreLabels(
                listOf("Action", "动作", "Comedy", "Drama", "剧情", "Documentary", "纪录片"),
            ),
        )
    }

    @Test
    fun english_genre_is_kept_when_server_has_no_chinese_twin() {
        assertEquals(
            listOf("Action", "Comedy", "Science Fiction"),
            dedupeBilingualGenreLabels(listOf("Action", "Comedy", "Science Fiction")),
        )
    }

    @Test
    fun duplicate_spelling_is_removed_without_changing_query_value() {
        assertEquals(
            listOf("动作", "喜剧"),
            dedupeBilingualGenreLabels(listOf("动作", "动作", "喜剧")),
        )
    }
}
