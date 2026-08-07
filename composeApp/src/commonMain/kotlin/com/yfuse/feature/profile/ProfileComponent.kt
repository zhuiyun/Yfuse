package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.util.clearImageCache
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.servers.ServersStoreFactory
import org.koin.core.context.GlobalContext

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    repo: EmbyRepository,
    val themePreferences: ThemePreferences,
    /** Re-opens the player on the current 一起看 room; see `RootComponent.enterWatchRoom`. */
    val onEnterWatchRoom: () -> Unit,
) : ComponentContext by componentContext {

    val store = ProfileStoreFactory(storeFactory, registry).create()

    /**
     * 添加服务器 is a modal on this screen rather than a pushed route, so its state
     * lives alongside the profile's instead of behind a navigation stack.
     */
    val serversStore = ServersStoreFactory(
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        discovery = GlobalContext.get().get<LanDiscovery>(),
    ).create()

    val offlineMedia: OfflineMediaManager = GlobalContext.get().get()
    val syncManager: ServerSyncManager = GlobalContext.get().get()
    val playbackRecovery: PlaybackRecoveryStore = GlobalContext.get().get()
    val playbackPreferences: PlaybackPreferences = GlobalContext.get().get()
    val userAgentPreferences: UserAgentPreferences = GlobalContext.get().get()
    val danmakuPreferences: DanmakuPreferences = GlobalContext.get().get()
    val skipSegmentPreferences: SkipSegmentPreferences = GlobalContext.get().get()
    private val libraryCache: LibraryCache = GlobalContext.get().get()
    val watchTogetherPreferences: WatchTogetherPreferences = GlobalContext.get().get()
    val watchTogether: WatchTogetherClient = GlobalContext.get().get()

    /** Clear the shared image cache; offline video files and library metadata are untouched. */
    suspend fun onClearCache() = clearImageCache()

    /** Long-pressing a non-current server row removes it. */
    fun onRemoveServer(id: String) {
        registry.remove(id)
        // The cached library would otherwise outlive the server it was read from, taking up
        // room for a shelf nobody can open any more.
        libraryCache.clear(id)
    }

    fun exportServers(): String = registry.exportBackup()

    fun importServers(payload: String): Result<Int> = registry.importBackup(payload)

    fun recoveryItem(snapshot: PlaybackRecoverySnapshot): PlayerMediaItem? {
        val server = snapshot.serverId?.let(registry::serverById) ?: registry.defaultServer
        server ?: return null
        val urls = EmbyStream.streamUrls(server.baseUrl, snapshot.itemId, server.accessToken)
        return PlayerMediaItem(
            id = snapshot.itemId,
            url = urls.direct,
            transcodeUrl = urls.transcode,
            fallbackTranscodeUrl = urls.progressiveTranscode,
            playSessionId = urls.playSessionId,
            title = snapshot.title,
            serverId = server.id,
        )
    }

    init {
        lifecycle.doOnDestroy {
            store.dispose()
            serversStore.dispose()
        }
    }
}
