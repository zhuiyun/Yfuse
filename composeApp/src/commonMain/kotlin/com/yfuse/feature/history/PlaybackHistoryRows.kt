package com.yfuse.feature.history

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.LocalViewingSession
import com.yfuse.core.personal.PersonalAccessPolicy
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.sync.parseEpisodeWatchKey

internal data class PlaybackHistoryMetadataKey(
    val scopeToken: String,
    val serverId: String,
    val itemId: String,
)

internal data class PlaybackHistoryMetadata(
    val item: MediaDetail,
    val series: MediaDetail?,
)

internal data class PlaybackHistoryRow(
    val entry: PersonalEntry,
    val session: LocalViewingSession?,
    val server: SavedServer?,
    val metadata: PlaybackHistoryMetadata? = null,
    val metadataLoaded: Boolean = false,
) {
    val title: String get() =
        session?.seriesTitle?.takeIf(String::isNotBlank)
            ?: metadata?.series?.title ?: metadata?.item?.seriesName?.takeIf(String::isNotBlank) ?: entry.media.title
    val overview: String? get() =
        metadata?.series?.overview?.takeIf(String::isNotBlank)
            ?: metadata?.item?.overview?.takeIf(String::isNotBlank)
    val durationMs: Long get() =
        entry.durationMs.takeIf { it > 0 }
            ?: metadata
                ?.item
                ?.runtimeTicks
                ?.let { it / 10_000 }
                ?.coerceAtLeast(0L) ?: 0L
    val episodeLabel: String?
        get() {
            val coordinate = parseEpisodeWatchKey(entry.media.mediaKey)
            val episode =
                session?.episodeNumber ?: metadata?.item?.episodeNumber ?: coordinate?.episodeNumber ?: return null
            val season = session?.seasonNumber ?: metadata?.item?.seasonNumber ?: coordinate?.seasonNumber
            return (season?.let { "第 $it 季 · " }.orEmpty()) + "第 $episode 集"
        }
    val progress: Float
        get() =
            when {
                entry.completed -> 1f
                durationMs > 0 -> (entry.positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
                else -> 0f
            }
}

internal fun playbackHistoryServer(
    media: PersonalMediaRef,
    servers: List<SavedServer>,
): SavedServer? =
    servers.firstOrNull { it.id == media.serverId }
        ?: servers.firstOrNull { media.serverId in it.previousIds }

/** One card per series, keeping the latest episode's source, detail target and progress together. */
internal fun playbackHistoryRows(
    entries: List<PersonalEntry>,
    sessions: List<LocalViewingSession>,
    servers: List<SavedServer>,
    policy: PersonalAccessPolicy,
    metadataByItem: Map<Pair<String, String>, PlaybackHistoryMetadata?> = emptyMap(),
): List<PlaybackHistoryRow> {
    val metadata =
        sessions
            .sortedByDescending { it.lastWatchedAtEpochMs }
            .distinctBy { it.media.serverId to it.media.serverItemId }
            .associateBy { it.media.serverId to it.media.serverItemId }
    val rows =
        entries
            .filter {
                it.collection == PersonalCollection.History &&
                    !it.deleted &&
                    it.profileId == policy.profileId &&
                    it.media.serverId?.let(policy::allowsServer) != false
            }.sortedWith(compareByDescending<PersonalEntry> { it.watchedAtEpochMs }.thenByDescending { it.stamp })
            .map { entry ->
                val session =
                    entry.media.serverItemId?.takeIf(String::isNotBlank)?.let {
                        metadata[entry.media.serverId to it]
                    }
                val server = playbackHistoryServer(entry.media, servers)
                val key = server?.let { origin -> entry.media.serverItemId?.let { origin.id to it } }
                PlaybackHistoryRow(
                    entry,
                    session,
                    server,
                    key?.let(metadataByItem::get),
                    key != null && metadataByItem.containsKey(key),
                )
            }

    // Several providers can identify a show. Joining aliases also merges groups connected by
    // an older record, while the newest row retains the exact server and episode to open.
    val parents = IntArray(rows.size) { it }

    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }
    val owners = mutableMapOf<String, Int>()
    rows.forEachIndexed { index, row ->
        row.workKeys().forEach { key ->
            val other = owners.getOrPut(key) { index }
            val a = root(index)
            val b = root(other)
            parents[maxOf(a, b)] = minOf(a, b)
        }
    }
    return rows.filterIndexed { index, _ -> root(index) == index }
}

private fun PlaybackHistoryRow.workKeys(): Set<String> {
    val media = entry.media
    val episode =
        media.mediaType.equals("episode", true) || session?.isEpisode == true || metadata?.item?.seriesId != null
    val series = episode || media.mediaType.lowercase() in setOf("series", "tv")
    if (!series) return setOf(media.identity)
    return buildSet {
        add("series-item:${media.identity}")
        val providerKey = if (episode) parseEpisodeWatchKey(media.mediaKey)?.seriesKey else media.mediaKey
        providerKey?.externalSeriesKey()?.let { add("series-provider:$it") }
        session
            ?.seriesKey
            ?.removePrefix("provider:")
            ?.externalSeriesKey()
            ?.let { add("series-provider:$it") }
        session?.seriesKey?.takeIf { it.startsWith("server:") }?.let { add(it) }
        metadata?.series?.providerIds?.forEach { (provider, id) ->
            "$provider:$id".externalSeriesKey()?.let { add("series-provider:$it") }
        }
        val seriesItemId = if (episode) metadata?.item?.seriesId ?: session?.posterItemId else media.serverItemId
        if (!seriesItemId.isNullOrBlank() && !media.serverId.isNullOrBlank()) {
            add("series-server:${server?.id ?: media.serverId}:$seriesItemId")
        }
    }
}

private fun String.externalSeriesKey(): String? =
    trim().lowercase().takeIf {
        it.substringBefore(':') in setOf("tmdb", "tvdb", "imdb") && it.substringAfter(':', "").isNotBlank()
    }
