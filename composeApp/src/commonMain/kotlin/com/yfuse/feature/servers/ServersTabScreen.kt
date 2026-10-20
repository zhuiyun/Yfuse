@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.yfuse.feature.servers

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.formatWatchedAgo
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PageLoadingSkeleton
import com.yfuse.core.designsystem.RefreshIndicator
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SkeletonHandoff
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.filesource.FileSourcesSection
import com.yfuse.feature.profile.AddServerDialog
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/** Two on a 360dp phone; wider windows fill with more cards rather than stretching them. */
private val ServerCardMinWidth = 146.dp
internal val ServerHeaderCircleSize = 42.dp

/** How often 「上次观看」 re-reads the clock, so 「刚刚看过」 becomes 「1 分钟前」 on its own. */
private const val AGE_TICK_MS = 30_000L

/** Why a child profile finds nothing here to add, edit or remove, and the way to an adult one. */
internal const val CHILD_PROFILE_NOTE = "儿童资料不能管理服务器，请用家长 PIN 切换到成人资料"

/**
 * 服务器 — every saved server as a card: what it is called, whether it answers and how
 * quickly, and how long it has been since anyone watched anything on it.
 *
 * Tapping a card makes it the one the rest of the app reads from and moves to 库, because
 * that is the only reason to pick a server. Everything else — renaming, re-authenticating,
 * removing — is a long press, which keeps the destructive action off the surface of a grid
 * whose ordinary gesture is a single tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServersGridScreen(component: ServersTabComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val routeVisible = LocalRouteVisible.current
    val health by component.health.health.collectAsState()
    val checking by component.health.checking.collectAsState()
    val lastWatched by component.activity.lastWatched.collectAsState()
    val serverStats by component.stats.stats.collectAsState()
    val layout by component.layout.collectAsState()
    val listFilter by component.listFilter.collectAsState()
    val managementState by component.management.collectAsState()
    val access by component.access.collectAsState()
    // A child profile may choose among its servers but not change them: the registry refuses the
    // edit, and offering it only led to that refusal.
    val canManage = access.canManageServers

    // A server that refuses its saved session fails every request 库 would make, so its card opens
    // the sign-in form instead. One that is only offline still opens: 库 shows what it cached.
    fun signsInFirst(server: SavedServer): Boolean =
        canManage && health[server.id]?.status == ServerHealthStatus.AuthRequired

    val gridState = rememberLazyGridState()
    val share = rememberShareHandler()

    // Servers whose totals were never read — a first launch, or one added since — are read
    // once when the tab is first composed rather than on every visit.
    LaunchedEffect(Unit) { component.primeStats() }

    StatusBarIconStyle(darkIcons = !palette.isDark)
    ScrollToTopOnReselect(gridState)

    // The ages on the cards are relative, so they go stale where nothing else does. One
    // clock for the whole grid rather than one per card.
    var nowEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(routeVisible) {
        if (!routeVisible) return@LaunchedEffect
        nowEpochMs = System.currentTimeMillis()
        while (true) {
            delay(AGE_TICK_MS)
            nowEpochMs = System.currentTimeMillis()
        }
    }

    var actionsFor by remember { mutableStateOf<SavedServer?>(null) }
    var confirmRemove by remember { mutableStateOf<SavedServer?>(null) }
    var routesFor by remember { mutableStateOf<SavedServer?>(null) }
    var iconFor by remember { mutableStateOf<SavedServer?>(null) }
    var filterVisible by remember { mutableStateOf(false) }
    var diagnosticsFor by remember { mutableStateOf<SavedServer?>(null) }
    var managementFor by remember { mutableStateOf<SavedServer?>(null) }
    // The refresh round now has a real completion signal — it awaits both the probes and the
    // count requests — so the spinner tracks the work instead of a fixed delay.
    val refreshState by component.refreshState.collectAsState()
    val refreshing = refreshState.refreshing
    var requestedRefreshGeneration by remember { mutableStateOf<Long?>(null) }
    // One indicator per request: a pull shows the pull orb, the header key (and TalkBack's 刷新
    // action) the orb in the key. Both at once was two spinners for the same round of probes.
    var refreshByPull by remember { mutableStateOf(false) }
    val requestRefresh: (Boolean) -> Unit = { byPull ->
        component.refreshAll()?.let {
            requestedRefreshGeneration = it
            refreshByPull = byPull
        }
    }
    SideEffect {
        if (!routeVisible) requestedRefreshGeneration = null
    }
    // Only a failure is worth a toast: a refresh that worked shows in the cards it updated, and its
    // indicator has already said when it finished.
    val refreshFeedback =
        serverRefreshFeedback(refreshState, requestedRefreshGeneration, routeVisible)?.takeIf {
            it.result == ServerRefreshResult.Failure || it.result == ServerRefreshResult.PartialFailure
        }
    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = refreshing)

    LaunchedEffect(refreshing) {
        if (refreshing) return@LaunchedEffect
        nowEpochMs = System.currentTimeMillis()
    }
    val visibleServers =
        remember(state.servers, health, lastWatched, listFilter) {
            filterAndSortServers(state.servers, health, lastWatched, listFilter)
        }
    val currentServer =
        remember(state.servers, state.defaultServerId) {
            state.servers.firstOrNull { it.id == state.defaultServerId }
        }
    val otherVisibleServers =
        remember(visibleServers, state.defaultServerId) {
            visibleServers.filterNot { it.id == state.defaultServerId }
        }
    val onlineServerCount =
        remember(state.servers, health) {
            state.servers.count {
                health[it.id]?.status in
                    setOf(ServerHealthStatus.Healthy, ServerHealthStatus.Degraded)
            }
        }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing && refreshByPull,
            onRefresh = { requestRefresh(true) },
            state = pullState,
            indicator = {
                RefreshIndicator(pullState, refreshing && refreshByPull, Modifier.align(Alignment.TopCenter))
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            SkeletonHandoff(
                loading = !state.initialized,
                modifier = Modifier.fillMaxSize(),
                skeleton = { PageLoadingSkeleton() },
            ) {
                LookaheadScope {
                    val cardLookahead = this
                    LazyVerticalGrid(
                        // 列表 is one column of the same card, not a second card design: everything
                        // the grid card shows is worth showing in a row too, and two implementations
                        // of the same thing drift apart on the next change to either.
                        columns =
                            if (layout == ServerLayout.List) {
                                GridCells.Fixed(1)
                            } else {
                                GridCells.Adaptive(ServerCardMinWidth)
                            },
                        state = gridState,
                        // No handoff over the whole grid: it faded 添加, 刷新 and the layout key in
                        // with every filter that emptied the list. The results hand over on their
                        // own — cards and the empty line fade in and out as items.
                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        contentPadding =
                            PaddingValues(
                                start = Dimens.pageHorizontal,
                                end = Dimens.pageHorizontal,
                                top = Dimens.contentTop,
                                // Clears the dock however tall it is: three-key navigation and
                                // large text both push it up past a fixed inset.
                                bottom = floatingNavigationContentInset(),
                            ),
                        // The cards carry their own shadow, so the air between them has to be
                        // wider than the shadow or the grid reads as one slab of tiles.
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        motionItem(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            ServersHeader(
                                onAdd = { component.store.accept(ServersIntent.OpenAddDialog) }.takeIf { canManage },
                                refreshing = refreshing,
                                refreshingFromKey = refreshing && !refreshByPull,
                                onRefreshAll = { requestRefresh(false) },
                                layout = layout,
                                onLayout = component::setLayout,
                                filter = listFilter,
                                onFilter = { filterVisible = true },
                            )
                        }

                        if (!canManage) {
                            motionItem(key = "child-profile", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (state.servers.isEmpty()) {
                                        "这个资料还没有关联服务器，请用家长 PIN 切换到成人资料后添加"
                                    } else {
                                        CHILD_PROFILE_NOTE
                                    },
                                    style = AppTypography.body.medium,
                                    color = palette.sub2,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }

                        currentServer?.let { server ->
                            motionItem(
                                key = server.id,
                                contentType = "current-server",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                val signIn = signsInFirst(server)
                                CurrentServerHero(
                                    server = server,
                                    health = health[server.id],
                                    checking = (checking[server.id] ?: 0) > 0,
                                    stats = serverStats[server.id],
                                    serverCount = state.servers.size,
                                    onlineCount = onlineServerCount,
                                    signInOnOpen = signIn,
                                    onOpen = {
                                        if (signIn) component.reauthenticate(server) else component.onOpenLibrary()
                                    },
                                    onMore = { actionsFor = server },
                                    modifier = Modifier,
                                )
                            }
                        }

                        if (state.servers.isNotEmpty()) {
                            motionItem(key = "other-servers", span = { GridItemSpan(maxLineSpan) }) {
                                OtherServersHeader(count = otherVisibleServers.size)
                            }
                        }

                        // A child profile's empty page is the note above: it has no way to add one.
                        if (state.servers.isEmpty() && canManage) {
                            motionItem(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyServers(
                                    onAdd = { component.store.accept(ServersIntent.OpenAddDialog) },
                                )
                            }
                        }

                        if (state.servers.isNotEmpty() && otherVisibleServers.isEmpty()) {
                            motionItem(key = "filtered-empty", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (state.servers.size == 1) {
                                        "还没有其他服务器"
                                    } else {
                                        "没有其他符合当前筛选的服务器"
                                    },
                                    style = AppTypography.body.medium,
                                    color = palette.sub2,
                                    modifier = Modifier.padding(bottom = 24.dp),
                                )
                            }
                        }

                        items(otherVisibleServers, key = { it.id }, contentType = { "server-card" }) { server ->
                            val moving = !LocalAccessibilityOptions.current.reduceMotion && routeVisible
                            val cardMotion =
                                Modifier
                                    .animateItem(
                                        fadeInSpec = if (moving) Motion.tween(Motion.QUICK) else null,
                                        placementSpec = null,
                                        fadeOutSpec = if (moving) Motion.tween(Motion.STANDARD) else null,
                                    ).then(
                                        if (moving) {
                                            Modifier.animateBounds(
                                                lookaheadScope = cardLookahead,
                                                boundsTransform = { _, _ -> Motion.settle() },
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                            val signIn = signsInFirst(server)
                            ServerCard(
                                server = server,
                                isCurrent = false,
                                health = health[server.id],
                                checking = (checking[server.id] ?: 0) > 0,
                                stats = serverStats[server.id],
                                lastWatchedLabel = formatWatchedAgo(lastWatched[server.id], nowEpochMs),
                                signInOnClick = signIn,
                                onClick = {
                                    if (signIn) {
                                        component.reauthenticate(server)
                                    } else {
                                        component.store.accept(ServersIntent.SelectDefault(server.id))
                                        component.onOpenLibrary()
                                    }
                                },
                                onMore = { actionsFor = server },
                                modifier = cardMotion,
                            )
                        }

                        // 文件来源 below the servers. A child profile can neither add one nor play
                        // a file that belongs to no server, so it is not offered one.
                        if (canManage) {
                            motionItem(key = "file-sources", span = { GridItemSpan(maxLineSpan) }) {
                                FileSourcesSection(component.fileSources)
                            }
                        }
                    }
                }
            }
        }
        // A new request cancels the previous toast's timer; it cannot dismiss this generation.
        key(if (routeVisible) refreshState.generation else null) {
            val feedbackGeneration = refreshState.generation
            ActionToast(
                message = refreshFeedback?.message,
                onDismiss = {
                    if (requestedRefreshGeneration == feedbackGeneration) requestedRefreshGeneration = null
                },
                accent = refreshFeedback?.let { refreshResultColor(it.result) },
            )
        }
    }

    actionsFor?.let { server ->
        ServerActionsDialog(
            server = server,
            isCurrent = server.id == state.defaultServerId,
            canManage = canManage,
            health = health[server.id],
            onOpenLibrary = {
                if (server.id != state.defaultServerId) {
                    component.store.accept(ServersIntent.SelectDefault(server.id))
                }
                actionsFor = null
                component.onOpenLibrary()
            },
            onRefresh = {
                component.refreshHealth(server)
            },
            onCopyAddress = {
                share.copyText(server.baseUrl)
            },
            onSetDefault = {
                component.store.accept(ServersIntent.SelectDefault(server.id))
                actionsFor = null
            },
            onRoutes = {
                routesFor = server
                actionsFor = null
            },
            onDiagnostics = {
                diagnosticsFor = server
                actionsFor = null
            },
            onManage = {
                managementFor = server
                component.loadManagement(server)
                actionsFor = null
            },
            onIcon = {
                iconFor = server
                actionsFor = null
            },
            onEdit = {
                component.store.accept(ServersIntent.EditServer(server))
                actionsFor = null
            },
            onRemove = {
                confirmRemove = server
                actionsFor = null
            },
            onDismiss = { actionsFor = null },
        )
    }

    confirmRemove?.let { server ->
        val isCurrent = server.id == state.defaultServerId
        ConfirmDialog(
            title = "移除服务器",
            message =
                if (isCurrent) {
                    "将退出「${server.serverName}」并从列表中移除，已下载的离线内容会保留。"
                } else {
                    "将从列表中移除「${server.serverName}」，之后可以重新登录。"
                },
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                component.removeServer(server.id)
                confirmRemove = null
            },
            onDismiss = { confirmRemove = null },
        )
    }

    // Re-read from state so an edit made in the sheet is reflected without reopening it.
    routesFor?.let { opened ->
        val live = state.servers.firstOrNull { it.id == opened.id }
        if (live == null) {
            routesFor = null
        } else {
            ServerRoutesDialog(
                server = live,
                health = health[live.id],
                checking = (checking[live.id] ?: 0) > 0,
                onActivate = { component.activateRoute(live.id, it) },
                onSave = { routes, cleartextConfirmed ->
                    component.setRoutes(live.id, routes, cleartextConfirmed)
                },
                onProbe = { component.refreshHealth(live) },
                onDismiss = { routesFor = null },
            )
        }
    }

    iconFor?.let { opened ->
        val live = state.servers.firstOrNull { it.id == opened.id }
        if (live == null) {
            iconFor = null
        } else {
            ServerIconDialog(
                server = live,
                onSave = { emoji, tint ->
                    component.setIcon(live.id, emoji, tint)
                    iconFor = null
                },
                onDismiss = { iconFor = null },
            )
        }
    }

    // Held through its exit: a connection that succeeds closes the form from the store in the
    // same step that empties it, so it leaves showing what was entered instead of vanishing.
    DialogPresence(state.takeIf { it.dialogVisible }) { shown ->
        AddServerDialog(
            state = shown,
            onIntent = component.store::accept,
            onDismiss = { component.store.accept(ServersIntent.DismissDialog) },
        )
    }

    if (filterVisible) {
        ServerFilterDialog(
            servers = state.servers,
            filter = listFilter,
            onSort = component::setSortOrder,
            onAccount = component::setAccountFilter,
            onLatency = component::setLatencyFilter,
            onDismiss = { filterVisible = false },
        )
    }

    diagnosticsFor?.let { opened ->
        val live = state.servers.firstOrNull { it.id == opened.id }
        if (live == null) {
            diagnosticsFor = null
        } else {
            ServerTransportDiagnosticsDialog(
                server = live,
                health = health[live.id],
                checking = (checking[live.id] ?: 0) > 0,
                onProbe = { component.refreshHealth(live) },
                onDismiss = { diagnosticsFor = null },
            )
        }
    }

    managementFor?.let { opened ->
        val live = state.servers.firstOrNull { it.id == opened.id || opened.id in it.previousIds }
        if (live == null) {
            managementFor = null
            component.closeManagement()
        } else {
            val matchingState = managementState.forServer(live.id)
            val sessionId = (matchingState as? ServerManagementUiState.Ready)?.sessionId
            ServerManagementDialog(
                server = live,
                state = matchingState,
                onReload = { component.loadManagement(live) },
                onRefreshLibrary = { id -> sessionId?.let { component.refreshManagedLibrary(live, id, it) } },
                onRunTask = { id -> sessionId?.let { component.runManagedTask(live, id, it) } },
                onSwitchHomeUser = { userId, pin ->
                    sessionId?.let { component.switchManagedPlexUser(live, userId, pin, it) }
                },
                onDismiss = {
                    managementFor = null
                    component.closeManagement()
                },
            )
        }
    }
}
