package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Store

/**
 * A queue being prepared while the detail page is visible.
 *
 * The detail page and the transient PlayerComponent live in the same process, so sharing the
 * actual Store avoids rebuilding item detail, episode MediaSources and every stream URL after the
 * user has already pressed 播放. Resume position is part of the key because it lives in PlayerState;
 * 从头播放 therefore falls back to a fresh queue instead of inheriting resume state.
 */
internal data class PlaybackPreloadKey(
    val serverId: String?,
    val itemId: String,
    val startPositionTicks: Long,
    val mediaSourceId: String?,
)

internal typealias PreparedPlayerStore = Store<PlayerIntent, PlayerState, Nothing>

/**
 * Process-local cache for queues prepared by DetailComponent.
 *
 * The detail component remains the owner. PlayerComponent only borrows the Store long enough to
 * launch PlayerActivity, which means returning from playback and pressing 播放 again is still an
 * instant cache hit instead of rebuilding the queue.
 */
internal object PreparedPlaybackRegistry {
    private val stores = mutableMapOf<PlaybackPreloadKey, PreparedPlayerStore>()

    fun register(
        key: PlaybackPreloadKey,
        store: PreparedPlayerStore,
    ): PreparedPlayerStore? = stores.put(key, store)

    fun get(key: PlaybackPreloadKey): PreparedPlayerStore? = stores[key]

    /** True only while the detail page still owns this exact Store. */
    fun owns(key: PlaybackPreloadKey, store: PreparedPlayerStore): Boolean = stores[key] === store

    /** Removes an entry only when [store] is still the registered owner. */
    fun removeIfOwned(key: PlaybackPreloadKey, store: PreparedPlayerStore): Boolean {
        if (stores[key] !== store) return false
        stores.remove(key)
        return true
    }
}

/**
 * Platform cache warmer. Metadata/MediaSources are prepared in common code; Android additionally
 * warms the first bytes of the direct stream into the exact Media3 cache ExoPlayer will consume.
 */
interface PlaybackSourcePreloader {
    fun preload(url: String)
}
