package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.util.clearImageCache
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.feature.servers.ServersStoreFactory
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

    /** 下载与缓存 · 清除全部缓存. */
    fun onClearCache() = clearImageCache()

    /** Long-pressing a non-current server row removes it. */
    fun onRemoveServer(id: String) = registry.remove(id)

    fun exportServers(): String = registry.exportBackup()

    fun importServers(payload: String): Result<Int> = registry.importBackup(payload)

    init {
        lifecycle.doOnDestroy {
            store.dispose()
            serversStore.dispose()
        }
    }
}
