package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCacheTest {

    private fun item(id: String) = MediaItem(
        id = id,
        title = "Title $id",
        subtitle = null,
        type = "Movie",
        posterItemId = id,
        posterTag = "tag$id",
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
    )

    @Test
    fun round_trips_content_per_server() {
        val cache = LibraryCache(MapSettings())
        val content = HomeContent(
            featured = listOf(item("a")),
            resume = listOf(item("b")),
            rows = listOf(HomeRow("lib1", "电影", listOf(item("c")), totalCount = 99)),
            counts = LibraryCounts(movieCount = 42, seriesCount = 7),
        )

        cache.write("server1", content)

        val restored = cache.read("server1")
        assertEquals(content, restored)
        // Servers do not share a shelf.
        assertNull(cache.read("server2"))
    }

    @Test
    fun trims_rows_so_the_stored_blob_stays_small() {
        val cache = LibraryCache(MapSettings())
        val many = (1..60).map { item("i$it") }

        cache.write(
            "server1",
            HomeContent(
                featured = many,
                resume = many,
                rows = (1..30).map { HomeRow("lib$it", "行 $it", many) },
            ),
        )

        val restored = requireNotNull(cache.read("server1"))
        assertEquals(8, restored.featured.size)
        assertEquals(12, restored.resume.size)
        assertEquals(12, restored.rows.size)
        assertTrue(restored.rows.all { it.items.size == 20 })
    }

    @Test
    fun empty_content_clears_the_entry_rather_than_storing_nothing() {
        val cache = LibraryCache(MapSettings())
        cache.write("server1", HomeContent(featured = listOf(item("a"))))

        cache.write("server1", HomeContent())

        assertNull(cache.read("server1"))
    }

    @Test
    fun unreadable_content_is_discarded_instead_of_failing_every_launch() {
        val settings = MapSettings()
        val cache = LibraryCache(settings)
        settings.putString("library.cache.server1", "{ not json")

        assertNull(cache.read("server1"))
        // Dropped on the first failed read, so the next launch doesn't retry the same blob.
        assertNull(settings.getStringOrNull("library.cache.server1"))
    }
}
