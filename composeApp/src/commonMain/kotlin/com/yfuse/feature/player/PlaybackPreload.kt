package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Store
import kotlinx.coroutines.CompletableDeferred

/**
 * A queue being prepared while the detail page is visible.
 *
 * The detail page and the transient PlayerComponent live in the same process, so sharing the
 * actual Store avoids rebuilding the selected item's detail and stream URL after the user has
 * pressed 播放. Optional episode and backup-source work waits until the first frame. Resume
 * position is part of the key because it lives in PlayerState;
 * 从头播放 therefore falls back to a fresh queue instead of inheriting resume state.
 */
internal data class PlaybackPreloadKey(
    val serverId: String?,
    val itemId: String,
    val startPositionTicks: Long,
    val mediaSourceId: String?,
)

internal typealias PreparedPlayerStore = Store<PlayerIntent, PlayerState, Nothing>

/** Optional queue work starts only after the prepared Store is claimed and playback has output. */
internal class PreparedPlaybackGate {
    private val claimed = CompletableDeferred<PlaybackLaunchTiming?>()

    fun claim(timing: PlaybackLaunchTiming?) {
        claimed.complete(timing)
    }

    suspend fun awaitRelease() {
        claimed.await()?.awaitOutputOrDeadline()
    }
}

/**
 * Process-local cache for queues prepared by DetailComponent.
 *
 * The detail component owns an entry until PlayerComponent claims it for one launch. Claiming is
 * destructive: stream URLs carry a play-session id, and reusing the same Store for a later launch
 * would let the previous player's delayed `Stopped`/active-encoding cleanup terminate the new one.
 * A later launch therefore builds a fresh Store, with fresh session ids, unless the detail page has
 * prepared another entry in the meantime.
 */
internal object PreparedPlaybackRegistry {
    private data class Entry(
        val store: PreparedPlayerStore,
        val gate: PreparedPlaybackGate?,
    )

    private val stores = mutableMapOf<PlaybackPreloadKey, Entry>()

    fun register(
        key: PlaybackPreloadKey,
        store: PreparedPlayerStore,
        gate: PreparedPlaybackGate? = null,
    ): PreparedPlayerStore? = stores.put(key, Entry(store, gate))?.store

    /**
     * Transfers one prepared queue to the player that is about to launch it.
     *
     * Removing before returning makes the session-bearing URLs single-use even when two player
     * components are created for the same detail selection.
     */
    fun claim(
        key: PlaybackPreloadKey,
        timing: PlaybackLaunchTiming? = null,
    ): PreparedPlayerStore? =
        stores.remove(key)?.let { entry ->
            entry.gate?.claim(timing)
            entry.store
        }

    /** True only while the detail page still owns this exact Store. */
    fun owns(
        key: PlaybackPreloadKey,
        store: PreparedPlayerStore,
    ): Boolean = stores[key]?.store === store

    /** Removes an entry only when [store] is still the registered owner. */
    fun removeIfOwned(
        key: PlaybackPreloadKey,
        store: PreparedPlayerStore,
    ): Boolean {
        if (stores[key]?.store !== store) return false
        stores.remove(key)
        return true
    }
}

/**
 * Platform cache warmer. Metadata/MediaSources are prepared in common code; Android additionally
 * warms the first bytes of the direct stream into the sparse cache shared by every player engine.
 */
fun interface PlaybackSourcePreload {
    fun cancel()

    /** Stop unfinished work but allow an already prepared source to be claimed once. */
    fun handoff() = cancel()
}

internal fun noOpPlaybackSourcePreload(): PlaybackSourcePreload = PlaybackSourcePreload {}

interface PlaybackSourcePreloader {
    fun preload(item: PlayerMediaItem): PlaybackSourcePreload

    fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
    ): PlaybackSourcePreload = preload(item)

    fun preload(
        item: PlayerMediaItem,
        startPositionMs: Long,
        tracks: com.yfuse.core.data.PlaybackTrackRequest.Tracks?,
    ): PlaybackSourcePreload = preload(item, startPositionMs)
}
