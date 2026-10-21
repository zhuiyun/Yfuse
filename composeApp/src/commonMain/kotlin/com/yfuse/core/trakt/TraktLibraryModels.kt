package com.yfuse.core.trakt

import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalStamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

internal val PersonalCollection.traktPath: String
    get() =
        when (this) {
            PersonalCollection.Favorite -> "favorites"
            PersonalCollection.WatchLater -> "watchlist"
            PersonalCollection.History -> "history"
        }

internal val PersonalCollection.traktLabel: String
    get() =
        when (this) {
            PersonalCollection.Favorite -> "收藏"
            PersonalCollection.WatchLater -> "待看"
            PersonalCollection.History -> "观看历史"
        }

/** Episode IDs and series coordinates are deliberately separate namespaces. */
data class TraktLibraryMedia(
    val type: String,
    val ids: TraktIds,
    val showIds: TraktIds? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    internal fun aliases(): Set<String> {
        val direct = ids.aliases().map { "$type:$it" }
        val coordinates = showIds?.aliases().orEmpty().map { "episode:$it/s${season}e$episode" }
        return (direct + coordinates).toSet()
    }

    internal fun body(watchedAt: Long? = null): JsonObject {
        val value =
            buildJsonObject {
                if (ids.aliases().isNotEmpty()) put("ids", ids.body())
                if (watchedAt != null) put("watched_at", Instant.ofEpochMilli(watchedAt).toString())
            }
        if (ids.aliases().isNotEmpty()) {
            return buildJsonObject {
                put(
                    if (type ==
                        "movie"
                    ) {
                        "movies"
                    } else if (type == "show") {
                        "shows"
                    } else {
                        "episodes"
                    },
                    JsonArray(listOf(value)),
                )
            }
        }
        require(type == "episode" && showIds != null && season != null && episode != null)
        val episodeBody =
            buildJsonObject {
                put("number", episode)
                if (watchedAt != null) put("watched_at", Instant.ofEpochMilli(watchedAt).toString())
            }
        val seasonBody =
            buildJsonObject {
                put("number", season)
                put("episodes", JsonArray(listOf(episodeBody)))
            }
        val showBody =
            buildJsonObject {
                put("ids", showIds.body())
                put("seasons", JsonArray(listOf(seasonBody)))
            }
        return buildJsonObject { put("shows", JsonArray(listOf(showBody))) }
    }
}

private fun TraktIds.body() =
    buildJsonObject {
        trakt?.let { put("trakt", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb?.let { put("tvdb", it) }
        imdb?.let { put("imdb", it) }
    }

internal fun TraktIds.aliases(): Set<String> =
    buildSet {
        trakt?.takeIf { it > 0 }?.let { add("trakt:$it") }
        tmdb?.takeIf { it > 0 }?.let { add("tmdb:$it") }
        tvdb?.takeIf { it > 0 }?.let { add("tvdb:$it") }
        imdb?.takeIf { it.matches(Regex("tt[0-9]{1,16}")) }?.let { add("imdb:$it") }
    }

internal fun PersonalMediaRef.traktMedia(): TraktLibraryMedia? {
    val type =
        when (mediaType.lowercase()) {
            "movie" -> "movie"
            "series" -> "show"
            "episode" -> "episode"
            else -> return null
        }
    val coordinate = Regex("(.+)/s([0-9]+)e([0-9]+)").matchEntire(mediaKey)
    val key = coordinate?.groupValues?.get(1) ?: mediaKey
    val value = key.substringAfter(':', "")
    val ids =
        when (key.substringBefore(':').lowercase()) {
            "trakt" -> TraktIds(trakt = value.toLongOrNull()?.takeIf { it > 0 })
            "tmdb" -> TraktIds(tmdb = value.toIntOrNull()?.takeIf { it > 0 })
            "tvdb" -> TraktIds(tvdb = value.toLongOrNull()?.takeIf { it > 0 })
            "imdb" -> TraktIds(imdb = value.takeIf { it.matches(Regex("tt[0-9]{1,16}")) })
            else -> TraktIds(tmdb = tmdbId?.takeIf { it > 0 })
        }
    if (ids.aliases().isEmpty()) return null
    if (coordinate == null) return TraktLibraryMedia(type, ids)
    if (type != "episode") return null
    val season = coordinate.groupValues[2].toIntOrNull() ?: return null
    val number = coordinate.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return TraktLibraryMedia(type, TraktIds(), ids, season, number)
}

internal fun TraktListItem.libraryMedia(): TraktLibraryMedia? =
    when (type) {
        "movie" -> movie?.let { TraktLibraryMedia("movie", it.ids) }
        "show" -> show?.let { TraktLibraryMedia("show", it.ids) }
        "episode" ->
            episode?.takeIf { it.season >= 0 && it.number > 0 }?.let {
                TraktLibraryMedia("episode", it.ids, show?.ids, it.season, it.number)
            }
        else -> null
    }?.takeIf { it.aliases().isNotEmpty() }

data class TraktLibrarySnapshot(
    val scope: String,
    val profileId: String,
    val entries: List<PersonalEntry>,
)

interface TraktLibrarySink {
    val scopeToken: String
    val ownerIdentity: String

    fun snapshot(): TraktLibrarySnapshot

    fun isCurrent(
        snapshot: TraktLibrarySnapshot,
        entry: PersonalEntry,
    ): Boolean

    fun apply(
        snapshot: TraktLibrarySnapshot,
        previous: PersonalEntry?,
        incoming: PersonalEntry,
    ): PersonalEntry
}

@Serializable
internal data class TraktSyncMark(
    val stamp: PersonalStamp,
    val deleted: Boolean,
    val remotePresent: Boolean,
)

data class TraktSyncResult(
    val collection: PersonalCollection,
    val imported: Int = 0,
    val uploaded: Int = 0,
    val removed: Int = 0,
    val skipped: Int = 0,
    val unmatched: Int = 0,
    val error: String? = null,
) {
    val summary: String
        get() =
            "${collection.traktLabel}：接收 $imported，上传 $uploaded，移除 $removed，跳过 $skipped" +
                (if (unmatched > 0) "（其中 $unmatched 项无法匹配）" else "") +
                (error?.let { "；$it" } ?: "")
}

internal class TraktUnmatchedItemException : IllegalStateException("Trakt item not found")
