package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbHomeCacheTest {
    @Test
    fun recommendation_cache_is_bounded_and_round_trips() {
        val cache = TmdbHomeCache(MapSettings())
        cache.write(
            TmdbHome(
                featured = List(30) { item(it) },
                rows =
                    List(6) { row ->
                        TmdbRow("row-$row", List(30) { item(row * 100 + it) })
                    },
            ),
        )

        val restored = cache.read()!!

        assertEquals(21, restored.featured.size)
        assertEquals(4, restored.rows.size)
        assertEquals(listOf(24, 24, 24, 24), restored.rows.map { it.items.size })
    }

    @Test
    fun unreadable_recommendation_cache_is_removed() {
        val settings =
            MapSettings().apply {
                putString("tmdb.home.cache.v1", "not-json")
            }
        val cache = TmdbHomeCache(settings)

        assertNull(cache.read())
        assertNull(settings.getStringOrNull("tmdb.home.cache.v1"))
    }

    @Test
    fun recommendation_cache_expires_after_seven_days() {
        val settings = MapSettings()
        TmdbHomeCache(settings, today = { "2026-08-01" }).write(
            TmdbHome(featured = listOf(item(1))),
        )

        val cache = TmdbHomeCache(settings, today = { "2026-08-09" })

        assertNull(cache.read())
        assertNull(settings.getStringOrNull("tmdb.home.cache.v1"))
    }

    @Test
    fun recommendation_cache_bounds_text_and_serialized_size() {
        val settings = MapSettings()
        val cache = TmdbHomeCache(settings, today = { "2026-08-07" })
        cache.write(
            TmdbHome(
                featured =
                    listOf(
                        item(1).copy(
                            title = "t".repeat(2_000),
                            overview = "o".repeat(20_000),
                        ),
                    ),
            ),
        )

        val restored = cache.read()!!.featured.single()
        assertEquals(200, restored.title.length)
        assertEquals(1_500, restored.overview?.length)
        assertTrue(settings.getString("tmdb.home.cache.v1", "").length <= 512_000)
    }

    @Test
    fun failing_settings_read_and_cleanup_are_a_cache_miss() {
        val settings =
            object : Settings by MapSettings() {
                override fun getStringOrNull(key: String): String? = error("Storage unavailable")

                override fun remove(key: String) = error("Storage remains unavailable")
            }

        assertNull(TmdbHomeCache(settings).readCached())
    }

    @Test
    fun malformed_and_oversized_entries_remain_cache_misses_when_removal_fails() {
        for (raw in listOf("not-json", "x".repeat(512_001))) {
            val delegate = MapSettings().apply { putString("tmdb.home.cache.v1", raw) }
            val settings =
                object : Settings by delegate {
                    override fun remove(key: String) = error("Read-only storage")
                }

            assertNull(TmdbHomeCache(settings).readCached())
        }
    }

    @Test
    fun cancellation_from_read_cleanup_and_write_is_preserved() {
        val readCancelled =
            object : Settings by MapSettings() {
                override fun getStringOrNull(key: String): String? = throw CancellationException("Read cancelled")
            }
        assertFailsWith<CancellationException> { TmdbHomeCache(readCancelled).readCached() }

        val invalid = MapSettings().apply { putString("tmdb.home.cache.v1", "not-json") }
        val cleanupCancelled =
            object : Settings by invalid {
                override fun remove(key: String): Unit = throw CancellationException("Cleanup cancelled")
            }
        assertFailsWith<CancellationException> { TmdbHomeCache(cleanupCancelled).readCached() }

        val writeCancelled =
            object : Settings by MapSettings() {
                override fun putString(
                    key: String,
                    value: String,
                ): Unit = throw CancellationException("Write cancelled")
            }
        assertFailsWith<CancellationException> {
            TmdbHomeCache(writeCancelled).write(TmdbHome(featured = listOf(item(1))))
        }
    }

    private fun item(id: Int) =
        TmdbItem(
            id = id,
            title = "item-$id",
            overview = "overview-$id",
            posterPath = "/$id.jpg",
            backdropPath = "/$id-backdrop.jpg",
            year = "2026",
            mediaType = "movie",
            rating = 8.0,
        )
}
