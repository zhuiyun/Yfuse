package com.yfuse.feature.player

import com.arkivanov.mvikotlin.core.store.Store

/**
 * A queue being prepared while the detail page is visible.
 *
 * The detail page and the transient PlayerComponent live in the same process, so handing the
 * actual Store across avoids rebuilding item detail, episode MediaSources and every stream URL
 * after the user has already pressed 播放. The key intentionally excludes the resume position:
 * 从头播放 and resume use the same queue and only differ in where the native player seeks.
 */
internal data class PlaybackPreloadKey(
    val serverId: String?,
    val itemId: String,
    val mediaSourceId: String?,
)

internal typealias PreparedPlayerStore = Store<PlayerIntent, PlayerState, Nothing>

/**
 * Process-local handoff for a queue prepared by DetailComponent.
 *
 * A map rather than a single slot keeps a covered detail page's preparation intact while a
 * related detail page is pushed above it. All access is from component/main coroutines.
 */
internal object PreparedPlaybackRegistry {
    private val stores = mutableMapOf<PlaybackPreloadKey, PreparedPlayerStore>()

    fun register(
        key: PlaybackPreloadKey,
        store: PreparedPlayerStore,
    ): PreparedPlayerStore? = stores.put(key, store)

    /** Transfers ownership to PlayerComponent. */
    fun take(key: PlaybackPreloadKey): PreparedPlayerStore? = stores.remove(key)

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
