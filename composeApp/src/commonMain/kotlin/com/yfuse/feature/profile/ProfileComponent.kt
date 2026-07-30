package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.PlaybackRecoverySnapshot
import com.yfuse.core.data.PlaybackRecoveryStore
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.util.clearImageCache
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.feature.servers.ServersStoreFactory
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.core.network.EmbyStream
import org.koin.core.context.GlobalContext

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    repo: EmbyRepository,
    val themePreferences: ThemePreferences,
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
    val userAgentPreferences: UserAgentPreferences = GlobalContext.get().get()
    val danmakuPreferences: DanmakuPreferences = GlobalContext.get().get()

    /** 下载与缓存 · 清除全部缓存. */
    fun onClearCache() = clearImageCache()

    /** Long-pressing a non-current server row removes it. */
    fun onRemoveServer(id: String) = registry.remove(id)

    fun exportServers(): String = registry.exportBackup()

    fun importServers(payload: String): Result<Int> = registry.importBackup(payload)

    fun recoveryItem(snapshot: PlaybackRecoverySnapshot): PlayerMediaItem? {
        val server = snapshot.serverId?.let(registry::serverById) ?: registry.defaultServer
        server ?: return null
        return PlayerMediaItem(
            id = snapshot.itemId,
            url = EmbyStream.directPlay(server.baseUrl, snapshot.itemId, server.accessToken),
            transcodeUrl = EmbyStream.transcode(server.baseUrl, snapshot.itemId, server.accessToken),
            fallbackTranscodeUrl = EmbyStream.progressiveTranscode(
                server.baseUrl,
                snapshot.itemId,
                server.accessToken,
            ),
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
