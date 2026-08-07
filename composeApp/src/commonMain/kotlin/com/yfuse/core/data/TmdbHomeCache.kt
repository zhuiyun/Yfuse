package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.isoDateDaysBefore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Last successful recommendation page, used while TMDB is temporarily unreachable. */
class TmdbHomeCache(
    private val settings: Settings,
    private val today: () -> String = { currentIsoDate() },
) {
    private companion object {
        const val KEY = "tmdb.home.cache.v1"
        const val MAX_CACHE_AGE_DAYS = 7
        const val MAX_SERIALIZED_CHARS = 512_000
        const val MAX_FEATURED = 21
        const val MAX_ROWS = 4
        const val MAX_ITEMS_PER_ROW = 24
        const val MAX_ROW_TITLE_CHARS = 80
        const val MAX_TITLE_CHARS = 200
        const val MAX_OVERVIEW_CHARS = 1_500
        const val MAX_PATH_CHARS = 300
        const val MAX_SHORT_FIELD_CHARS = 32
    }

    @Serializable
    private data class Entry(
        val savedOn: String,
        val content: TmdbHome,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun read(): TmdbHome? {
        val raw = settings.getStringOrNull(KEY) ?: return null
        if (raw.length > MAX_SERIALIZED_CHARS) {
            settings.remove(KEY)
            AppLog.warning(
                category = "feature.home",
                event = "recommendations_cache_oversized",
                message = "Oversized home recommendations cache was discarded",
            )
            return null
        }
        return runCatching {
            val entry = json.decodeFromString(Entry.serializer(), raw)
            val currentDate = today()
            val oldestAcceptedDate = isoDateDaysBefore(currentDate, MAX_CACHE_AGE_DAYS)
            check(entry.savedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            check(entry.savedOn >= oldestAcceptedDate && entry.savedOn <= currentDate)
            entry.content
        }.onFailure {
            settings.remove(KEY)
            AppLog.warning(
                category = "feature.home",
                event = "recommendations_cache_unreadable",
                message = "Cached home recommendations were discarded",
                throwable = it,
            )
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    fun write(content: TmdbHome) {
        if (content.isEmpty) return
        val trimmed = content.copy(
            featured = content.featured.take(MAX_FEATURED).map { it.trimForCache() },
            rows = content.rows.take(MAX_ROWS).map { row ->
                row.copy(
                    title = row.title.take(MAX_ROW_TITLE_CHARS),
                    items = row.items.take(MAX_ITEMS_PER_ROW).map { it.trimForCache() },
                )
            },
        )
        runCatching {
            val encoded = json.encodeToString(
                Entry.serializer(),
                Entry(savedOn = today(), content = trimmed),
            )
            check(encoded.length <= MAX_SERIALIZED_CHARS) {
                "Home recommendations cache exceeds its size limit"
            }
            settings.putString(KEY, encoded)
        }.onFailure {
            AppLog.warning(
                category = "feature.home",
                event = "recommendations_cache_write_failed",
                message = "Home recommendations could not be cached",
                throwable = it,
            )
        }
    }

    private fun TmdbItem.trimForCache(): TmdbItem = copy(
        title = title.take(MAX_TITLE_CHARS),
        overview = overview?.take(MAX_OVERVIEW_CHARS),
        posterPath = posterPath?.take(MAX_PATH_CHARS),
        backdropPath = backdropPath?.take(MAX_PATH_CHARS),
        year = year?.take(MAX_SHORT_FIELD_CHARS),
        mediaType = mediaType.take(MAX_SHORT_FIELD_CHARS),
        releaseDate = releaseDate?.take(MAX_SHORT_FIELD_CHARS),
        genreIds = genreIds.take(MAX_SHORT_FIELD_CHARS),
        originalLanguage = originalLanguage?.take(MAX_SHORT_FIELD_CHARS),
    )
}
