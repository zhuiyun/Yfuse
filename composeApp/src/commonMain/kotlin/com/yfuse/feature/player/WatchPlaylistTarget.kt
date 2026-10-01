package com.yfuse.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The room playlist's way to the player on screen. The playlist is listed in the room dialog, up in
 * the chrome, while the queue it plays from and the gate every episode change goes through belong
 * to the player, which binds one of these for as long as it is composed (PlayerWatchSyncEffects).
 *
 * With it the dialog offers 播放 only for entries this device's queue holds, and starts one as it
 * is tapped. A request left for the next playback tick waited for the player's state to change,
 * which a paused player may not do for a while, and one the queue could not resolve went nowhere.
 */
internal class WatchPlaylistTarget(
    /** Where this device's queue holds the media a room names by key, or null when it does not. */
    val indexOf: (mediaKey: String) -> Int?,
    /** Starts the queue entry at an index, for the whole room. */
    val play: (index: Int) -> Unit,
) {
    companion object {
        /** The player on screen's, while there is one. */
        var current: WatchPlaylistTarget? by mutableStateOf(null)
            private set

        fun bind(target: WatchPlaylistTarget) {
            current = target
        }

        /** Takes [target] away only: a player that has already bound its own keeps it. */
        fun unbind(target: WatchPlaylistTarget) {
            if (current === target) current = null
        }
    }
}
