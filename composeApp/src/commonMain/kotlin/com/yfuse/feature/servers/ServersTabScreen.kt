package com.yfuse.feature.servers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.LatencySeverity
import com.yfuse.core.data.RouteHealth
import com.yfuse.core.data.SERVER_ICON_EMOJI_MAX_CHARS
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.ServerStats
import com.yfuse.core.data.formatServerCount
import com.yfuse.core.data.formatWatchedAgo
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.ServerIconTints
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.TabBarInset
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverTintColor
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.network.validateEmbyServerEndpoint
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.profile.AddServerDialog
import kotlinx.coroutines.delay

/** Two on a 360dp phone; wider windows fill with more cards rather than stretching them. */
private val ServerCardMinWidth = 146.dp
private val ServerHeaderCircleSize = 42.dp

/** How often 「上次观看」 re-reads the clock, so 「刚刚看过」 becomes 「1 分钟前」 on its own. */
private const val AGE_TICK_MS = 30_000L

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
fun ServersTabScreen(component: ServersTabComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val health by component.health.health.collectAsState()
    val lastWatched by component.activity.lastWatched.collectAsState()
    val serverStats by component.stats.stats.collectAsState()
    val layout by component.layout.collectAsState()
    val listFilter by component.listFilter.collectAsState()
    val managementState by component.management.collectAsState()
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
    LaunchedEffect(Unit) {
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
    val refreshing by component.refreshing.collectAsState()
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
            isRefreshing = refreshing,
            onRefresh = { component.refreshAll() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
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
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding =
                    PaddingValues(
                        start = Dimens.pageHorizontal,
                        end = Dimens.pageHorizontal,
                        top = Dimens.contentTop,
                        bottom = TabBarInset,
                    ),
                // The cards carry their own shadow, so the air between them has to be
                // wider than the shadow or the grid reads as one slab of tiles.
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ServersHeader(
                        onAdd = { component.store.accept(ServersIntent.OpenAddDialog) },
                        refreshing = refreshing,
                        onRefreshAll = { component.refreshAll() },
                        layout = layout,
                        onLayout = component::setLayout,
                        filter = listFilter,
                        onFilter = { filterVisible = true },
                    )
                }

                currentServer?.let { server ->
                    item(key = "current-${server.id}", span = { GridItemSpan(maxLineSpan) }) {
                        CurrentServerHero(
                            server = server,
                            health = health[server.id],
                            stats = serverStats[server.id],
                            serverCount = state.servers.size,
                            onlineCount = onlineServerCount,
                            onOpen = { component.onOpenLibrary() },
                            onMore = { actionsFor = server },
                        )
                    }
                }

                if (state.servers.isNotEmpty()) {
                    item(key = "other-servers", span = { GridItemSpan(maxLineSpan) }) {
                        OtherServersHeader(count = otherVisibleServers.size)
                    }
                }

                if (state.servers.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyServers(
                            onAdd = { component.store.accept(ServersIntent.OpenAddDialog) },
                        )
                    }
                }

                if (state.servers.isNotEmpty() && otherVisibleServers.isEmpty()) {
                    item(key = "filtered-empty", span = { GridItemSpan(maxLineSpan) }) {
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

                items(otherVisibleServers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        isCurrent = false,
                        health = health[server.id],
                        stats = serverStats[server.id],
                        lastWatchedLabel = formatWatchedAgo(lastWatched[server.id], nowEpochMs),
                        onClick = {
                            component.store.accept(ServersIntent.SelectDefault(server.id))
                            component.onOpenLibrary()
                        },
                        onMore = { actionsFor = server },
                    )
                }
            }
        }
    }

    actionsFor?.let { server ->
        ServerActionsDialog(
            server = server,
            isCurrent = server.id == state.defaultServerId,
            health = health[server.id],
            lastWatchedLabel = formatWatchedAgo(lastWatched[server.id], nowEpochMs),
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

    if (state.dialogVisible) {
        AddServerDialog(
            state = state,
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
                onProbe = { component.refreshHealth(live) },
                onDismiss = { diagnosticsFor = null },
            )
        }
    }

    managementFor?.let { opened ->
        val live = state.servers.firstOrNull { it.id == opened.id }
        if (live == null) {
            managementFor = null
            component.closeManagement()
        } else {
            ServerManagementDialog(
                server = live,
                state = managementState,
                onReload = { component.loadManagement(live) },
                onRefreshLibrary = { component.refreshManagedLibrary(live, it) },
                onRunTask = { component.runManagedTask(live, it) },
                onSwitchHomeUser = { userId, pin ->
                    component.switchManagedPlexUser(live, userId, pin)
                },
                onDismiss = {
                    managementFor = null
                    component.closeManagement()
                },
            )
        }
    }
}

/** 「3 条线路 · 2 条可用」, or the single address when there is nothing to choose between. */
private fun routesSummary(
    server: SavedServer,
    health: ServerHealth?,
): String {
    val routes = server.effectiveRoutes
    if (routes.size <= 1) return "只有主线路，可添加备用地址"
    val reachable =
        health
            ?.routes
            ?.takeIf { it.isNotEmpty() }
            ?.let { probed -> routes.count { probed[it.id]?.reachable == true } }
    val availability = reachable?.let { "$it 条可用" } ?: "正在检查"
    return "${routes.size} 条线路 · $availability · 当前${server.activeRoute.name}"
}

@Composable
private fun ServersHeader(
    onAdd: () -> Unit,
    refreshing: Boolean,
    onRefreshAll: () -> Unit,
    layout: ServerLayout,
    onLayout: (ServerLayout) -> Unit,
    filter: ServerListFilter,
    onFilter: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val spin by rememberInfiniteTransition(label = "servers-refresh").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(Motion.REFRESH_SPIN, easing = LinearEasing),
            ),
        label = "servers-refresh-angle",
    )
    Column(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "我的服务器",
                style = AppTypography.display.strong,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .pressable(onClickLabel = "添加服务器", onClick = onAdd)
                    .touchTarget()
                    .shadow(GlassLift.control, GlassShapes.chip)
                    .liquidGlass(
                        shape = GlassShapes.chip,
                        fill = accent.container,
                        border = accent.border.copy(alpha = 0.42f),
                        sheen = 0.7f,
                    ).padding(horizontal = 13.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Add, null, tint = accent.accent, modifier = Modifier.size(13.dp))
                Text("添加", style = AppTypography.body.strong, color = accent.accent)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val filterActive =
                filter.sort != ServerSortOrder.Saved ||
                    filter.account != null ||
                    filter.latency != ServerLatencyFilter.All
            Row(
                Modifier
                    .weight(1f)
                    .pressable(onClickLabel = "打开排序与筛选", onClick = onFilter)
                    .touchTarget()
                    .shadow(GlassLift.control, GlassShapes.chip)
                    .liquidGlass(
                        shape = GlassShapes.chip,
                        fill = if (filterActive) accent.container else palette.card2,
                        border = if (filterActive) accent.border else palette.border,
                        sheen = 0.75f,
                    ).padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Menu,
                    contentDescription = null,
                    tint = if (filterActive) accent.accent else palette.sub2,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    filter.displayLabel(),
                    style = AppTypography.caption.strong,
                    color = if (filterActive) accent.accent else palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .pressable(
                        haptic = HapticSignal.Select,
                        onClickLabel =
                            if (layout == ServerLayout.Grid) "改为列表展示" else "改为网格展示",
                        onClick = {
                            onLayout(
                                if (layout == ServerLayout.Grid) {
                                    ServerLayout.List
                                } else {
                                    ServerLayout.Grid
                                },
                            )
                        },
                    ).touchTarget()
                    .shadow(GlassLift.control, CircleShape)
                    .liquidGlass(CircleShape, palette.card2, palette.border, sheen = 0.75f)
                    .size(ServerHeaderCircleSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (layout == ServerLayout.Grid) AppIcons.Menu else AppIcons.Grid,
                    contentDescription = null,
                    tint = palette.sub2,
                    modifier = Modifier.size(15.dp),
                )
            }
            Box(
                Modifier
                    .pressable(
                        enabled = !refreshing,
                        onClickLabel = "刷新全部服务器",
                        onClick = onRefreshAll,
                    ).touchTarget()
                    .shadow(GlassLift.control, CircleShape)
                    .liquidGlass(CircleShape, palette.card2, palette.border, sheen = 0.75f)
                    .size(ServerHeaderCircleSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Refresh,
                    contentDescription = null,
                    tint = if (refreshing) accent.accent else palette.sub2,
                    modifier =
                        Modifier
                            .size(15.dp)
                            .graphicsLayer { rotationZ = if (refreshing) spin else 0f },
                )
            }
        }
    }
}

@Composable
private fun CurrentServerHero(
    server: SavedServer,
    health: ServerHealth?,
    stats: ServerStats?,
    serverCount: Int,
    onlineCount: Int,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val status = health?.status ?: ServerHealthStatus.Unknown
    val statusColor = serverStatusColor(status)
    val badgeColor = serverTintColor(server.id, server.iconTint)
    val latencyColor =
        latencySeverityColor(health?.latencySeverity ?: LatencySeverity.Unknown)

    Column(
        Modifier
            .fillMaxWidth()
            .semantics { selected = true }
            .pressable(
                onClickLabel = "打开${server.serverName}媒体库",
                onLongClick = onMore,
                onLongClickLabel = "打开${server.serverName}操作",
                onClick = onOpen,
            ).shadow(
                Shadows.primaryButton(accent.accent.copy(alpha = 0.42f)),
                GlassShapes.card,
            ).liquidGlass(
                shape = GlassShapes.card,
                fill = lerp(palette.card, accent.container, if (palette.isDark) 0.26f else 0.22f),
                border = accent.border.copy(alpha = 0.72f),
                over = palette.background,
                sheen = 0.66f,
            ).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(AppShapes.thumb)
                    .background(
                        Brush.linearGradient(
                            listOf(lerp(badgeColor, Color.White, 0.16f), badgeColor),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    server.iconEmoji ?: server.serverName.take(1).uppercase(),
                    style = AppTypography.section.strong,
                    color = if (server.iconEmoji == null) Color.White else Color.Unspecified,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        server.serverName,
                        style = AppTypography.section.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accent.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = "当前服务器",
                            tint = accent.onAccent,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        server.userName.ifBlank { "未记录账号" },
                        style = AppTypography.body.regular,
                        color = palette.sub2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Text(
                        connectionLabel(health),
                        style = AppTypography.body.medium,
                        color = if (status == ServerHealthStatus.Unknown) palette.sub2 else statusColor,
                        maxLines = 1,
                    )
                }
            }
            Row(
                Modifier
                    .pressable(onClickLabel = "进入服务器", onClick = onOpen)
                    .heightIn(min = 42.dp)
                    .glass(AppShapes.pill, accent.accent, accent.border)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("进入", style = AppTypography.body.strong, color = accent.onAccent)
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = accent.onAccent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroMetric(
                icon = AppIcons.User,
                value = serverCount.toString(),
                label = "已连接",
                color = accent.accent,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(1.dp, 42.dp).background(palette.border))
            HeroMetric(
                icon = AppIcons.Server,
                value = onlineCount.toString(),
                label = "在线",
                color = Semantic.Success,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(1.dp, 42.dp).background(palette.border))
            HeroMetric(
                icon = AppIcons.Refresh,
                value = health?.latencyMs?.let { "$it ms" } ?: "未测速",
                label = health?.latencySeverity?.label ?: "延迟",
                color = latencyColor,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        ServerCountsRow(stats)
    }
}

@Composable
private fun HeroMetric(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .glass(GlassShapes.thumb, color.copy(alpha = 0.08f), color.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = AppTypography.body.strong,
                color = if (label == "延迟") palette.text else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OtherServersHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "其他服务器",
            style = AppTypography.section.strong,
            color = LocalPalette.current.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count 台",
            style = AppTypography.caption.medium,
            color = LocalPalette.current.sub2,
        )
    }
}

/**
 * One server: name, reachability, and 上次观看.
 *
 * Laid out as two rows rather than a stack. The first version put the badge, the name, the
 * account, the address, a status chip, the age and a "切换并打开" caption on seven separate
 * lines, which made a 204dp tile — two of them filled a phone. Pairing the badge with the
 * name recovers most of that height, and the card is a card, not a page: it exists to be
 * compared with the eleven others next to it, so what it owes the reader is one glance.
 */
@Composable
private fun ServerCard(
    server: SavedServer,
    isCurrent: Boolean,
    health: ServerHealth?,
    stats: ServerStats?,
    lastWatchedLabel: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val status = health?.status ?: ServerHealthStatus.Unknown
    val statusColor = serverStatusColor(status)
    val badgeColor = serverTintColor(server.id, server.iconTint)
    val surfaceModifier =
        if (isCurrent) {
            Modifier
                .shadow(Shadows.primaryButton(accent.accent.copy(alpha = 0.55f)), GlassShapes.card)
                .liquidGlass(
                    shape = GlassShapes.card,
                    fill = lerp(palette.card, accent.container, if (palette.isDark) 0.26f else 0.20f),
                    border = accent.border.copy(alpha = 0.72f),
                    over = palette.background,
                    sheen = 0.64f,
                )
        } else if (server.iconTint != null) {
            // A tint the user chose is theirs to see. It washes the card from the badge corner
            // and fades out well before the numbers, so the figures keep the page's own contrast
            // rather than being read through a colour.
            Modifier
                .shadow(GlassLift.control, GlassShapes.card)
                .liquidGlass(
                    shape = GlassShapes.card,
                    // One tinted colour, not a gradient: liquidGlass draws its own body ramp
                    // over whatever fill it is given, and it takes a Color.
                    fill = lerp(palette.card, badgeColor, if (palette.isDark) 0.20f else 0.16f),
                    border = lerp(palette.border, badgeColor, 0.28f),
                    sheen = 0.52f,
                )
        } else {
            Modifier
                .shadow(GlassLift.control, GlassShapes.card)
                .liquidGlass(
                    shape = GlassShapes.card,
                    fill = palette.card,
                    border = palette.border,
                    sheen = 0.52f,
                )
        }
    Box(
        Modifier
            .fillMaxWidth()
            .semantics { selected = isCurrent }
            .pressable(
                onClickLabel =
                    if (isCurrent) {
                        "打开${server.serverName}媒体库"
                    } else {
                        "切换到${server.serverName}"
                    },
                onLongClick = onMore,
                onLongClickLabel = "打开${server.serverName}操作",
                onClick = onClick,
            ).then(surfaceModifier),
    ) {
        Column(Modifier.padding(12.dp)) {
            // The header keeps clear of the corner button's 44dp target, so a tap meant for
            // the card cannot land on the menu and vice versa.
            Row(
                Modifier.padding(end = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(AppShapes.thumb)
                        .background(
                            Brush.linearGradient(
                                listOf(lerp(badgeColor, Color.White, 0.14f), badgeColor),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // An emoji is the picture the server does not have. It is drawn as-is rather
                    // than tinted white, because a colour-blocked emoji is no longer the glyph
                    // the user picked.
                    Text(
                        server.iconEmoji ?: server.serverName.take(1).uppercase(),
                        style = AppTypography.body.strong,
                        color = if (server.iconEmoji == null) Color.White else Color.Unspecified,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            server.serverName,
                            style = AppTypography.body.strong,
                            color = palette.text,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(5.dp))
                            Icon(
                                AppIcons.Check,
                                contentDescription = "当前服务器",
                                tint = accent.accent,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    // Which address the card is currently talking to, once there is more than
                    // one to choose between. A single-route server would only ever read 主线路,
                    // which says nothing the card does not already imply, so it keeps the account
                    // in that slot instead.
                    Text(
                        if (server.hasBackupRoutes) {
                            server.activeRoute.name
                        } else {
                            server.userName.ifBlank { "未记录账号" }
                        },
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Text(
                    connectionLabel(health),
                    style = AppTypography.caption.medium,
                    color = if (status == ServerHealthStatus.Unknown) palette.sub2 else statusColor,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    lastWatchedLabel,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val latencySeverity = health?.latencySeverity ?: LatencySeverity.Unknown
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(latencySeverityColor(latencySeverity)),
                )
                Text(
                    latencyLabel(health),
                    style = AppTypography.caption.medium,
                    color = latencySeverityColor(latencySeverity),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            ServerCountsRow(stats)
        }

        // Its own control in the corner rather than a glyph in the header row. Inline, it
        // was a bare 13dp mark that read as decoration and sat wherever the name left it;
        // in the corner with a surface under it, it is somewhere to press and it is in the
        // same place on all twelve cards.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .pressable(onClickLabel = "打开${server.serverName}操作", onClick = onMore)
                .touchTarget(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    // Round, and the app's own glass rather than a flat chip: it sits on a
                    // card that is already liquid glass, so a second, duller material for
                    // the one control on that card read as a sticker on top of it.
                    .liquidGlass(
                        shape = CircleShape,
                        fill = if (isCurrent) accent.container else palette.card2,
                        border = if (isCurrent) accent.border.copy(alpha = 0.5f) else palette.border,
                        sheen = 0.9f,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.More,
                    contentDescription = null,
                    tint = if (isCurrent) accent.accent else palette.sub2,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * 电影 / 剧集 totals, or a dash each until the server has answered once.
 *
 * The dash is deliberate rather than a blank: the row keeps its height whether or not the
 * numbers have arrived, so a grid mid-refresh does not reflow card by card as each server
 * reports. What the machine holds is most of the reason to pick one server over another, and
 * it is the one thing the card could not say before.
 */
@Composable
private fun ServerCountsRow(stats: ServerStats?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerCount(AppIcons.Movie, "电影", stats?.movieCount, Modifier.weight(1f))
        ServerCount(AppIcons.Series, "剧集", stats?.seriesCount, Modifier.weight(1f))
    }
}

@Composable
private fun ServerCount(
    icon: ImageVector,
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = palette.sub2,
            modifier = Modifier.size(13.dp),
        )
        Text(
            formatServerCount(value),
            style = AppTypography.caption.medium,
            color = if (value == null) palette.sub2 else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun serverStatusColor(status: ServerHealthStatus): Color =
    when (status) {
        ServerHealthStatus.Healthy -> Semantic.Success
        ServerHealthStatus.Degraded -> Semantic.Warning
        ServerHealthStatus.AuthRequired -> Semantic.Error
        else -> Semantic.Offline
    }

internal fun latencySeverityColor(severity: LatencySeverity): Color =
    when (severity) {
        LatencySeverity.Stable -> Semantic.Success
        LatencySeverity.Slow -> Semantic.Warning
        LatencySeverity.Unstable -> Semantic.Error
        LatencySeverity.Unknown -> Semantic.Offline
    }

internal fun connectionLabel(health: ServerHealth?): String =
    when (health?.status) {
        ServerHealthStatus.Healthy -> "在线"
        ServerHealthStatus.Degraded -> "服务异常"
        ServerHealthStatus.Offline -> "无法连接"
        ServerHealthStatus.AuthRequired -> "需重新登录"
        else -> "正在检查"
    }

/**
 * The number when there is one, and the reason when there is not.
 *
 * [ServerHealth.summary] already says both, but it leads with 在线 — the word the coloured
 * dot beside it has just said — and buries the milliseconds behind a separator.
 */
internal fun latencyLabel(health: ServerHealth?): String =
    health?.latencyMs?.let { "${health.latencySeverity.label} · $it ms" } ?: "延迟 · 未测速"

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Server, null, tint = palette.sub2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text("连接一台 Emby 服务器", style = AppTypography.body.strong, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "填入地址和账号即可，之后可以随时在这里切换",
            style = AppTypography.caption.regular,
            color = palette.sub2,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "添加服务器",
            style = AppTypography.body.strong,
            color = accent.onAccent,
            modifier =
                Modifier
                    .pressable(onClick = onAdd)
                    .heightIn(min = MinTouchTarget)
                    .glass(GlassShapes.chip, accent.accent, accent.border)
                    .padding(horizontal = 22.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun ServerManagementDialog(
    server: SavedServer,
    state: ServerManagementUiState,
    onReload: () -> Unit,
    onRefreshLibrary: (String) -> Unit,
    onRunTask: (String) -> Unit,
    onSwitchHomeUser: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var plexHomePin by remember(server.id) { mutableStateOf("") }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "服务器管理",
            subtitle = server.serverName,
            onClose = onDismiss,
        )
        when (state) {
            ServerManagementUiState.Idle,
            is ServerManagementUiState.Loading,
            -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    Text("正在读取媒体库与服务器任务…", style = AppTypography.body.medium, color = palette.body)
                }
            }
            is ServerManagementUiState.Error -> {
                Text(state.message, style = AppTypography.body.medium, color = palette.error)
                Spacer(Modifier.height(12.dp))
                OverlayButton(
                    label = "重试",
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    tone = OverlayButtonTone.Primary,
                )
            }
            is ServerManagementUiState.Ready -> {
                if (state.snapshot.supportsPlexHomeSwitch && state.snapshot.plexHomeUsers.isNotEmpty()) {
                    Text("Plex Home 用户", style = AppTypography.caption.strong, color = palette.sub2)
                    Spacer(Modifier.height(8.dp))
                    if (state.snapshot.plexHomeUsers.any { it.pinProtected }) {
                        YfFormField(
                            value = plexHomePin,
                            onValueChange = { plexHomePin = it.filter(Char::isDigit).take(4) },
                            label = "受保护用户 PIN",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                        state.snapshot.plexHomeUsers.forEach { user ->
                            OverlayOptionRow(
                                label = user.name,
                                description =
                                    buildString {
                                        append(if (user.admin) "管理员" else "家庭用户")
                                        if (user.pinProtected) append(" · 需要 PIN")
                                    },
                                selected = false,
                                onClick = {
                                    onSwitchHomeUser(
                                        user.id,
                                        plexHomePin.takeIf { user.pinProtected }.orEmpty(),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text("媒体库扫描", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                    state.snapshot.libraries.forEach { library ->
                        OverlayOptionRow(
                            label = library.name,
                            description =
                                if (state.busyId == "library:${library.id}") {
                                    "正在提交扫描…"
                                } else {
                                    "扫描 ${library.collectionType?.ifBlank { "媒体库" } ?: "媒体库"}"
                                },
                            selected = false,
                            onClick = { onRefreshLibrary(library.id) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("服务器任务", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(8.dp))
                if (!state.snapshot.supportsScheduledTasks) {
                    Text(
                        "Plex 没有稳定的通用计划任务接口；媒体库扫描、单项元数据刷新和分析仍可用。",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                } else if (state.snapshot.scheduledTasksError != null) {
                    Text(
                        state.snapshot.scheduledTasksError,
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                } else if (state.snapshot.tasks.isEmpty()) {
                    Text("服务器未返回计划任务。", style = AppTypography.caption.regular, color = palette.sub2)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                        state.snapshot.tasks.forEach { task ->
                            val progress =
                                task.progressPercent
                                    ?.toInt()
                                    ?.let { " · $it%" }
                                    .orEmpty()
                            OverlayOptionRow(
                                label = task.name,
                                description = "${task.state}$progress${task.lastResult?.let { " · 上次 $it" }.orEmpty()}",
                                selected = false,
                                onClick = { onRunTask(task.id) },
                            )
                        }
                    }
                }
                state.message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = AppTypography.caption.medium, color = Semantic.Success)
                }
                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = AppTypography.caption.medium, color = palette.error)
                }
                Spacer(Modifier.height(12.dp))
                OverlayButton(
                    label = "刷新状态",
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    tone = OverlayButtonTone.Plain,
                )
            }
        }
    }
}

/**
 * Long-press menu — the card's own identity, then the things its face cannot carry.
 *
 * The first version was a title, a URL and three bare lines of text, the destructive one
 * wearing a full-bleed pink band that ran into the panel's edges. It read as a form rather
 * than as a menu about one particular machine. This one restates the card — same badge
 * colour, same two facts — so there is never a doubt about which of a dozen servers is
 * about to be removed, and gives every action a glyph, a sentence and a shape of its own.
 */
@Composable
private fun ServerActionsDialog(
    server: SavedServer,
    isCurrent: Boolean,
    health: ServerHealth?,
    lastWatchedLabel: String,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    onCopyAddress: () -> Unit,
    onSetDefault: () -> Unit,
    onRoutes: () -> Unit,
    onDiagnostics: () -> Unit,
    onManage: () -> Unit,
    onIcon: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(AppShapes.thumb)
                    .background(serverTintColor(server.id, server.iconTint)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    server.iconEmoji ?: server.serverName.take(1).uppercase(),
                    style = AppTypography.section.strong,
                    color = if (server.iconEmoji == null) Color.White else Color.Unspecified,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        server.serverName,
                        style = AppTypography.section.strong,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent) {
                        Text(
                            "当前",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier =
                                Modifier
                                    .glass(GlassShapes.chip, accent.container, accent.border)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    server.baseUrl,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // The same two facts the card carries, so the sheet is unmistakably about it.
        Row(
            Modifier
                .fillMaxWidth()
                .glass(GlassShapes.chip, palette.card2, palette.border)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when (health?.status) {
                            ServerHealthStatus.Healthy -> Semantic.Success
                            ServerHealthStatus.Degraded -> Semantic.Warning
                            ServerHealthStatus.AuthRequired -> Semantic.Error
                            else -> Semantic.Offline
                        },
                    ),
            )
            Text(
                connectionLabel(health),
                style = AppTypography.caption.medium,
                color = palette.sub,
                maxLines = 1,
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        latencySeverityColor(
                            health?.latencySeverity ?: LatencySeverity.Unknown,
                        ),
                    ),
            )
            Text(
                latencyLabel(health),
                style = AppTypography.caption.medium,
                color = latencySeverityColor(health?.latencySeverity ?: LatencySeverity.Unknown),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                lastWatchedLabel,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(14.dp))
        ServerActionRow(
            icon = AppIcons.Home,
            label = "打开媒体库",
            description = if (isCurrent) "进入当前服务器的内容" else "切换到此服务器并进入内容",
            prominent = true,
            onClick = overlayAction(onOpenLibrary),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Refresh,
            label = "测试连接",
            description = "重新检查在线状态与访问延迟",
            onClick = onRefresh,
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Cloud,
            label = "复制服务器地址",
            description = server.baseUrl,
            onClick = onCopyAddress,
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Check,
            label = if (isCurrent) "已是当前服务器" else "设为当前服务器",
            description =
                if (isCurrent) {
                    "库、搜索和播放都在读取这一台"
                } else {
                    "库、搜索和播放都会切到这一台"
                },
            enabled = !isCurrent,
            onClick = overlayAction(onSetDefault),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Cast,
            label = "线路",
            description = routesSummary(server, health),
            onClick = overlayAction(onRoutes),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Lock,
            label = "HTTPS 诊断",
            description = "检查主线路与备用地址的传输安全",
            onClick = overlayAction(onDiagnostics),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Server,
            label = "服务器管理",
            description = "扫描媒体库、查看并运行服务器任务",
            onClick = overlayAction(onManage),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Grid,
            label = "图标与颜色",
            description = "给这台服务器一个一眼认得出的样子",
            onClick = overlayAction(onIcon),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Edit,
            label = "编辑连接与名称",
            description = "改名不用重新登录，改地址或账号要",
            onClick = overlayAction(onEdit),
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Close,
            label = "移除服务器",
            description = "已下载的离线内容会保留",
            destructive = true,
            onClick = overlayAction(onRemove),
        )
    }
}

/** One action: a glyph in its own tile, a verb, and what the verb will do. */
@Composable
private fun ServerActionRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    prominent: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val ink =
        when {
            destructive -> palette.error
            !enabled -> palette.sub2
            else -> palette.text
        }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .liquidGlass(
                shape = GlassShapes.chip,
                // The destructive fill stays inside the row's own rounded shape instead of
                // bleeding to the panel's edges the way the old option row's band did.
                fill =
                    when {
                        destructive -> palette.errorContainer
                        prominent -> accent.container
                        else -> palette.card2
                    },
                border =
                    when {
                        destructive -> palette.error.copy(alpha = 0.34f)
                        prominent -> accent.border.copy(alpha = 0.38f)
                        else -> palette.border
                    },
            ).padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(GlassShapes.thumb)
                .background(
                    when {
                        destructive -> palette.error.copy(alpha = 0.12f)
                        prominent -> accent.accent
                        else -> accent.container
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    when {
                        destructive -> palette.error
                        prominent -> accent.onAccent
                        else -> accent.accent
                    },
                modifier = Modifier.size(14.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTypography.body.strong, color = ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 线路 — the addresses one server answers on, which of them is live, and how each performs.
 *
 * Every row is both a report and a switch: the point of keeping a LAN address beside a WAN
 * one is to move between them, and a list that showed the addresses without letting the user
 * pick would leave that to a failover they cannot see. Selecting takes effect immediately
 * rather than on a 保存 — there is one field of state, and confirming a radio button is
 * ceremony.
 *
 * The primary is the address the session was authenticated against, so it can be renamed but
 * not repointed or removed; changing where a server lives is 编辑连接, and it re-authenticates.
 */
@Composable
private fun ServerRoutesDialog(
    server: SavedServer,
    health: ServerHealth?,
    onActivate: (String) -> Unit,
    onSave: (List<ServerRoute>, Boolean) -> Unit,
    onProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val routes = server.effectiveRoutes
    var draftName by remember(server.id) { mutableStateOf("") }
    var draftUrl by remember(server.id) { mutableStateOf("") }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    val atCapacity = routes.size >= ServerRoute.MAX_ROUTES

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "线路",
            subtitle = "同一台服务器的多个地址；无法连接时会自动切到可用线路",
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            routes.forEach { route ->
                ServerRouteRow(
                    route = route,
                    health = health?.route(route.id),
                    isActive = route.id == server.activeRoute.id,
                    isPrimary = route.id == ServerRoute.PRIMARY_ID,
                    onActivate = { onActivate(route.id) },
                    onRemove = { onSave(routes.filterNot { it.id == route.id }, false) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        OverlayButton(
            label = "测速全部线路",
            onClick = onProbe,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        if (atCapacity) {
            Text(
                "已达 ${ServerRoute.MAX_ROUTES} 条线路上限",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
        } else {
            Text("添加备用线路", style = AppTypography.caption.strong, color = palette.sub2)
            Spacer(Modifier.height(8.dp))
            YfFormField(
                value = draftName,
                onValueChange = {
                    draftName = it.take(ServerRoute.MAX_NAME_CHARS)
                    error = null
                },
                label = "名称，例如 内网 / 家里",
            )
            Spacer(Modifier.height(8.dp))
            YfFormField(
                value = draftUrl,
                onValueChange = {
                    draftUrl = it.trim()
                    error = null
                },
                label = "地址，例如 http://192.168.1.10:8096",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), style = AppTypography.caption.medium, color = palette.error)
            }
            val endpoint = validateEmbyServerEndpoint(draftUrl)
            if (draftUrl.isNotBlank() && endpoint.message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    endpoint.message,
                    style = AppTypography.caption.medium,
                    color = palette.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            OverlayButton(
                label = "添加线路",
                onClick = {
                    val url = endpoint.normalizedEndpoint?.takeIf { endpoint.allowed }
                    when {
                        url == null ->
                            error = endpoint.message
                                ?: "请填写以 http:// 或 https:// 开头的完整地址"
                        routes.any { it.url == url } -> error = "这个地址已经在列表里了"
                        else -> {
                            onSave(
                                routes +
                                    ServerRoute(
                                        id = ServerRoute.nextId(routes.map { it.id }),
                                        name = ServerRoute.sanitizeName(draftName, "备用线路"),
                                        url = url,
                                    ),
                                false,
                            )
                            draftName = ""
                            draftUrl = ""
                            error = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                tone = OverlayButtonTone.Primary,
                enabled = endpoint.allowed,
            )
        }
    }
}

/** One address: whether it is live, what it is called, where it points, and how fast. */
@Composable
private fun ServerRouteRow(
    route: ServerRoute,
    health: RouteHealth?,
    isActive: Boolean,
    isPrimary: Boolean,
    onActivate: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val dotColor =
        when (health?.status) {
            ServerHealthStatus.Healthy -> Semantic.Success
            ServerHealthStatus.Degraded -> Semantic.Warning
            ServerHealthStatus.AuthRequired -> Semantic.Error
            null, ServerHealthStatus.Unknown -> Semantic.Offline
            else -> Semantic.Offline
        }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                haptic = HapticSignal.Select,
                role = Role.RadioButton,
                onClickLabel = "切换到${route.name}",
                onClick = onActivate,
            ).semantics { selected = isActive }
            .heightIn(min = MinTouchTarget)
            .liquidGlass(
                shape = GlassShapes.chip,
                fill = if (isActive) accent.container else palette.card2,
                border = if (isActive) accent.border else palette.border,
            ).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    route.name,
                    style = if (isActive) AppTypography.body.strong else AppTypography.body.medium,
                    color = if (isActive) accent.accent else palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    routeLatencyLabel(health),
                    style = AppTypography.caption.medium,
                    color =
                        if (health?.latencyMs != null) {
                            latencySeverityColor(health.latencySeverity)
                        } else {
                            palette.sub2
                        },
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                route.url,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The primary is the identity; removing it would leave a server with no address the
        // session was ever established against.
        if (!isPrimary) {
            Icon(
                AppIcons.Close,
                contentDescription = "删除${route.name}",
                tint = palette.sub2,
                modifier =
                    Modifier
                        .pressable(onClickLabel = "删除${route.name}", onClick = onRemove)
                        .touchTarget()
                        .size(24.dp)
                        .liquidGlass(CircleShape, palette.card, palette.border)
                        .padding(7.dp),
            )
        }
    }
}

private fun routeStatusLabel(health: RouteHealth?): String =
    when (health?.status) {
        ServerHealthStatus.Healthy -> "在线"
        ServerHealthStatus.Degraded -> "服务异常"
        ServerHealthStatus.Offline -> "无法连接"
        ServerHealthStatus.AuthRequired -> "需重新登录"
        else -> "未检查"
    }

private fun routeLatencyLabel(health: RouteHealth?): String =
    health?.latencyMs?.let { "${health.latencySeverity.label} · $it ms" }
        ?: routeStatusLabel(health)

@Composable
private fun ServerFilterDialog(
    servers: List<SavedServer>,
    filter: ServerListFilter,
    onSort: (ServerSortOrder) -> Unit,
    onAccount: (String?) -> Unit,
    onLatency: (ServerLatencyFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts =
        remember(servers) {
            servers
                .map { it.userName }
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
        }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "排序与筛选",
            subtitle = "按在线、延迟、最近使用或账号整理服务器",
            onClose = onDismiss,
        )
        Text("排序", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            ServerSortOrder.entries.forEach { value ->
                OverlayOptionRow(
                    label = value.label,
                    selected = value == filter.sort,
                    onClick = { onSort(value) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("延迟体验", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            ServerLatencyFilter.entries.forEach { value ->
                OverlayOptionRow(
                    label = value.label,
                    selected = value == filter.latency,
                    onClick = { onLatency(value) },
                )
            }
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("账号", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                OverlayOptionRow(
                    label = "全部账号",
                    selected = filter.account == null,
                    onClick = { onAccount(null) },
                )
                accounts.forEach { account ->
                    OverlayOptionRow(
                        label = account,
                        selected = filter.account == account,
                        onClick = { onAccount(account) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerTransportDiagnosticsDialog(
    server: SavedServer,
    onProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val diagnostics = remember(server) { diagnoseServerTransport(server) }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "HTTPS 诊断",
            subtitle = server.serverName,
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            diagnostics.forEach { item ->
                val color =
                    when (item.severity) {
                        TransportDiagnosticSeverity.Secure -> Semantic.Success
                        TransportDiagnosticSeverity.LocalCleartext -> Semantic.Warning
                        TransportDiagnosticSeverity.Blocked -> Semantic.Error
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .flatGlass(GlassShapes.chip, palette.card2, palette.border)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${item.routeName} · ${item.summary}",
                            style = AppTypography.body.medium,
                            color = palette.text,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.address,
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OverlayButton(
            label = "同时测试连接与延迟",
            onClick = onProbe,
            modifier = Modifier.fillMaxWidth(),
            tone = OverlayButtonTone.Primary,
        )
    }
}

/**
 * 图标与颜色 — an emoji and a tint, which is as much identity as a server can be given
 * without asking it for artwork it does not have.
 *
 * The colour is applied to the badge and washed across the card, so the grid becomes
 * navigable by colour before it is read; the emoji is what makes one card recognisable at a
 * glance among a dozen whose names all start with the same two characters.
 */
@Composable
private fun ServerIconDialog(
    server: SavedServer,
    onSave: (String?, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    var emoji by remember(server.id) { mutableStateOf(server.iconEmoji.orEmpty()) }
    var tint by remember(server.id) { mutableStateOf(server.iconTint) }
    val preview = serverTintColor(server.id, tint)

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "图标与颜色", subtitle = server.serverName, onClose = onDismiss)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(AppShapes.thumb)
                    .background(
                        Brush.linearGradient(
                            listOf(lerp(preview, Color.White, 0.14f), preview),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    emoji.ifBlank { server.serverName.take(1).uppercase() },
                    style = AppTypography.section.strong,
                    color = if (emoji.isBlank()) Color.White else Color.Unspecified,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("预览", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(3.dp))
                Text(
                    "留空则继续用名称首字",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        YfFormField(
            value = emoji,
            onValueChange = { value ->
                // One glyph: the badge is 30dp on the card and a pasted sentence would
                // reflow the header row it sits in.
                emoji = value.trim().takeWhile { !it.isWhitespace() }.take(SERVER_ICON_EMOJI_MAX_CHARS)
            },
            label = "图标，粘贴一个表情",
        )

        Spacer(Modifier.height(14.dp))
        Text("颜色", style = AppTypography.caption.strong, color = palette.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerIconTints.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { swatch ->
                        val value = swatch.argbLong()
                        val selected = tint == value
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .pressable(
                                    haptic = HapticSignal.Select,
                                    role = Role.RadioButton,
                                    onClickLabel = "选择颜色",
                                    onClick = { tint = value },
                                ).semantics { this.selected = selected }
                                .clip(AppShapes.control)
                                .background(swatch)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = palette.text,
                                            shape = AppShapes.control,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "自动配色",
            style = AppTypography.caption.medium,
            color = if (tint == null) accent.accent else palette.sub2,
            modifier =
                Modifier
                    .pressable(onClickLabel = "恢复自动配色", onClick = { tint = null })
                    .touchTarget()
                    .padding(vertical = 4.dp),
        )

        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "保存",
            onDismiss = onDismiss,
            onConfirm = overlayAction { onSave(emoji.takeIf { it.isNotBlank() }, tint) },
        )
    }
}

/** Compose packs colour as a ULong; the registry stores plain 0xAARRGGBB. */
private fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
