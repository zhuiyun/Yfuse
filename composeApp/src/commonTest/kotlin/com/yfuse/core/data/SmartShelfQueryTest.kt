package com.yfuse.core.data

import com.yfuse.core.model.LibrarySort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartShelfQueryTest {
    private val drama =
        SmartPlaylist(
            name = "甜宠短剧",
            serverId = "s1",
            libraryId = "lib-drama",
            genre = "爱情",
            watchStatus = "Unplayed",
            sort = "Rating",
        )

    @Test
    fun a_rule_that_is_one_library_listing_becomes_a_shelf() {
        assertEquals(
            SmartShelfQuery("s1", "lib-drama", "爱情", LibrarySort.Rating, unplayedOnly = true),
            drama.libraryShelf(),
        )
        assertEquals(LibrarySort.RecentlyAdded, drama.copy(sort = "Relevance").libraryShelf()?.sort)
    }

    @Test
    fun anything_the_listing_cannot_ask_for_keeps_the_rule_a_chip() {
        assertNull(drama.copy(query = "总裁").libraryShelf())
        assertNull(drama.copy(year = 2026).libraryShelf())
        assertNull(drama.copy(type = "Series").libraryShelf())
        assertNull(drama.copy(watchStatus = "Resumable").libraryShelf())
        assertNull(drama.copy(libraryId = null).libraryShelf())
        assertNull(drama.copy(serverId = null).libraryShelf())
    }
}
