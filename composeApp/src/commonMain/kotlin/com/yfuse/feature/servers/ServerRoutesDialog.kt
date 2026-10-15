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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.RouteHealth
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalToastBottomInset
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.SwipeActionsRow
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberUndoWindow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.network.validateEmbyServerEndpoint
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

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
internal fun ServerRoutesDialog(
    server: SavedServer,
    health: ServerHealth?,
    onActivate: (String) -> Unit,
    onSave: (List<ServerRoute>, Boolean) -> Unit,
    onProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val latestServer by rememberUpdatedState(server)
    val latestSave by rememberUpdatedState(onSave)
    // 删除线路, 先做，给 5 秒撤销 (see [UndoWindow]): the route leaves the list at once and the server
    // only when its toast has gone, and it is taken out of whatever the routes are by then.
    val removals =
        rememberUndoWindow<ServerRoute>(server.id) { route ->
            latestSave(latestServer.effectiveRoutes.filterNot { it.id == route.id }, false)
        }
    var removing by remember(server.id) { mutableStateOf<ServerRoute?>(null) }
    // A fresh toast for every removal: two routes can share a name.
    var removalToast by remember(server.id) { mutableIntStateOf(0) }

    fun removeRoute(route: ServerRoute) {
        removals.hold(route)
        removing = route
        removalToast++
    }

    fun undoRemoval(route: ServerRoute) {
        if (removals.undo { it.id == route.id } != null) removing = null
    }

    // The toast left — timed out, swiped away, the app sent to the background: the route goes now.
    // Closing the sheet is the toast leaving too, and the window settles itself then.
    fun settleRemoval() {
        removals.settle()
        removing = null
    }
    val routes = server.effectiveRoutes.filterNot { it.id == removing?.id }
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
                key(route.id) {
                    val isPrimary = route.id == ServerRoute.PRIMARY_ID
                    // The primary cannot be removed, so it has nothing to swipe to.
                    SwipeActionsRow(
                        trailing =
                            if (isPrimary) {
                                null
                            } else {
                                ItemAction(
                                    label = "删除",
                                    icon = AppIcons.Close,
                                    destructive = true,
                                    undoable = true,
                                    id = "route.remove",
                                ) { removeRoute(route) }
                            },
                        shape = AppShapes.chip,
                    ) { actions ->
                        ServerRouteRow(
                            route = route,
                            health = health?.route(route.id),
                            isActive = route.id == server.activeRoute.id,
                            isPrimary = isPrimary,
                            onActivate = { onActivate(route.id) },
                            onRemove = { removeRoute(route) },
                            modifier = actions,
                        )
                    }
                }
            }
        }
        // Inside the sheet, under the rows: the sheet is its own window, over anything on the page.
        Box(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalToastBottomInset provides 0.dp) {
                key(removalToast) {
                    val pending = removing
                    ActionToast(
                        message = pending?.let { "已删除线路「${it.name}」" },
                        onDismiss = ::settleRemoval,
                        action = pending?.let { route -> ToastAction("撤销") { undoRemoval(route) } },
                    )
                }
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
                            // Something new sends a waiting removal on its way: [routes] already
                            // leaves that route out, so this one save carries both changes.
                            if (removals.release() != null) removing = null
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
    /** Applied first, on the node that carries the row's click: its swipe's custom actions go here. */
    modifier: Modifier = Modifier,
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
        modifier
            .fillMaxWidth()
            .pressable(
                haptic = HapticSignal.Select,
                role = Role.RadioButton,
                onClickLabel = "切换到${route.name}",
                onClick = onActivate,
            ).semantics { selected = isActive }
            .heightIn(min = MinTouchTarget)
            .liquidGlass(
                shape = AppShapes.chip,
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
