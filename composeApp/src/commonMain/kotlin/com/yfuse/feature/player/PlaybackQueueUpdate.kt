package com.yfuse.feature.player

/** Enrichment may change the catalog, but cannot silently change the currently opened resource. */
internal fun canUpdatePlaybackQueue(
    previous: List<PlayerMediaItem>,
    previousIndex: Int,
    updated: List<PlayerMediaItem>,
    updatedIndex: Int,
): Boolean {
    val playing = previous.getOrNull(previousIndex) ?: return false
    val replacement = updated.getOrNull(updatedIndex) ?: return false
    if (updated.map { it.serverId to it.id }.distinct().size != updated.size) return false
    return playing.id == replacement.id &&
        playing.serverId == replacement.serverId &&
        playing.url == replacement.url &&
        playing.activeVersion?.id == replacement.activeVersion?.id &&
        playing.playSessionId == replacement.playSessionId
}

internal fun remapPlaybackQueueIndices(
    indices: Set<Int>,
    previous: List<PlayerMediaItem>,
    updated: List<PlayerMediaItem>,
): Set<Int> {
    val remembered = indices.mapNotNull { previous.getOrNull(it)?.let { item -> item.serverId to item.id } }.toSet()
    return updated.mapIndexedNotNull { index, item -> index.takeIf { item.serverId to item.id in remembered } }.toSet()
}
