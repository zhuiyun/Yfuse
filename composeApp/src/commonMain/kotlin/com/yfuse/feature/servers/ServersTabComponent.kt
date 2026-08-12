package com.yfuse.feature.servers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.launch

/**
 * The 服务器 tab — the saved servers as a grid, and everything one can do to one of them.
 *
 * They used to be a collapsed list inside 我的, three taps from the page whose content they
 * decide, in a row with no room to say more than a name. Whether a machine answers, how
 * fast, and how long it has been since anyone opened it are all worth seeing at a glance;
 * that is a card, and a screenful of cards is a tab.
 *
 * There is no navigation stack here on purpose: adding, editing and removing are modals
 * over the grid, so this tab is only ever showing the grid.
 */
class ServersTabComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    private val registry: ServerRegistry,
    dependencies: AppDependencies,
    /** Where a newly chosen server is meant to take the user. */
    val onOpenLibrary: () -> Unit,
) : ComponentContext by componentContext {

    /** Adding, editing, choosing the default and removing all live in this one store. */
    val store = ServersStoreFactory(
        storeFactory = storeFactory,
        repo = repo,
        registry = registry,
        discovery = dependencies.lanDiscovery,
    ).create()

    val health: ServerHealthMonitor = dependencies.serverHealthMonitor
    val activity: ServerActivityStore = dependencies.serverActivity
    private val libraryCache: LibraryCache = dependencies.libraryCache
    private val scope = componentScope(lifecycle)

    /** Re-probes every saved server; the grid's latency is only as fresh as its last probe. */
    fun refreshHealth() {
        scope.launch { health.refreshAll(registry.data.value.servers) }
    }

    /** Re-probes one card from its context menu without making every server flash. */
    fun refreshHealth(server: com.yfuse.core.model.SavedServer) {
        scope.launch { health.refresh(server) }
    }

    /**
     * Removing a server takes its cached library with it — those shelves would otherwise
     * outlive the machine they were read from — and its watch history, which describes a
     * server that no longer exists.
     */
    fun removeServer(id: String) {
        store.accept(ServersIntent.Remove(id))
        libraryCache.clear(id)
        activity.retainOnly(registry.data.value.servers.mapTo(mutableSetOf()) { it.id })
    }

    init {
        lifecycle.doOnDestroy { store.dispose() }
    }
}
