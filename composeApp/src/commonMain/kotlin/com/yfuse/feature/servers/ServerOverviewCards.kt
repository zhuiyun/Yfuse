@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.yfuse.feature.servers

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.LatencySeverity
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.ServerStats
import com.yfuse.core.data.formatServerCount
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.contentColorForSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.glassControlHighlight
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.refreshAction
import com.yfuse.core.designsystem.serverTintColor
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerLayout
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** 「3 条线路 · 2 条可用」, or the single address when there is nothing to choose between. */
internal fun routesSummary(
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
internal fun ServersHeader(
    /** Null for a profile that cannot add servers; the key is then left out. */
    onAdd: (() -> Unit)?,
    /** A refresh is under way, however it was started; the key waits for it. */
    refreshing: Boolean,
    /** The refresh under way came from the key, so the key carries its indicator. */
    refreshingFromKey: Boolean,
    onRefreshAll: () -> Unit,
    layout: ServerLayout,
    onLayout: (ServerLayout) -> Unit,
    filter: ServerListFilter,
    onFilter: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
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
                // A pull-to-refresh page: TalkBack's touch exploration never produces the pull.
                modifier = Modifier.weight(1f).refreshAction(enabled = !refreshing, onRefresh = onRefreshAll),
            )
            if (onAdd != null) {
                Row(
                    Modifier
                        .pressable(onClickLabel = "添加服务器", onClick = onAdd)
                        .touchTarget()
                        .shadow(GlassLift.control, AppShapes.chip)
                        .liquidGlass(
                            shape = AppShapes.chip,
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
                    .shadow(GlassLift.control, AppShapes.chip)
                    .liquidGlass(
                        shape = AppShapes.chip,
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
                        label = "切换布局",
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
                    ).semantics {
                        stateDescription = if (layout == ServerLayout.Grid) "当前网格" else "当前列表"
                    }.touchTarget()
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
            // The orb in the key while its refresh runs, then the key again. The spinning glyph,
            // the result glyph after it and the toast on top were three answers to one request;
            // a failure still gets the toast.
            Box(
                Modifier
                    .pressable(
                        enabled = !refreshing,
                        label = "刷新全部服务器",
                        onClick = onRefreshAll,
                    ).semantics { if (refreshing) stateDescription = "正在刷新" }
                    .touchTarget()
                    .shadow(GlassLift.control, CircleShape)
                    .liquidGlass(CircleShape, palette.card2, palette.border, sheen = 0.75f)
                    .size(ServerHeaderCircleSize),
                contentAlignment = Alignment.Center,
            ) {
                InlineLoadingContent(loading = refreshingFromKey, slotSize = 15.dp, color = accent.accent) {
                    Icon(
                        AppIcons.Refresh,
                        contentDescription = null,
                        tint = palette.sub2,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun refreshResultColor(result: ServerRefreshResult): Color =
    when (result) {
        ServerRefreshResult.Success -> Semantic.Success
        ServerRefreshResult.PartialFailure -> Semantic.Warning
        ServerRefreshResult.Failure -> Semantic.Error
        ServerRefreshResult.Cancelled, ServerRefreshResult.Empty -> LocalPalette.current.sub2
    }

@Composable
internal fun CurrentServerHero(
    server: SavedServer,
    health: ServerHealth?,
    checking: Boolean,
    stats: ServerStats?,
    serverCount: Int,
    onlineCount: Int,
    /** [onOpen] signs in again rather than opening 库; see [ServerCard]'s `signInOnClick`. */
    signInOnOpen: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val status = health?.status ?: ServerHealthStatus.Unknown
    val statusColor = serverStatusColor(status)
    val badgeColor = serverTintColor(server.id, server.iconTint)
    val latencyColor =
        latencySeverityColor(health?.latencySeverity ?: LatencySeverity.Unknown)
    val largeText = LocalDensity.current.fontScale >= SERVER_LARGE_TEXT_SCALE

    Column(
        modifier
            .fillMaxWidth()
            .semantics { selected = true }
            .pressable(
                onClickLabel = if (signInOnOpen) "重新登录${server.serverName}" else "打开${server.serverName}媒体库",
                onLongClick = onMore,
                onLongClickLabel = "打开${server.serverName}操作",
                onClick = onOpen,
            ).shadow(
                Shadows.primaryButton(accent.accent.copy(alpha = 0.42f)),
                AppShapes.card,
            ).liquidGlass(
                shape = AppShapes.card,
                fill = lerp(palette.card, accent.container, if (palette.isDark) 0.26f else 0.22f),
                border = glassControlHighlight(accent, palette.isDark),
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
                    color = if (server.iconEmoji == null) contentColorForSurface(badgeColor) else Color.Unspecified,
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
                    ServerConnectionMark(status, checking, statusColor, 7.dp)
                    Text(
                        connectionLabel(health),
                        style = AppTypography.body.medium,
                        color =
                            if (status == ServerHealthStatus.Unknown) {
                                palette.sub2
                            } else {
                                palette.statusText(statusColor)
                            },
                        maxLines = 1,
                    )
                }
            }
            Row(
                Modifier
                    .pressable(onClickLabel = "进入服务器", onClick = onOpen)
                    .heightIn(min = MinTouchTarget)
                    .liquidGlass(AppShapes.pill, accent.container, glassControlHighlight(accent, palette.isDark))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("进入", style = AppTypography.body.strong, color = accent.accent)
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        if (largeText) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroMetric(AppIcons.User, serverCount.toString(), "已连接", accent.accent, Modifier.fillMaxWidth())
                HeroMetric(AppIcons.Server, onlineCount.toString(), "在线", Semantic.Success, Modifier.fillMaxWidth())
                HeroMetric(
                    AppIcons.Refresh,
                    health?.latencyMs?.let { "$it ms" } ?: "未测速",
                    health?.latencySeverity?.label ?: "延迟",
                    latencyColor,
                    Modifier.fillMaxWidth(),
                )
            }
        } else {
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
                .glass(AppShapes.thumb, color.copy(alpha = 0.08f), color.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = AppTypography.body.strong,
                color = if (label == "延迟") palette.text else palette.statusText(color),
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
internal fun OtherServersHeader(count: Int) {
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
internal fun ServerCard(
    server: SavedServer,
    isCurrent: Boolean,
    health: ServerHealth?,
    checking: Boolean,
    stats: ServerStats?,
    lastWatchedLabel: String,
    /** The server wants a new sign-in, and [onClick] opens that form instead of 库. */
    signInOnClick: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val status = health?.status ?: ServerHealthStatus.Unknown
    val statusColor = serverStatusColor(status)
    val badgeColor = serverTintColor(server.id, server.iconTint)
    val surfaceModifier =
        if (isCurrent) {
            Modifier
                .shadow(Shadows.primaryButton(accent.accent.copy(alpha = 0.55f)), AppShapes.card)
                .liquidGlass(
                    shape = AppShapes.card,
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
                .shadow(GlassLift.control, AppShapes.card)
                .liquidGlass(
                    shape = AppShapes.card,
                    // One tinted colour, not a gradient: liquidGlass draws its own body ramp
                    // over whatever fill it is given, and it takes a Color.
                    fill = lerp(palette.card, badgeColor, if (palette.isDark) 0.20f else 0.16f),
                    border = lerp(palette.border, badgeColor, 0.28f),
                    sheen = 0.52f,
                )
        } else {
            Modifier
                .shadow(GlassLift.control, AppShapes.card)
                .liquidGlass(
                    shape = AppShapes.card,
                    fill = palette.card,
                    border = palette.border,
                    sheen = 0.52f,
                )
        }
    Box(
        modifier
            .fillMaxWidth()
            .semantics { selected = isCurrent }
            .pressable(
                onClickLabel =
                    when {
                        signInOnClick -> "重新登录${server.serverName}"
                        isCurrent -> "打开${server.serverName}媒体库"
                        else -> "切换到${server.serverName}"
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
                                listOf(badgeColor, badgeColor),
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
                        color = if (server.iconEmoji == null) contentColorForSurface(badgeColor) else Color.Unspecified,
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
                ServerConnectionMark(status, checking, statusColor, 6.dp)
                Text(
                    connectionLabel(health),
                    style = AppTypography.caption.medium,
                    color = if (status == ServerHealthStatus.Unknown) palette.sub2 else palette.statusText(statusColor),
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
                    color = palette.statusText(latencySeverityColor(latencySeverity)),
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
                .pressable(label = "打开${server.serverName}操作", onClick = onMore)
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
    if (LocalDensity.current.fontScale >= SERVER_LARGE_TEXT_SCALE) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerCount(AppIcons.Movie, "电影", stats?.movieCount, Modifier.fillMaxWidth())
            ServerCount(AppIcons.Series, "剧集", stats?.seriesCount, Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerCount(AppIcons.Movie, "电影", stats?.movieCount, Modifier.weight(1f))
            ServerCount(AppIcons.Series, "剧集", stats?.seriesCount, Modifier.weight(1f))
        }
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
internal fun EmptyServers(onAdd: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(AppShapes.card, palette.card2, palette.border)
            .padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Server, null, tint = palette.sub2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text("连接 Emby、Jellyfin 或 Plex 服务器", style = AppTypography.body.strong, color = palette.text)
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
                    .glass(AppShapes.chip, accent.accent, accent.border)
                    .padding(horizontal = 22.dp, vertical = 13.dp),
        )
    }
}
