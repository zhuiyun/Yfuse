package com.yfuse.core.data

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.sync.watchKey

/** Coarse connection class used when selecting a source; it never claims more than we know. */
enum class PlaybackNetworkClass { Unmetered, Metered, Offline, Unknown }

/** One search hit together with the server that owns its images and detail route. */
data class CrossServerMediaHit(
    val serverId: String,
    val serverName: String,
    val item: MediaItem,
)

/** A single logical title card backed by one or more server-specific copies. */
data class CrossServerMediaGroup(
    val identity: String,
    val recommended: CrossServerMediaHit,
    val copies: List<CrossServerMediaHit>,
)

/**
 * Stable media identity for aggregation.
 *
 * Provider ids are authoritative. The fallback deliberately includes type/year/title: unlike a
 * bare title it does not merge a film, remake and series that happen to share one display name.
 */
fun MediaItem.crossServerIdentity(): String {
    val providerKey = providerIds.watchKey(id)
    if (!providerKey.startsWith("emby:")) return "$type:$providerKey"
    return listOf(
        "metadata",
        type.trim().lowercase(),
        year?.toString().orEmpty(),
        title.trim().lowercase(),
    ).joinToString(":")
}

/** Groups duplicate hits into one card while retaining every concrete route behind it. */
fun aggregateCrossServerMedia(
    hits: List<CrossServerMediaHit>,
    health: Map<String, ServerHealth> = emptyMap(),
): List<CrossServerMediaGroup> =
    hits
        .groupBy { it.item.crossServerIdentity() }
        .map { (identity, copies) ->
            val ordered =
                copies.sortedWith { left, right ->
                    compareSearchHit(left, right, health)
                }
            CrossServerMediaGroup(identity, ordered.first(), ordered)
        }.sortedWith(
            compareByDescending<CrossServerMediaGroup> { it.copies.size }
                .thenByDescending { it.recommended.item.communityRating ?: Double.NEGATIVE_INFINITY }
                .thenBy {
                    it.recommended.item.title
                        .lowercase()
                },
        )

private fun compareSearchHit(
    left: CrossServerMediaHit,
    right: CrossServerMediaHit,
    health: Map<String, ServerHealth>,
): Int =
    compareValuesBy(
        left,
        right,
        { searchHealthRank(health[it.serverId]?.status) },
        { health[it.serverId]?.latencyMs ?: Long.MAX_VALUE },
        { it.serverName.lowercase() },
        { it.serverId },
    )

private fun searchHealthRank(status: ServerHealthStatus?): Int =
    when (status) {
        ServerHealthStatus.Healthy -> 0
        ServerHealthStatus.Degraded -> 1
        ServerHealthStatus.Unknown, null -> 2
        ServerHealthStatus.Offline -> 3
        ServerHealthStatus.AuthRequired -> 4
    }

/**
 * Ranked detail candidate. The score is explainable and only orders comparable copies; it is
 * never exposed as a promise of bandwidth or playback success.
 */
data class RankedServerSource(
    val source: ServerSource,
    val score: Int,
)

/**
 * Whether the copy the user is already pointed at can serve this playback right now.
 *
 * Ranking exists to break ties between usable copies, not to overrule a deliberate choice.
 * [sourceScore] gives Healthy 2_500 and Degraded 1_400, and a single 5xx is enough to mark a
 * server Degraded - so one bad response from an otherwise working library outranked it by 1_100
 * points and moved playback to another server. Reserve that override for a source that genuinely
 * cannot be played.
 */
fun serverSourcePlayable(
    source: ServerSource,
    health: ServerHealth?,
): Boolean =
    source.reachable &&
        source.source != null &&
        source.itemId != null &&
        health?.status != ServerHealthStatus.Offline &&
        health?.status != ServerHealthStatus.AuthRequired

/** The best candidate, or null when no source is actually playable. */
fun recommendedServerSource(
    sources: List<ServerSource>,
    health: Map<String, ServerHealth> = emptyMap(),
    network: PlaybackNetworkClass = PlaybackNetworkClass.Unknown,
): ServerSource? =
    rankServerSources(sources, health, network)
        .firstOrNull { ranked ->
            ranked.source.reachable && ranked.source.source != null && ranked.source.itemId != null
        }?.source

