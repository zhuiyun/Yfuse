package com.yfuse.core.sync.playback

/**
 * Returns null only when Yfuse has no state for this media. Zero is meaningful: a completed item,
 * an explicit manual-unwatched reset, or a newer restart must be able to override a stale positive
 * resume value returned by a media server.
 */
internal fun PlaybackSyncStore.authoritativeStartPositionMs(
    mediaKey: String,
    aliases: List<String> = emptyList(),
    serverId: String? = null,
    completedRatio: Double = 0.95,
): Long? {
    val state = find(mediaKey, aliases, serverId)?.document?.state ?: return null
    if (state.played) return 0L
    if (state.durationMs > 0L && state.positionMs >= (state.durationMs * completedRatio).toLong()) {
        return 0L
    }
    return state.positionMs.coerceAtLeast(0L)
}
