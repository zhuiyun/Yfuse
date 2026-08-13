package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.MediaItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCacheTest {
    private fun item(id: String) =
        MediaItem(
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
        val content =
            HomeContent(
                featured = listOf(item("a")),
                resume = listOf(item("b")),
                rows = listOf(HomeRow("lib1", "电影", listOf(item("c")), totalCount = 99)),
                counts = LibraryCounts(movieCount = 42, seriesCount = 7),
            )

        cache.write("server1", content, updatedAtEpochMs = 1_700_000_000_000L)
        cache.write(
            "server2",
            HomeContent(featured = listOf(item("other"))),
            updatedAtEpochMs = 1_700_000_000_001L,
        )

        val restored = requireNotNull(cache.readSnapshot("server1"))
        assertEquals(content, restored.content)
        assertEquals(1_700_000_000_000L, restored.updatedAtEpochMs)
        // Servers do not share either a shelf or its freshness timestamp.
        val other = requireNotNull(cache.readSnapshot("server2"))
        assertEquals(
            "other",
            other.content.featured
                .single()
                .id,
        )
        assertEquals(1_700_000_000_001L, other.updatedAtEpochMs)
    }

    @Test
    fun legacyRawContentRemainsReadableWithUnknownFreshness() {
        val settings = MapSettings()
        val cache = LibraryCache(settings)
        val content = HomeContent(featured = listOf(item("legacy")))
        settings.putString(
            "library.cache.server1",
            Json.encodeToString(HomeContent.serializer(), content),
        )

        val restored = requireNotNull(cache.readSnapshot("server1"))

        assertEquals(content, restored.content)
        assertNull(restored.updatedAtEpochMs)
        assertEquals(content, cache.read("server1"))
    }

    @Test
    fun pre_container_cache_shape_uses_empty_container_defaults() {
        val settings = MapSettings()
        settings.putString(
            "library.cache.server1",
            """{"rows":[{"libraryId":"old","title":"旧媒体库","items":[]}]}""",
        )

        val restored = requireNotNull(LibraryCache(settings).readSnapshot("server1"))

        assertEquals(
            "old",
            restored.content.rows
                .single()
                .libraryId,
        )
        assertTrue(restored.content.collections.isEmpty())
        assertTrue(restored.content.playlists.isEmpty())
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
            updatedAtEpochMs = 123L,
        )

        val restored = requireNotNull(cache.readSnapshot("server1"))
        assertEquals(123L, restored.updatedAtEpochMs)
        assertEquals(8, restored.content.featured.size)
        assertEquals(12, restored.content.resume.size)
        assertEquals(12, restored.content.rows.size)
        assertTrue(restored.content.rows.all { it.items.size == 20 })
    }

    @Test
    fun empty_content_clears_the_entry_rather_than_storing_nothing() {
        val cache = LibraryCache(MapSettings())
        cache.write("server1", HomeContent(featured = listOf(item("a"))), 123L)

        cache.write("server1", HomeContent(), 456L)

        assertNull(cache.readSnapshot("server1"))
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

    @Test
    fun malformedV2EnvelopeDoesNotFallBackToAnEmptyLegacyShape() {
        val settings = MapSettings()
        val cache = LibraryCache(settings)
        settings.putString("library.cache.server1", """{"v":2,"updatedAt":123}""")

        assertNull(cache.readSnapshot("server1"))
        assertNull(settings.getStringOrNull("library.cache.server1"))
    }
}
