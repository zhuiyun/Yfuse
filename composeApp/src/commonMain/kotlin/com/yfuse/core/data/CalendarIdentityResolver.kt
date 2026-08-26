package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.MediaDetail

data class TmdbSeriesIdentityCandidate(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val popularity: Double = 0.0,
)

class CalendarIdentityAmbiguousException(
    val candidates: List<TmdbSeriesIdentityCandidate>,
) : Exception("找到多个可能的剧集，请选择正确条目")

class CalendarIdentityResolver(
    private val tmdb: TmdbRepository,
    private val settings: Settings,
) {
    suspend fun resolve(
        detail: MediaDetail,
        serverId: String?,
    ): Result<Int> {
        require(detail.type.equals("Series", ignoreCase = true)) { "只有剧集支持播出日历" }
        return resolveIdentity(
            itemId = detail.id,
            title = detail.title,
            year = detail.year,
            providerIds = detail.providerIds,
            serverId = serverId,
        )
    }

    /** Exact automatic identity lookup for recently added/favourite library series. */
    suspend fun resolve(
        identity: LibrarySeriesIdentity,
        serverId: String?,
    ): Result<Int> =
        resolveIdentity(
            itemId = identity.itemId,
            title = identity.title,
            year = identity.year,
            providerIds = identity.providerIds,
            serverId = serverId,
        )

    private suspend fun resolveIdentity(
        itemId: String,
        title: String,
        year: Int?,
        providerIds: Map<String, String>,
        serverId: String?,
    ): Result<Int> {
        val itemKey = itemKey(serverId, itemId)
        settings.getIntOrNull(itemKey)?.takeIf { it > 0 }?.let { return Result.success(it) }

        providerIds.tmdbId()?.let { tmdbId ->
            remember(serverId, itemId, tmdbId)
            return Result.success(tmdbId)
        }

        externalIdentity(providerIds)?.let { (id, source) ->
            tmdb.findSeriesByExternalId(id, source).getOrNull()?.let { candidate ->
                remember(serverId, itemId, candidate.tmdbId)
                return Result.success(candidate.tmdbId)
            }
        }

        val candidates =
            tmdb
                .searchSeriesIdentityCandidates(title, year)
                .getOrElse { return Result.failure(it) }
        val normalizedTitle = normalizeIdentityTitle(title)
        val exact =
            candidates.filter { candidate ->
                normalizeIdentityTitle(candidate.title) == normalizedTitle &&
                    (year == null || candidate.year == null || kotlin.math.abs(year - candidate.year) <= 1)
            }
        if (exact.size == 1) {
            val tmdbId = exact.single().tmdbId
            remember(serverId, itemId, tmdbId)
            return Result.success(tmdbId)
        }
        return Result.failure(CalendarIdentityAmbiguousException((exact.ifEmpty { candidates }).take(5)))
    }

    /** Returns candidates even when an automatic or saved mapping already exists. */
    suspend fun candidates(detail: MediaDetail): Result<List<TmdbSeriesIdentityCandidate>> {
        require(detail.type.equals("Series", ignoreCase = true)) { "只有剧集支持播出日历" }
        return tmdb
            .searchSeriesIdentityCandidates(detail.title, detail.year)
            .map { candidates ->
                val normalizedTitle = normalizeIdentityTitle(detail.title)
                candidates
                    .sortedWith(
                        compareByDescending<TmdbSeriesIdentityCandidate> {
                            normalizeIdentityTitle(it.title) == normalizedTitle
                        }.thenByDescending { candidate ->
                            detail.year != null &&
                                candidate.year != null &&
                                kotlin.math.abs(detail.year - candidate.year) <= 1
                        }.thenByDescending(TmdbSeriesIdentityCandidate::popularity),
                    ).distinctBy(TmdbSeriesIdentityCandidate::tmdbId)
                    .take(8)
            }
    }

    fun remember(
        serverId: String?,
        itemId: String,
        tmdbId: Int,
    ) {
        require(tmdbId > 0)
        val oldTmdbId = settings.getIntOrNull(itemKey(serverId, itemId))
        if (oldTmdbId != null && oldTmdbId != tmdbId) {
            settings
                .getStringOrNull(reverseKey(serverId, oldTmdbId))
                ?.takeIf { it == itemId }
                ?.let { settings.remove(reverseKey(serverId, oldTmdbId)) }
        }
        val oldItemId = settings.getStringOrNull(reverseKey(serverId, tmdbId))
        if (!oldItemId.isNullOrBlank() && oldItemId != itemId) {
            settings.remove(itemKey(serverId, oldItemId))
        }
        settings.putInt(itemKey(serverId, itemId), tmdbId)
        settings.putString(reverseKey(serverId, tmdbId), itemId)
    }

    fun forget(
        serverId: String?,
        itemId: String,
        tmdbId: Int? = null,
    ) {
        val resolvedTmdbId = tmdbId ?: settings.getIntOrNull(itemKey(serverId, itemId))
        settings.remove(itemKey(serverId, itemId))
        resolvedTmdbId?.let { id ->
            settings
                .getStringOrNull(reverseKey(serverId, id))
                ?.takeIf { it == itemId }
                ?.let { settings.remove(reverseKey(serverId, id)) }
        }
    }

    fun mappedSeriesItemId(
        serverId: String,
        tmdbId: Int,
    ): String? = settings.getStringOrNull(reverseKey(serverId, tmdbId))?.takeIf(String::isNotBlank)

    private fun externalIdentity(providerIds: Map<String, String>): Pair<String, String>? {
        val imdb = providerIds.entries.firstOrNull { it.key.equals("imdb", true) }?.value
        if (!imdb.isNullOrBlank()) return imdb to "imdb_id"
        val tvdb = providerIds.entries.firstOrNull { it.key.equals("tvdb", true) }?.value
        if (!tvdb.isNullOrBlank()) return tvdb to "tvdb_id"
        return null
    }

    private fun Map<String, String>.tmdbId(): Int? =
        entries.firstOrNull { it.key.equals("tmdb", true) }?.value?.toIntOrNull()

    private fun itemKey(
        serverId: String?,
        itemId: String,
    ) = "calendar.identity.item.${serverId.orEmpty().safeKey()}.$itemId"

    private fun reverseKey(
        serverId: String?,
        tmdbId: Int,
    ) = "calendar.identity.tmdb.${serverId.orEmpty().safeKey()}.$tmdbId"
}

internal fun normalizeIdentityTitle(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun String.safeKey(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }
