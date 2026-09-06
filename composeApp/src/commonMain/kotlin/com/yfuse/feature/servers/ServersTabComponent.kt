package com.yfuse.feature.servers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerManagementSnapshot
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ServerStatsStore
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ServerManagementUiState {
    data object Idle : ServerManagementUiState

    data class Loading(
        val serverId: String,
    ) : ServerManagementUiState

    data class Ready(
        val serverId: String,
        val snapshot: ServerManagementSnapshot,
        val busyId: String? = null,
        val message: String? = null,
        val error: String? = null,
    ) : ServerManagementUiState

    data class Error(
        val serverId: String,
        val message: String,
    ) : ServerManagementUiState
}

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
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    dependencies: AppDependencies,
    private val themePreferences: ThemePreferences,
    /** Where a newly chosen server is meant to take the user. */
    val onOpenLibrary: () -> Unit,
) : ComponentContext by componentContext {
    /** Adding, editing, choosing the default and removing all live in this one store. */
    val store =
        ServersStoreFactory(
            storeFactory = storeFactory,
            repo = repo,
            registry = registry,
            discovery = dependencies.lanDiscovery,
            onAuthenticated = dependencies.playbackReportingCoordinator::resumeAfterAuthentication,
        ).create()

    val health: ServerHealthMonitor = dependencies.serverHealthMonitor
    val activity: ServerActivityStore = dependencies.serverActivity
    val stats: ServerStatsStore = dependencies.serverStats

    /** Grid or list; see [ServerLayout]. */
    val layout: StateFlow<ServerLayout> = themePreferences.serverLayout

    fun setLayout(value: ServerLayout) = themePreferences.setServerLayout(value)

    private val libraryCache: LibraryCache = dependencies.libraryCache
    private val scope = componentScope(lifecycle)

    private val _refreshing = MutableStateFlow(false)

    private val _listFilter = MutableStateFlow(ServerListFilter())
    private val _management = MutableStateFlow<ServerManagementUiState>(ServerManagementUiState.Idle)

    /** Sorting, latency and account filtering stay with this tab while the app is alive. */
    val listFilter: StateFlow<ServerListFilter> = _listFilter.asStateFlow()

    /** Drives the header's refresh control so a whole-grid re-probe is visibly running. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val management: StateFlow<ServerManagementUiState> = _management.asStateFlow()

    fun loadManagement(server: SavedServer) {
        _management.value = ServerManagementUiState.Loading(server.id)
        scope.launch {
            repo.serverManagement(server).fold(
                onSuccess = { snapshot ->
                    _management.value = ServerManagementUiState.Ready(server.id, snapshot)
                },
                onFailure = {
                    _management.value =
                        ServerManagementUiState.Error(server.id, it.message ?: "读取服务器管理信息失败")
                },
            )
        }
    }

    fun closeManagement() {
        _management.value = ServerManagementUiState.Idle
    }

    fun refreshManagedLibrary(
        server: SavedServer,
        libraryId: String,
    ) {
        val ready = _management.value as? ServerManagementUiState.Ready ?: return
        _management.value = ready.copy(busyId = "library:$libraryId", message = null, error = null)
        scope.launch {
            repo.refreshLibrary(server, libraryId).fold(
                onSuccess = {
                    val libraryName =
                        ready.snapshot.libraries
                            .firstOrNull { library -> library.id == libraryId }
                            ?.name
                            ?: libraryId
                    _management.value =
                        ready.copy(message = "已提交媒体库扫描任务：$libraryName")
                },
                onFailure = {
                    _management.value = ready.copy(error = it.message ?: "媒体库扫描启动失败")
                },
            )
        }
    }

    fun runManagedTask(
        server: SavedServer,
        taskId: String,
    ) {
        val ready = _management.value as? ServerManagementUiState.Ready ?: return
        _management.value = ready.copy(busyId = "task:$taskId", message = null, error = null)
        scope.launch {
            repo.runServerTask(server, taskId).fold(
                onSuccess = {
                    _management.value = ready.copy(message = "服务器任务已启动")
                },
                onFailure = {
                    _management.value = ready.copy(error = it.message ?: "服务器任务启动失败")
                },
            )
        }
    }

    fun switchManagedPlexUser(
        server: SavedServer,
        userId: String,
        pin: String,
    ) {
        val ready = _management.value as? ServerManagementUiState.Ready ?: return
        _management.value = ready.copy(busyId = "home:$userId", message = null, error = null)
        scope.launch {
            repo.switchPlexServerHomeUser(server, userId, pin).fold(
                onSuccess = { authenticated ->
                    val replacement =
                        authenticated.toSavedServer(
                            serverName = server.serverName,
                            localCleartextConfirmed = server.localCleartextConfirmed,
                        )
                    if (!registry.replace(server.id, replacement)) {
                        _management.value = ready.copy(error = "服务器已不存在，请重新打开管理中心")
                        return@fold
                    }
                    val updated = registry.serverById(replacement.id) ?: replacement
                    loadManagement(updated)
                },
                onFailure = {
                    _management.value = ready.copy(error = it.message ?: "Plex Home 用户切换失败")
                },
            )
        }
    }

    fun setSortOrder(value: ServerSortOrder) {
        _listFilter.update { it.copy(sort = value) }
    }

    fun setAccountFilter(value: String?) {
        _listFilter.update { it.copy(account = value) }
    }

    fun setLatencyFilter(value: ServerLatencyFilter) {
        _listFilter.update { it.copy(latency = value) }
    }

    /**
     * Re-probes every saved server and re-reads its totals.
     *
     * Health and totals are refreshed together because they answer the same question from the
     * user's side — "is this server worth opening right now" — and splitting them would leave
     * a card reporting 40 ms beside counts read a week ago.
     */
    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            try {
                val servers = registry.data.value.servers
                health.refreshAll(servers)
                refreshStats(this, servers)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Re-probes one card from its context menu without making every server flash. */
    fun refreshHealth(server: SavedServer) {
        scope.launch {
            health.refresh(server)
            refreshStats(this, listOf(server))
        }
    }

    /**
     * Reads totals for servers that have none yet.
     *
     * Called on first composition, so it deliberately skips servers already cached: opening
     * the tab must not fire a request per server on every visit when the numbers move by a
     * handful of titles a week.
     */
    fun primeStats() {
        scope.launch {
            val missing =
                registry.data.value.servers
                    .filter { stats.statsFor(it.id) == null }
            if (missing.isNotEmpty()) refreshStats(this, missing)
        }
    }

    private suspend fun refreshStats(
        scope: CoroutineScope,
        servers: List<SavedServer>,
    ) {
        servers
            .map { server ->
                scope.async {
                    repo.itemCounts(server).onSuccess { stats.record(server.id, it) }
                }
            }.awaitAll()
    }

    /** Saves an edited route list, then re-probes so the new addresses report immediately. */
    fun setRoutes(
        serverId: String,
        routes: List<ServerRoute>,
        localCleartextConfirmed: Boolean = false,
    ) {
        if (!registry.setRoutes(serverId, routes, localCleartextConfirmed)) return
        registry.serverById(serverId)?.let { updated ->
            scope.launch { health.refresh(updated) }
        }
    }

    /** Moves a server onto one of its routes by hand. */
    fun activateRoute(
        serverId: String,
        routeId: String,
    ) {
        if (!registry.activateRoute(serverId, routeId)) return
        registry.serverById(serverId)?.let { updated ->
            scope.launch { health.refresh(updated) }
        }
    }

    fun setIcon(
        serverId: String,
        emoji: String?,
        tint: Long?,
    ) {
        registry.setIcon(serverId, emoji, tint)
    }

    /**
     * Removing a server takes its cached library with it — those shelves would otherwise
     * outlive the machine they were read from — and its watch history, which describes a
     * server that no longer exists.
     */
    fun removeServer(id: String) {
        store.accept(ServersIntent.Remove(id))
        libraryCache.clear(id)
        val remaining =
            registry.data.value.servers
                .mapTo(mutableSetOf()) { it.id }
        activity.retainOnly(remaining)
        stats.retainOnly(remaining)
    }

    init {
        lifecycle.doOnDestroy { store.dispose() }
    }
}
