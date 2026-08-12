package com.yfuse.feature.servers

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.formatWatchedAgo
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverBadgeColor
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.profile.AddServerDialog
import kotlinx.coroutines.delay

/** Two on a 360dp phone; wider windows fill with more cards rather than stretching them. */
private val ServerCardMinWidth = 148.dp

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
    val gridState = rememberLazyGridState()
    val share = rememberShareHandler()

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
    var refreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = refreshing)

    // The monitor has no completion signal to bind to, so the spinner is held for the
    // length of a probe round rather than pretending to know when the last one answered.
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        component.refreshHealth()
        delay(900)
        nowEpochMs = System.currentTimeMillis()
        refreshing = false
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(ServerCardMinWidth),
                state = gridState,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = Dimens.pageHorizontal,
                    end = Dimens.pageHorizontal,
                    top = Dimens.contentTop,
                    bottom = TabBarInset,
                ),
                // The cards carry their own shadow, so the air between them has to be
                // wider than the shadow or the grid reads as one slab of tiles.
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    ServersHeader(
                        serverCount = state.servers.size,
                        onlineCount = state.servers.count {
                            health[it.id]?.status == ServerHealthStatus.Healthy
                        },
                        currentName = state.servers
                            .firstOrNull { it.id == state.defaultServerId }
                            ?.serverName,
                        onAdd = { component.store.accept(ServersIntent.OpenAddDialog) },
                    )
                }

                if (state.servers.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyServers(
                            onAdd = { component.store.accept(ServersIntent.OpenAddDialog) },
                        )
                    }
                }

                items(state.servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        isCurrent = server.id == state.defaultServerId,
                        health = health[server.id],
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
            message = if (isCurrent) {
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

    if (state.dialogVisible) {
        AddServerDialog(
            state = state,
            onIntent = component.store::accept,
            onDismiss = { component.store.accept(ServersIntent.DismissDialog) },
        )
    }
}

@Composable
private fun ServersHeader(
    serverCount: Int,
    onlineCount: Int,
    currentName: String?,
    onAdd: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("服务器", style = AppTypography.display.strong, color = palette.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "管理连接，并选择库、搜索和播放使用的服务器",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                )
            }
            Row(
                Modifier
                    .pressable(onClickLabel = "添加服务器", onClick = onAdd)
                    .touchTarget()
                    .shadow(GlassLift.control, GlassShapes.chip)
                    .liquidGlass(
                        shape = GlassShapes.chip,
                        fill = accent.container,
                        border = accent.border.copy(alpha = 0.42f),
                        sheen = 0.75f,
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Add, null, tint = accent.accent, modifier = Modifier.size(13.dp))
                Text("添加", style = AppTypography.body.strong, color = accent.accent)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape = GlassShapes.card,
                    fill = palette.card,
                    border = palette.border,
                    sheen = 0.55f,
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerSummaryValue(
                value = serverCount.toString(),
                label = "已连接",
                color = accent.accent,
            )
            Box(Modifier.size(1.dp, 28.dp).background(palette.border))
            ServerSummaryValue(
                value = onlineCount.toString(),
                label = "在线",
                color = Semantic.Success,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("当前服务器", style = AppTypography.caption.regular, color = palette.sub2)
                Text(
                    currentName ?: if (serverCount == 0) "等待添加" else "未选择",
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServerSummaryValue(value: String, label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Column {
            Text(value, style = AppTypography.section.strong, color = LocalPalette.current.text)
            Text(label, style = AppTypography.caption.regular, color = LocalPalette.current.sub2)
        }
    }
}

/**
 * One server. Name, reachability, and 上次观看 — the three things that decide whether this
 * is the machine to open right now.
 */
@Composable
private fun ServerCard(
    server: SavedServer,
    isCurrent: Boolean,
    health: ServerHealth?,
    lastWatchedLabel: String,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val status = health?.status ?: ServerHealthStatus.Unknown
    val statusColor = serverStatusColor(status)
    val badgeColor = serverBadgeColor(server.id)
    val surfaceModifier = if (isCurrent) {
        Modifier
            .shadow(Shadows.primaryButton(accent.accent.copy(alpha = 0.70f)), GlassShapes.card)
            .glass(
                shape = GlassShapes.card,
                fill = Brush.linearGradient(
                    0f to lerp(accent.container, Color.White, if (palette.isDark) 0.04f else 0.18f),
                    0.48f to lerp(accent.container, palette.card, 0.54f),
                    1f to palette.card,
                ),
                border = accent.border.copy(alpha = 0.72f),
            )
            .border(1.dp, accent.border.copy(alpha = 0.40f), GlassShapes.card)
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
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 158.dp)
            .semantics { selected = isCurrent }
            .pressable(
                onClickLabel = if (isCurrent) "打开媒体库" else "切换到${server.serverName}",
                onLongClick = onMore,
                onLongClickLabel = "服务器操作",
                onClick = onClick,
            )
            .then(surfaceModifier)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrent) {
                        Brush.horizontalGradient(listOf(accent.accent, badgeColor))
                    } else {
                        Brush.horizontalGradient(
                            listOf(palette.border.copy(alpha = 0.22f), Color.Transparent),
                        )
                    },
                ),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .shadow(GlassLift.control, AppShapes.thumb)
                    .clip(AppShapes.thumb)
                    .background(
                        Brush.linearGradient(
                            listOf(lerp(badgeColor, Color.White, 0.14f), badgeColor),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    server.serverName.take(1).uppercase(),
                    style = AppTypography.section.strong,
                    color = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            if (isCurrent) {
                Row(
                    Modifier
                        .liquidGlass(
                            GlassShapes.chip,
                            accent.container,
                            accent.border.copy(alpha = 0.46f),
                            sheen = 0.65f,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Check, null, tint = accent.accent, modifier = Modifier.size(11.dp))
                    Text("当前", style = AppTypography.caption.strong, color = accent.accent)
                }
            }
            Icon(
                AppIcons.More,
                contentDescription = "服务器操作",
                tint = palette.sub2,
                modifier = Modifier
                    .pressable(onClickLabel = "服务器操作", onClick = onMore)
                    .touchTarget()
                    .padding(12.dp)
                    .size(13.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            server.serverName,
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            server.userName.ifBlank { "未记录账号" },
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .glass(
                    shape = GlassShapes.chip,
                    fill = statusColor.copy(alpha = if (palette.isDark) 0.12f else 0.08f),
                    border = statusColor.copy(alpha = 0.20f),
                )
                .padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Text(
                latencyLabel(health),
                style = AppTypography.caption.medium,
                color = if (status == ServerHealthStatus.Unknown) palette.sub2 else statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            lastWatchedLabel,
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = if (isCurrent) accent.accent else palette.sub2,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun serverStatusColor(status: ServerHealthStatus): Color = when (status) {
    ServerHealthStatus.Healthy -> Semantic.Success
    ServerHealthStatus.Degraded -> Semantic.Warning
    ServerHealthStatus.AuthRequired -> Semantic.Error
    else -> Semantic.Offline
}

/**
 * The number when there is one, and the reason when there is not.
 *
 * [ServerHealth.summary] already says both, but it leads with 在线 — the word the coloured
 * dot beside it has just said — and buries the milliseconds behind a separator.
 */
internal fun latencyLabel(health: ServerHealth?): String = when (health?.status) {
    ServerHealthStatus.Healthy -> health.latencyMs?.let { "延迟 $it ms" } ?: "在线"
    ServerHealthStatus.Degraded -> health.message ?: "连接不稳定"
    ServerHealthStatus.Offline -> "无法连接"
    ServerHealthStatus.AuthRequired -> "需要重新登录"
    else -> "正在检查"
}

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
            modifier = Modifier
                .pressable(onClick = onAdd)
                .heightIn(min = MinTouchTarget)
                .glass(GlassShapes.chip, accent.accent, accent.border)
                .padding(horizontal = 22.dp, vertical = 13.dp),
        )
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
                    .background(serverBadgeColor(server.id)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    server.serverName.take(1).uppercase(),
                    style = AppTypography.section.strong,
                    color = Color.White,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        Text(
                            "当前",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier = Modifier
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
                latencyLabel(health),
                style = AppTypography.caption.medium,
                color = palette.sub,
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
            onClick = onOpenLibrary,
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
            description = if (isCurrent) {
                "库、搜索和播放都在读取这一台"
            } else {
                "库、搜索和播放都会切到这一台"
            },
            enabled = !isCurrent,
            onClick = onSetDefault,
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Edit,
            label = "编辑连接与名称",
            description = "改名不用重新登录，改地址或账号要",
            onClick = onEdit,
        )
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Close,
            label = "移除服务器",
            description = "已下载的离线内容会保留",
            destructive = true,
            onClick = onRemove,
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
    val ink = when {
        destructive -> palette.error
        !enabled -> palette.sub2
        else -> palette.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .glass(
                shape = GlassShapes.chip,
                // The destructive fill stays inside the row's own rounded shape instead of
                // bleeding to the panel's edges the way the old option row's band did.
                fill = when {
                    destructive -> palette.errorContainer
                    prominent -> accent.container
                    else -> palette.card2
                },
                border = when {
                    destructive -> palette.error.copy(alpha = 0.34f)
                    prominent -> accent.border.copy(alpha = 0.38f)
                    else -> palette.border
                },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
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
                tint = when {
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
