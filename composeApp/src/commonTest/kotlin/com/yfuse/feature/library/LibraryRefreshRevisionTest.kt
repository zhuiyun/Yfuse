package com.yfuse.feature.library

import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LibraryRefreshRevisionTest {
    @Test
    fun the_same_titles_with_new_progress_are_not_new_content() {
        val before = content(resume = listOf(item("a", played = 10.0)))
        val after = content(resume = listOf(item("a", played = 60.0)))

        assertEquals(libraryRefreshRevision(before), libraryRefreshRevision(after))
    }

    @Test
    fun a_title_added_anywhere_on_the_page_is_new_content() {
        val before = libraryRefreshRevision(content())

        assertNotEquals(before, libraryRefreshRevision(content(featured = listOf(item("f"), item("g")))))
        assertNotEquals(before, libraryRefreshRevision(content(resume = listOf(item("a"), item("b")))))
        assertNotEquals(
            before,
            libraryRefreshRevision(content(rows = listOf(HomeRow("movies", "电影", listOf(item("m"), item("n")))))),
        )
    }

    @Test
    fun a_title_moving_between_hero_and_history_is_new_content() {
        assertNotEquals(
            libraryRefreshRevision(content(featured = listOf(item("x")), resume = emptyList())),
            libraryRefreshRevision(content(featured = emptyList(), resume = listOf(item("x")))),
        )
    }

    private fun content(
        featured: List<MediaItem> = listOf(item("f")),
        resume: List<MediaItem> = listOf(item("a")),
        rows: List<HomeRow> = listOf(HomeRow("movies", "电影", listOf(item("m")))),
    ): HomeContent = HomeContent(featured = featured, resume = resume, rows = rows)

    private fun item(
        id: String,
        played: Double? = null,
    ): MediaItem =
        MediaItem(
            id = id,
            title = id,
            subtitle = null,
            type = "Movie",
            posterItemId = id,
            posterTag = null,
            backdropItemId = null,
            backdropTag = null,
            playedPercentage = played,
        )
}
