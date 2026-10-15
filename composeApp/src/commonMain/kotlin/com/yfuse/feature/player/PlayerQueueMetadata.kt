package com.yfuse.feature.player

import kotlinx.serialization.Serializable

@Serializable
data class TrickplayStoryboard(
    val urlPattern: String,
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val intervalMs: Long,
    val thumbnailCount: Int,
    val urlIndexMultiplier: Long = 1L,
    val frames: List<TrickplayStoryboardFrame> = emptyList(),
) {
    fun frameAt(positionMs: Long): TrickplayFrame {
        if (frames.isNotEmpty()) {
            val target = positionMs.coerceAtLeast(0L)
            var low = 0
            var high = frames.lastIndex
            var match = 0
            while (low <= high) {
                val middle = (low + high).ushr(1)
                if (frames[middle].positionMs <= target) {
                    match = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return TrickplayFrame(frames[match].url, column = 0, row = 0)
        }
        val frame =
            (positionMs.coerceAtLeast(0L) / intervalMs.coerceAtLeast(1L))
                .coerceAtMost((thumbnailCount - 1).coerceAtLeast(0).toLong())
                .toInt()
        val perSheet = (tileColumns * tileRows).coerceAtLeast(1)
        val local = frame % perSheet
        val urlIndex = (frame / perSheet).toLong() * urlIndexMultiplier.coerceAtLeast(1L)
        return TrickplayFrame(
            url = urlPattern.replace("{index}", urlIndex.toString()),
            column = local % tileColumns.coerceAtLeast(1),
            row = local / tileColumns.coerceAtLeast(1),
        )
    }
}

@Serializable
data class TrickplayStoryboardFrame(
    val positionMs: Long,
    val url: String,
)

/** Merges the legacy offline sidecar with provider-owned online tracks without duplicate URLs. */
internal fun PlayerMediaItem.playbackExternalSubtitles(): List<PlayerExternalSubtitle> =
    buildList {
        externalSubtitleUri
            ?.takeIf(String::isNotBlank)
            ?.let { uri ->
                add(
                    PlayerExternalSubtitle(
                        uri = uri,
                        language = externalSubtitleLanguage,
                        default = true,
                    ),
                )
            }
        addAll(externalSubtitles)
    }.distinctBy(PlayerExternalSubtitle::uri)

data class TrickplayFrame(
    val url: String,
    val column: Int,
    val row: Int,
)

internal data class TrickplayCacheKey(
    val serverId: String,
    val itemId: String,
    val mediaSourceId: String,
)

/** Keeps viewed-episode storyboards (including misses) without prefetching an entire season. */
internal fun Map<TrickplayCacheKey, TrickplayStoryboard?>.withTrickplayResult(
    key: TrickplayCacheKey,
    storyboard: TrickplayStoryboard?,
    maxEntries: Int = MAX_TRICKPLAY_CACHE_ENTRIES,
): Map<TrickplayCacheKey, TrickplayStoryboard?> {
    require(maxEntries > 0)
    return (filterKeys { it != key } + (key to storyboard))
        .entries
        .toList()
        .takeLast(maxEntries)
        .associate { it.toPair() }
}

internal const val MAX_TRICKPLAY_CACHE_ENTRIES = 8

internal fun String.withPlaySessionId(sessionId: String): String {
    if (isBlank()) return this
    val plexSession = Regex("([?&])session=[^&]*")
    val withProviderSession =
        if (contains("/video/:/transcode/universal/", ignoreCase = true)) {
            if (plexSession.containsMatchIn(this)) {
                replace(plexSession, "$1session=$sessionId")
            } else {
                "$this${if ('?' in this) '&' else '?'}session=$sessionId"
            }
        } else {
            this
        }
    val parameter = Regex("([?&])PlaySessionId=[^&]*")
    return if (parameter.containsMatchIn(withProviderSession)) {
        withProviderSession.replace(parameter, "$1PlaySessionId=$sessionId")
    } else {
        "$withProviderSession${if ('?' in withProviderSession) '&' else '?'}PlaySessionId=$sessionId"
    }
}

/**
 * Whether two queue snapshots describe the same media sources in the same order.
 *
 * Episode polling also refreshes display-only metadata such as title, artwork, skip segments and
 * watched progress. Those fields are deliberately excluded: changing them can recompose the
 * controls without tearing down a healthy playback engine. The engine only needs rebuilding when
 * an entry is added, removed, reordered, or one of the URLs it may load changes.
 */
internal fun List<PlayerMediaItem>.hasSamePlaybackSourcesAs(other: List<PlayerMediaItem>): Boolean {
    if (size != other.size) return false
    return indices.all { index ->
        val current = this[index]
        val refreshed = other[index]
        current.id == refreshed.id &&
            current.url.playbackSourceKey() == refreshed.url.playbackSourceKey() &&
            current.transcodeUrl.playbackSourceKey() ==
            refreshed.transcodeUrl.playbackSourceKey() &&
            current.fallbackTranscodeUrl.playbackSourceKey() ==
            refreshed.fallbackTranscodeUrl.playbackSourceKey() &&
            (current.drmConfiguration ?: current.activeVersion?.drmConfiguration) ==
            (refreshed.drmConfiguration ?: refreshed.activeVersion?.drmConfiguration)
    }
}

/**
 * The entries [other] adds after this queue, or null when it is not a pure extension of it.
 *
 * Appending is the one shape of change an engine can absorb while playing. Anything else — a
 * reorder, a removal, a file swapped underneath an entry — needs the engine rebuilt, because
 * its playlist is addressed by position.
 */
internal fun List<PlayerMediaItem>.appendedBy(other: List<PlayerMediaItem>): List<PlayerMediaItem>? {
    if (other.size <= size) return null
    if (!hasSamePlaybackSourcesAs(other.subList(0, size))) return null
    return other.subList(size, other.size).toList()
}

/**
 * The address with the id that identifies *this* playback of it removed.
 *
 * A play session is minted per queue build, so two entries can name the same file through
 * different session ids. Comparing the raw URLs would then read "the sources changed" and
 * tear down a healthy engine — which is the question this key exists to answer correctly.
 * For comparison only; the result is not a fetchable URL.
 */
private fun String.playbackSourceKey(): String = replace(Regex("[?&]PlaySessionId=[^&]*"), "")

/** Enrichment cannot replace a negotiated URL/session or a recovery choice already published. */
internal fun PlayerMediaItem.withQueueMetadata(metadata: PlayerMediaItem): PlayerMediaItem =
    copy(
        title = metadata.title,
        providerIds = metadata.providerIds.ifEmpty { providerIds },
        mediaType = metadata.mediaType.ifBlank { mediaType },
        year = metadata.year ?: year,
        playbackSegments = metadata.playbackSegments,
        chapters = metadata.chapters.ifEmpty { chapters },
        seasonNumber = metadata.seasonNumber ?: seasonNumber,
        episodeNumber = metadata.episodeNumber ?: episodeNumber,
        seriesId = metadata.seriesId ?: seriesId,
        seriesName = metadata.seriesName ?: seriesName,
        seriesKey = metadata.seriesKey,
        watchKey = metadata.watchKey,
        matchKeys = metadata.matchKeys,
        stillUrl = metadata.stillUrl,
        posterUrl = metadata.posterUrl,
        progress = metadata.progress,
        caption = metadata.caption,
        durationMsHint = metadata.durationMsHint.takeIf { it > 0L } ?: durationMsHint,
    )
