package com.yfuse.core.widget

import com.yfuse.core.data.TmdbSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetTitleMatchTest {
    private fun result(
        id: Int,
        title: String,
        year: Int? = null,
        kind: String = "movie",
    ) = TmdbSearchResult(id, kind, title, null, year, null, null, null, null, 0, 0.0)

    @Test fun exact_year_resolves_a_remake_but_missing_year_does_not_guess() {
        val source = WidgetMedia("source-id", "Film", year = "2026-01-01")
        val old = result(1, "Film", 1999)
        val new = result(2, "Film", 2026)
        assertEquals(new, source.exactDetailMatch(listOf(old, new)))
        assertNull(source.copy(year = "").exactDetailMatch(listOf(old, new)))
        assertNull(source.copy(year = "2025").exactDetailMatch(listOf(old, new)))
    }

    @Test fun original_titles_match_but_partial_names_and_wrong_media_types_do_not() {
        val source = WidgetMedia("source-id", "  THE FILM ", mediaType = "series")
        val series = result(2, "中文片名", 2026, "tv").copy(originalTitle = "The Film")
        assertEquals(series, source.exactDetailMatch(listOf(series)))
        assertNull(source.exactDetailMatch(listOf(series.copy(mediaType = "movie"))))
        assertNull(source.exactDetailMatch(listOf(series.copy(originalTitle = "The Film 2"))))
        assertNull(source.exactDetailMatch(listOf(series.copy(id = 0))))
    }

    @Test fun matching_result_keeps_real_identity_for_default_details() {
        val result = result(42, "Title", 2026).copy(posterPath = "/poster.jpg", overview = "Overview", rating = 8.0)
        val detail = result.widgetDetailItem()
        assertEquals(42, detail.id)
        assertEquals("2026", detail.year)
        assertEquals("movie", detail.mediaType)
        assertEquals("/poster.jpg", detail.posterPath)
        assertEquals("Overview", detail.overview)
        assertEquals(8.0, detail.rating)
    }
}
