package com.yfuse.tv.integration

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.ceil

/** Provider identity is part of every key so equal item ids on Emby, Jellyfin and Plex never collide. */
@Serializable
enum class TvMediaProvider {
    Emby,
    Jellyfin,
    Plex,
}

/**
 * One authenticated media-server lane.
 *
 * [serverId] and [profileId] stay inside Yfuse's private storage. Only [opaqueLaneId] is written
 * to a launcher URI or TvProvider row.
 */
@Serializable
data class ContinueWatchingScope(
    val provider: TvMediaProvider,
    val serverId: String,
    val profileId: String,
) {
    init {
        require(serverId.isNotBlank())
        require(profileId.isNotBlank())
    }

    val opaqueLaneId: String
        get() = stableOpaqueId("lane", provider.name, serverId, profileId)
}

@Serializable
data class ContinueWatchingIdentity(
    val scope: ContinueWatchingScope,
    val itemId: String,
) {
    init {
        require(itemId.isNotBlank())
    }

    /** Stable, credential-free id used as TvProvider's internal provider id. */
    val platformId: String
        get() = "yfuse.${stableOpaqueId("item", scope.provider.name, scope.serverId, scope.profileId, itemId)}"
}

@Serializable
enum class ContinueWatchingMediaType {
    Movie,
    Episode,
}

/** Credential-free snapshot safe to persist and hand to a system surface. */
@Serializable
data class ContinueWatchingEntry(
    val identity: ContinueWatchingIdentity,
    val mediaType: ContinueWatchingMediaType,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastEngagementEpochMs: Long,
    /** Already stripped of access-token query parameters before it reaches this model. */
    val posterArtUri: String? = null,
)

data class ContinueWatchingObservation(
    val entry: ContinueWatchingEntry,
    val explicitlyCompleted: Boolean = false,
    /** A new playback generation below the eligibility threshold removes an old stale position. */
    val startedNewGeneration: Boolean = false,
)

sealed interface ContinueWatchingDecision {
    data class Upsert(
        val entry: ContinueWatchingEntry,
    ) : ContinueWatchingDecision

    data class Delete(
        val identity: ContinueWatchingIdentity,
    ) : ContinueWatchingDecision

    data object Ignore : ContinueWatchingDecision
}

/**
 * Google TV policy shared by event ingestion, WorkManager reconciliation and tests.
 *
 * Movies become eligible at the earlier of 3% or two minutes; episodes at two minutes. A
 * completed item is removed at the same 95% boundary used by Yfuse playback sync. Five most
 * recent entries are published, but identities remain isolated by provider/server/profile.
 */
class ContinueWatchingPolicy(
    private val maxPublishedEntries: Int = 5,
    private val maxEntriesPerScope: Int = 5,
) {
    init {
        require(maxPublishedEntries in 1..10)
        require(maxEntriesPerScope in 1..20)
    }

    fun decide(observation: ContinueWatchingObservation): ContinueWatchingDecision {
        val entry = observation.entry
        if (entry.title.isBlank() || entry.durationMs < 0L || entry.positionMs < 0L) {
            return ContinueWatchingDecision.Ignore
        }
        val duration = entry.durationMs.coerceAtLeast(0L)
        val position =
            if (duration > 0L) {
                entry.positionMs.coerceIn(0L, duration)
            } else {
                entry.positionMs
            }
        val completed =
            observation.explicitlyCompleted ||
                duration > 0L && position >= (duration * COMPLETED_RATIO).toLong()
        if (completed) return ContinueWatchingDecision.Delete(entry.identity)

        val threshold = eligibilityThresholdMs(entry.mediaType, duration)
        if (position < threshold) {
            return if (observation.startedNewGeneration) {
                ContinueWatchingDecision.Delete(entry.identity)
            } else {
                ContinueWatchingDecision.Ignore
            }
        }
        return ContinueWatchingDecision.Upsert(
            entry.copy(
                positionMs = position,
                durationMs = duration,
                title = entry.title.trim(),
                subtitle = entry.subtitle?.trim()?.takeIf(String::isNotBlank),
                description = entry.description?.trim()?.takeIf(String::isNotBlank),
            ),
        )
    }

    fun selectForPublication(entries: Collection<ContinueWatchingEntry>): List<ContinueWatchingEntry> =
        entries
            .asSequence()
            .filter { decide(ContinueWatchingObservation(it)) is ContinueWatchingDecision.Upsert }
            .distinctBy(ContinueWatchingEntry::identity)
            .sortedByDescending(ContinueWatchingEntry::lastEngagementEpochMs)
            .take(maxPublishedEntries)
            .toList()

    fun selectForScope(
        scope: ContinueWatchingScope,
        entries: Collection<ContinueWatchingEntry>,
    ): List<ContinueWatchingEntry> =
        entries
            .asSequence()
            .filter { it.identity.scope == scope }
            .sortedByDescending(ContinueWatchingEntry::lastEngagementEpochMs)
            .distinctBy(ContinueWatchingEntry::identity)
            .take(maxEntriesPerScope)
            .toList()

    private fun eligibilityThresholdMs(
        mediaType: ContinueWatchingMediaType,
        durationMs: Long,
    ): Long =
        when (mediaType) {
            ContinueWatchingMediaType.Episode -> MINIMUM_PLAYED_MS
            ContinueWatchingMediaType.Movie -> {
                if (durationMs <= 0L) {
                    MINIMUM_PLAYED_MS
                } else {
                    minOf(MINIMUM_PLAYED_MS, ceil(durationMs * MOVIE_MINIMUM_RATIO).toLong())
                }
            }
        }

    private companion object {
        const val MINIMUM_PLAYED_MS = 120_000L
        const val MOVIE_MINIMUM_RATIO = 0.03
        const val COMPLETED_RATIO = 0.95
    }
}

enum class ContinueWatchingBackend {
    Engage,
    WatchNext,
}

sealed interface ContinueWatchingPublishResult {
    data class Published(
        val backend: ContinueWatchingBackend,
        val publishedCount: Int,
        val deletedCount: Int = 0,
        /** Optional best-effort surface (for example Preview Channel) did not update. */
        val degradedSurface: String? = null,
    ) : ContinueWatchingPublishResult

    data class Unavailable(
        val backend: ContinueWatchingBackend,
        val reason: String,
        /** Terminal means retrying the same APK/device cannot make this backend available. */
        val terminal: Boolean,
    ) : ContinueWatchingPublishResult

    data class Failed(
        val backend: ContinueWatchingBackend,
        val reason: String,
        val retryable: Boolean,
    ) : ContinueWatchingPublishResult
}

/** Snapshot semantics match Engage: every successful call replaces the app's full continuation list. */
fun interface ContinueWatchingPublisher {
    suspend fun replace(entries: List<ContinueWatchingEntry>): ContinueWatchingPublishResult
}

internal fun stableOpaqueId(
    domain: String,
    vararg components: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("yfuse-tv:$domain:v1".encodeToByteArray())
    components.forEach { component ->
        digest.update(0.toByte())
        digest.update(component.encodeToByteArray())
    }
    return Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(digest.digest())
        .take(22)
}
