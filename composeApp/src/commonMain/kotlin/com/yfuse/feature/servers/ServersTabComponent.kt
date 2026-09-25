package com.yfuse.feature.servers

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnPause
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ServerStatsStore
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.personal.PersonalAccessPolicy
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
            quickConnectGateway = dependencies.quickConnectGateway,
            onAuthenticated = dependencies.playbackReportingCoordinator::resumeAfterAuthentication,
        ).create()

    val health: ServerHealthMonitor = dependencies.serverHealthMonitor
    val activity: ServerActivityStore = dependencies.serverActivity
    val stats: ServerStatsStore = dependencies.serverStats

    private val personal by lazy {
        org.koin.core.context.GlobalContext
            .get()
            .get<PersonalLibraryRepository>()
    }

    /**
     * Who is browsing. A child profile may pick among the servers it was given but not change
     * them — the registry refuses every such edit — so the tab offers none; see
     * [PersonalAccessPolicy.canManageServers].
     */
    val access: StateFlow<PersonalAccessPolicy> get() = personal.policy

    /** Arriving from 库's empty page: the add form, open — unless this profile cannot add one. */
    fun openAddServer() {
        if (personal.policy.value.canManageServers) store.accept(ServersIntent.OpenAddDialog)
    }

    /** Grid or list; see [ServerLayout]. */
    val layout: StateFlow<ServerLayout> = themePreferences.serverLayout

    suspend fun checkSetup(serverId: String): Result<String> {
        val server =
            registry.serverById(serverId)
                ?: return Result.failure(IllegalStateException("当前资料无法访问此服务器"))
        val latency = repo.probeServer(server).getOrElse { return Result.failure(it) }
        val libraries = repo.libraries(server).getOrElse { return Result.failure(it) }
        return Result.success(
            if (libraries.isEmpty()) {
                "连接成功（${latency}ms），但该用户没有可见媒体库，请联系服务器管理员授权。"
            } else {
                "连接成功（${latency}ms），可访问 ${libraries.size} 个媒体库。"
            },
        )
    }

    fun setLayout(value: ServerLayout) = themePreferences.setServerLayout(value)

    private val libraryCache: LibraryCache = dependencies.libraryCache
    private val scope = componentScope(lifecycle)

    private val refreshController =
        ServerRefreshController(scope) {
            val servers = registry.data.value.servers
            val healthResults = health.refreshAllResults(servers)
            val statsResults = refreshStats(servers.map { it.id })
            summarizeServerRefresh(servers.map { it.id }, healthResults, statsResults)
        }

    private val _listFilter = MutableStateFlow(ServerListFilter())

    /** Sorting, latency and account filtering stay with this tab while the app is alive. */
    val listFilter: StateFlow<ServerListFilter> = _listFilter.asStateFlow()

    /** Each explicit refresh has its own real result, separate from background/initial probes. */
    val refreshState: StateFlow<ServerRefreshState> = refreshController.state

    /** Existing non-phone surfaces retain their Boolean loading contract. */
    val refreshing: StateFlow<Boolean> =
        refreshState
            .map { it.refreshing }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * What the store has to say once its form or menu has closed — a save, or an edit the
     * registry refused. The shell shows it, so it stays up whichever tab the app is on by then.
     */
    val notice: StateFlow<String?> =
        store.states
            .map { it.notice }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun dismissNotice() = store.accept(ServersIntent.DismissNotice)

    private fun currentServer(id: String): SavedServer? =
        registry.data.value.servers
            .firstOrNull { it.id == id }

    private val managementController = ServerManagementController(scope, ::currentServer, repo::serverManagement)
    val management: StateFlow<ServerManagementUiState> = managementController.state

    fun loadManagement(server: SavedServer) = managementController.open(server.id)

    fun closeManagement() = managementController.close()

    fun refreshManagedLibrary(
        server: SavedServer,
        libraryId: String,
        sessionId: Long,
    ) {
        managementController.submit(
            server.id,
            sessionId,
            "library:$libraryId",
            accepts = { it.libraries.any { library -> library.id == libraryId } },
        ) { target ->
            repo.refreshLibrary(target, libraryId).map {
                ServerManagementActionResult("已提交媒体库扫描任务")
            }
        }
    }

    fun runManagedTask(
        server: SavedServer,
        taskId: String,
        sessionId: Long,
    ) {
        managementController.submit(
            server.id,
            sessionId,
            "task:$taskId",
            accepts = { it.supportsScheduledTasks && it.tasks.any { task -> task.id == taskId } },
        ) { target ->
            repo.runServerTask(target, taskId).map { ServerManagementActionResult("服务器任务已启动") }
        }
    }

    fun switchManagedPlexUser(
        server: SavedServer,
        userId: String,
        pin: String,
        sessionId: Long,
    ) {
        managementController.submit(
            server.id,
            sessionId,
            "home:$userId",
            accepts = { it.supportsPlexHomeSwitch && it.plexHomeUsers.any { user -> user.id == userId } },
        ) { target ->
            repo.switchPlexServerHomeUser(target, userId, pin).mapCatching { authenticated ->
                // This action was accepted for target, even if a different panel is now visible.
                val current = currentServer(target.id)
                check(current?.sameManagementAccount(target) == true) { "服务器会话已改变，请重新打开管理中心" }
                val replacement =
                    authenticated.toSavedServer(
                        serverName = current.serverName,
                        localCleartextConfirmed = current.localCleartextConfirmed,
                    )
                check(registry.replace(target.id, replacement)) { "服务器已不存在，请重新打开管理中心" }
                ServerManagementActionResult("Plex Home 用户已切换", replacement.id)
            }
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
    fun refreshAll(): Long? = refreshController.refreshAll()

    /** Re-probes one card from its context menu without making every server flash. */
    fun refreshHealth(server: SavedServer) {
        scope.launch {
            health.refresh(server)
            refreshStats(listOf(server.id))
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
            if (missing.isNotEmpty()) refreshStats(missing.map { it.id })
        }
    }

    private suspend fun refreshStats(serverIds: List<String>): Map<String, Result<Unit>> =
        refreshCurrentServerStats(serverIds, ::currentServer, repo::itemCounts, stats::record)

    /**
     * Runs one registry edit made from this tab. The registry refuses a child profile's edits by
     * throwing, and the throw used to leave through the tap that asked and take the app down; it
     * is the tab's notice instead.
     */
    private fun <T> editRegistry(edit: () -> T): T? =
        runCatching(edit)
            .onFailure {
                AppLog.warning(
                    category = "server.registry",
                    event = "edit_refused",
                    message = "Saved server edit was refused",
                    throwable = it,
                )
                store.accept(ServersIntent.ShowNotice(it.registryEditMessage()))
            }.getOrNull()

    /** Saves an edited route list, then re-probes so the new addresses report immediately. */
    fun setRoutes(
        serverId: String,
        routes: List<ServerRoute>,
        localCleartextConfirmed: Boolean = false,
    ) {
        if (editRegistry { registry.setRoutes(serverId, routes, localCleartextConfirmed) } != true) return
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
        editRegistry { registry.setIcon(serverId, emoji, tint) }
    }

    /**
     * Removing a server takes its cached library with it — those shelves would otherwise
     * outlive the machine they were read from — and its watch history, which describes a
     * server that no longer exists.
     */
    fun removeServer(id: String) {
        managementController.closeIfServer(id)
        store.accept(ServersIntent.Remove(id))
        // A refused removal (a child profile's) leaves the server, and so its cache, in place.
        val refused =
            registry.data.value.servers
                .any { it.id == id }
        if (refused) return
        libraryCache.clear(id)
        val remaining =
            registry.data.value.servers
                .mapTo(mutableSetOf()) { it.id }
        activity.retainOnly(remaining)
        stats.retainOnly(remaining)
    }

    init {
        lifecycle.doOnPause { refreshController.suppressFeedback() }
        lifecycle.doOnDestroy { store.dispose() }
    }
}