/**
 * Recommends across availability, health, latency, picture/version facts and current network.
 *
 * Quality has enough weight to distinguish real versions, but an offline/auth-expired server
 * cannot win on resolution alone. Metered connections softly favour efficient bitrates instead
 * of making high-quality copies unusable; the user can still choose any source manually.
 */
fun rankServerSources(
    sources: List<ServerSource>,
    health: Map<String, ServerHealth> = emptyMap(),
    network: PlaybackNetworkClass = PlaybackNetworkClass.Unknown,
): List<RankedServerSource> =
    sources
        .map { source -> RankedServerSource(source, sourceScore(source, health[source.serverId], network)) }
        .sortedWith(
            compareByDescending<RankedServerSource> { it.score }
                .thenBy { health[it.source.serverId]?.latencyMs ?: Long.MAX_VALUE }
                .thenBy { it.source.serverName.lowercase() }
                .thenBy { it.source.serverId },
        )

/** Ordered, distinct and bounded so a broken title can never cycle through servers forever. */
fun smartFailoverServerIds(
    currentServerId: String,
    sources: List<ServerSource>,
    health: Map<String, ServerHealth> = emptyMap(),
    network: PlaybackNetworkClass = PlaybackNetworkClass.Unknown,
    maxFallbacks: Int = MAX_SMART_SOURCE_FALLBACKS,
): List<String> {
    if (maxFallbacks <= 0) return emptyList()
    return rankServerSources(sources, health, network)
        .asSequence()
        .map(RankedServerSource::source)
        .filter { candidate ->
            candidate.serverId != currentServerId &&
                candidate.reachable &&
                candidate.source != null &&
                candidate.itemId != null &&
                health[candidate.serverId]?.status != ServerHealthStatus.AuthRequired &&
                health[candidate.serverId]?.status != ServerHealthStatus.Offline
        }.map(ServerSource::serverId)
        .distinct()
        .take(maxFallbacks.coerceAtMost(MAX_SMART_SOURCE_FALLBACKS))
        .toList()
}

private fun sourceScore(
    candidate: ServerSource,
    health: ServerHealth?,
    network: PlaybackNetworkClass,
): Int {
    if (!candidate.reachable || candidate.source == null || candidate.itemId == null) {
        return UNAVAILABLE_SCORE
    }
    val healthScore =
        when (health?.status) {
            ServerHealthStatus.Healthy -> 2_500
            ServerHealthStatus.Degraded -> 1_400
            ServerHealthStatus.Unknown, null -> 1_000
            ServerHealthStatus.Offline -> -8_000
            ServerHealthStatus.AuthRequired -> -10_000
        }
    val latencyScore =
        health?.latencyMs?.let { latency ->
            (1_200 - latency.coerceAtLeast(0L).coerceAtMost(1_200L)).toInt()
        } ?: 300
    return healthScore + latencyScore + qualityScore(candidate.source, network)
}

private fun qualityScore(
    source: SourceInfo,
    network: PlaybackNetworkClass,
): Int {
    val height = source.videoHeight ?: source.videoWidth?.let { it * 9 / 16 }
    val resolution =
        when {
            height == null -> 0
            height >= 2160 -> 1_600
            height >= 1440 -> 1_250
            height >= 1080 -> 1_000
            height >= 720 -> 650
            else -> 250
        }
    val range =
        when {
            source.dolbyVision -> 260
            source.videoRange.orEmpty().contains("HDR", ignoreCase = true) -> 180
            else -> 0
        }
    val audio = (if (source.dolbyAtmos) 110 else 0) + (if (source.losslessAudio) 90 else 0)
    val bitrateMbps = source.bitrateBps?.coerceAtLeast(0)?.div(1_000_000)
    val networkAdjustment =
        when (network) {
            PlaybackNetworkClass.Unmetered, PlaybackNetworkClass.Unknown ->
                bitrateMbps?.coerceAtMost(120)?.times(3) ?: 0
            PlaybackNetworkClass.Metered ->
                when {
                    bitrateMbps == null -> 0
                    bitrateMbps <= 8 -> 180
                    bitrateMbps <= 15 -> 80
                    else -> -(bitrateMbps - 15).coerceAtMost(100) * 12
                }
            PlaybackNetworkClass.Offline -> -10_000
        }
    return resolution + range + audio + networkAdjustment
}

const val MAX_SMART_SOURCE_FALLBACKS = 3
private const val UNAVAILABLE_SCORE = -100_000
