package com.yfuse.feature.player

/**
 * One-shot bridge from the room dialog to the active gated player.
 *
 * PlayerControls intentionally stays unaware of room-playlist transport. The dialog records the
 * requested cross-server media key here, and the already-running WatchGatedPlayback state tick
 * consumes it before publishing the next room anchor. Only one request matters: a second tap
 * replaces a not-yet-consumed first tap instead of queueing stale playback changes.
 */
internal object WatchPlaylistPlaybackRequest {
    private var pendingMediaKey: String? = null

    fun request(mediaKey: String) {
        pendingMediaKey = mediaKey.trim().takeIf(String::isNotEmpty)
    }

    fun consume(): String? = pendingMediaKey.also { pendingMediaKey = null }

    fun clear() {
        pendingMediaKey = null
    }
}
