package com.yfuse.feature.detail

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.sync.playback.PlaybackStateRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull

/** Reopening reads current records; a late metadata response is projected again even without a write. */
internal fun localDetailProgress(
    states: Flow<DetailState>,
    visible: Flow<Boolean>,
    changes: Flow<Long>,
    stateFor: (String, String) -> PlaybackStateRecord?,
): Flow<Map<Pair<String, String>, PlaybackStateRecord?>> =
    combine(states, visible, changes) { state, isVisible, _ ->
        if (isVisible) state.localProgressSnapshot(stateFor) else null
    }.filterNotNull()

/** Include missing records too: a cleared account or reset must clear the page's old progress. */
internal fun DetailState.localProgressSnapshot(
    stateFor: (String, String) -> PlaybackStateRecord?,
): Map<Pair<String, String>, PlaybackStateRecord?> =
    buildMap {
        fun include(
            serverId: String?,
            itemId: String?,
        ) {
            if (serverId != null && itemId != null && (serverId to itemId) !in this) {
                put(serverId to itemId, stateFor(serverId, itemId))
            }
        }
        include(server?.id, detail?.id)
        include(playServer?.id, playSourceDetail?.id)
        include(playServer?.id, playTarget?.id)
        episodes.forEach { include(playServer?.id, it.id) }
    }

/** Project progress only. Metadata, selections and an open progress editor remain untouched. */
internal fun DetailState.withLocalProgress(progress: Map<Pair<String, String>, PlaybackStateRecord?>): DetailState {
    fun MediaDetail.project(serverId: String?): MediaDetail {
        if (serverId == null) return this
        val key = serverId to id
        if (key !in progress) return this
        val record = progress[key]
        return copy(played = record?.played == true, resumePositionTicks = record.resumeTicks())
    }
    val playbackServerId = playServer?.id
    val targetKey = playbackServerId?.let { id -> playTarget?.let { id to it.id } }
    return copy(
        detail = detail?.project(server?.id),
        playSourceDetail = playSourceDetail?.project(playServer?.id),
        playTarget = playTarget?.project(playServer?.id),
        playPositionTicks =
            if (targetKey != null && targetKey in progress) {
                progress[targetKey].resumeTicks() ?: 0L
            } else {
                playPositionTicks
            },
        episodes =
            episodes.map { episode ->
                if (playbackServerId == null) return@map episode
                val key = playbackServerId to episode.id
                if (key !in progress) return@map episode
                val record = progress[key]
                val ticks = record.resumeTicks()
                val durationMs =
                    record?.durationMs?.takeIf { it > 0L }
                        ?: episode.runtimeTicks?.div(10_000L)?.takeIf { it > 0L }
                        ?: episode.runtimeMinutes
                            ?.toLong()
                            ?.times(60_000L)
                            ?.takeIf { it > 0L }
                episode.copy(
                    played = record?.played == true,
                    resumePositionTicks = ticks,
                    playedPercentage =
                        if (record != null && ticks != null && durationMs != null) {
                            (record.positionMs.toDouble() * 100.0 / durationMs).coerceIn(0.0, 100.0)
                        } else {
                            null
                        },
                )
            },
    )
}

private fun PlaybackStateRecord?.resumeTicks(): Long? =
    this
        ?.takeUnless { it.played }
        ?.positionMs
        ?.takeIf { it > 0L }
        ?.let { if (it > Long.MAX_VALUE / 10_000L) Long.MAX_VALUE else it * 10_000L }

/** A component and a library copy share canonical progress without inventing a server item. */
internal fun DetailState.localProgressForItem(
    local: com.yfuse.core.sync.playback.PlaybackSyncStore,
    serverId: String,
    itemId: String,
): PlaybackStateRecord? {
    // A concrete library item uses the same authoritative record as history and repository
    // metadata. Provider aliases are only a fallback for items with no direct record.
    val direct = local.stateForServerItem(serverId, itemId)
    val episode = episodes.firstOrNull { it.id == itemId }
    val media = listOfNotNull(playTarget, playSourceDetail, detail).firstOrNull { it.id == itemId }
    val series = playSourceDetail?.takeIf { it.type == "Series" } ?: detail?.takeIf { it.type == "Series" }
    val keys =
        com.yfuse.core.sync.watchMatchKeys(
            ownProviderIds = episode?.providerIds ?: media?.providerIds.orEmpty(),
            seriesProviderIds = series?.providerIds.orEmpty(),
            seasonNumber = episode?.seasonNumber ?: media?.seasonNumber,
            episodeNumber = episode?.indexNumber ?: media?.episodeNumber,
            fallbackId = itemId,
        )
    local.bindKnownSource(keys.first(), keys, serverId, itemId)
    return com.yfuse.core.sync.playback.libraryResumeState(
        direct = direct,
        portable =
            keys.filterNot { it.startsWith("emby:") }.mapNotNull { key ->
                local.find(key, serverId = serverId)?.document?.state
            },
    )
}
