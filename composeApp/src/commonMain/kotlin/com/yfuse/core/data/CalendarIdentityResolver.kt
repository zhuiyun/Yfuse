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
) : Exception("请从服务器排期中选择正确剧集")

class CalendarIdentityResolver(
    private val schedules: OfficialAiringScheduleCatalog,
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

    /**
     * The viewer's own choice stands; after it, the server's TMDB id; after that, a unique
     * exact title in the publication.
     *
     * A title is only a guess — a 短剧 without a TMDB entry often shares its name with another
     * show — so a guessed identity, and one taken from a TMDB id the server has since changed,
     * is saved as inferred and checked again on every lookup instead of standing for good.
     */
    private suspend fun resolveIdentity(
        itemId: String,
        title: String,
        year: Int?,
        providerIds: Map<String, String>,
        serverId: String?,
    ): Result<Int> {
        val saved = settings.getIntOrNull(itemKey(serverId, itemId))?.takeIf { it > 0 }
        if (saved != null && settings.getStringOrNull(sourceKey(serverId, itemId)) == null) {
            return Result.success(saved)
        }

        providerIds.tmdbProviderId()?.let { tmdbId ->
            remember(serverId, itemId, tmdbId, IdentitySource.ServerId)
            return Result.success(tmdbId)
        }

        // Checking an earlier guess again uses the publication already held.
        if (saved == null) schedules.refreshIfDue()
        val candidates = schedules.identityCandidates(title)
        val normalizedTitle = normalizeIdentityTitle(title)
        val exact =
            candidates.filter { candidate ->
                normalizeIdentityTitle(candidate.title) == normalizedTitle &&
                    identityYearsAgree(year, candidate.year)
            }
        val guess =
            exact.singleOrNull()?.tmdbId?.takeIf { tmdbId ->
                // A show the viewer has already given to another series is not this one.
                val other = mappedSeriesItemId(serverId.orEmpty(), tmdbId)
                other == null || other == itemId || !chosenByViewer(serverId, other, tmdbId)
            }
        if (guess != null) {
            remember(serverId, itemId, guess, IdentitySource.Title)
            return Result.success(guess)
        }
        if (saved != null) {
            // The show has left the publication, which does not make the guess wrong.
            if (exact.isEmpty() && candidates.none { it.tmdbId == saved }) return Result.success(saved)
            forget(serverId, itemId, saved)
        }
        return Result.failure(CalendarIdentityAmbiguousException((exact.ifEmpty { candidates }).take(5)))
    }

    /** Returns candidates even when an automatic or saved mapping already exists. */
    suspend fun candidates(detail: MediaDetail): Result<List<TmdbSeriesIdentityCandidate>> {
        require(detail.type.equals("Series", ignoreCase = true)) { "只有剧集支持播出日历" }
        schedules.refreshIfDue()
        return Result.success(schedules.identityCandidates(detail.title))
    }

    /** The viewer's own choice, made on the detail page. */
    fun remember(
        serverId: String?,
        itemId: String,
        tmdbId: Int,
    ) = remember(serverId, itemId, tmdbId, IdentitySource.Viewer)

    /** A library series matched to a scheduled show by its title alone. */
    internal fun rememberTitleMatch(
        serverId: String?,
        itemId: String,
        tmdbId: Int,
    ) = remember(serverId, itemId, tmdbId, IdentitySource.Title)

    /**
     * True when the viewer gave this series [tmdbId] themselves, which outranks the TMDB id the
     * server gives it. A mapping saved before sources were recorded counts as theirs.
     */
    fun chosenByViewer(
        serverId: String?,
        itemId: String,
        tmdbId: Int,
    ): Boolean =
        settings.getIntOrNull(itemKey(serverId, itemId)) == tmdbId &&
            settings.getStringOrNull(sourceKey(serverId, itemId)) == null

    private fun remember(
        serverId: String?,
        itemId: String,
        tmdbId: Int,
        source: IdentitySource,
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
            settings.remove(sourceKey(serverId, oldItemId))
        }
        settings.putInt(itemKey(serverId, itemId), tmdbId)
        settings.putString(reverseKey(serverId, tmdbId), itemId)
        val sourceKey = sourceKey(serverId, itemId)
        when (val stored = source.stored) {
            null -> settings.remove(sourceKey)
            else -> settings.putString(sourceKey, stored)
        }
    }

    fun forget(
        serverId: String?,
        itemId: String,
        tmdbId: Int? = null,
    ) {
        val resolvedTmdbId = tmdbId ?: settings.getIntOrNull(itemKey(serverId, itemId))
        settings.remove(itemKey(serverId, itemId))
        settings.remove(sourceKey(serverId, itemId))
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

    private fun itemKey(
        serverId: String?,
        itemId: String,
    ) = "calendar.identity.item.${serverId.orEmpty().safeKey()}.$itemId"

    private fun reverseKey(
        serverId: String?,
        tmdbId: Int,
    ) = "calendar.identity.tmdb.${serverId.orEmpty().safeKey()}.$tmdbId"

    /** How an item's mapping was reached; absent for the viewer's own choice. */
    private fun sourceKey(
        serverId: String?,
        itemId: String,
    ) = "calendar.identity.source.${serverId.orEmpty().safeKey()}.$itemId"

    private enum class IdentitySource(
        val stored: String?,
    ) {
        Viewer(null),
        ServerId("server"),
        Title("title"),
    }
}

/** The TMDB id a media server gives an item, where it gives one. */
internal fun Map<String, String>.tmdbProviderId(): Int? =
    entries
        .firstOrNull { it.key.equals("tmdb", true) }
        ?.value
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

/**
 * A library year and a show's premiere year agree when either is unknown or they are a year
 * apart at most — a show that premiered on 31 December is still that year's show.
 */
internal fun identityYearsAgree(
    libraryYear: Int?,
    premiereYear: Int?,
): Boolean = libraryYear == null || premiereYear == null || kotlin.math.abs(libraryYear - premiereYear) <= 1

internal fun normalizeIdentityTitle(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

private fun String.safeKey(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }
