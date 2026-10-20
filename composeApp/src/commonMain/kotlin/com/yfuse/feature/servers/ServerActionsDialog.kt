package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverTintColor
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** Bottom sheet with separate connection and management pages and a fixed library action. */
@Composable
internal fun ServerActionsDialog(
    server: SavedServer,
    isCurrent: Boolean,
    canManage: Boolean,
    health: ServerHealth?,
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
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    var managing by rememberSaveable(server.id, canManage) { mutableStateOf(false) }
    var copied by remember(server.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    GlassDialog(
        onDismiss = onDismiss,
        modifier =
            Modifier
                .background(if (palette.isDark) palette.reducedFill.card2 else Color.White, sheetShape)
                .semantics { paneTitle = "${server.serverName}服务器操作" },
        scrollable = false,
        liquidButtons = false,
        contentPadding = 0.dp,
        alignment = Alignment.BottomCenter,
        windowPadding = PaddingValues(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 0.dp),
        shape = sheetShape,
        maxWidth = 480.dp,
        animation = DialogAnimation.Slide,
    ) {
        val sheetPalette = LocalPalette.current
        val accent = LocalAccentColors.current
        ServerActionsHeader(server, isCurrent, health, onDismiss)

        if (canManage) {
            Row(
                Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .selectableGroup()
                    .background(sheetPalette.reducedFill.card3, AppShapes.control)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ServerActionsTab("连接", selected = !managing, modifier = Modifier.weight(1f)) { managing = false }
                ServerActionsTab("管理", selected = managing, modifier = Modifier.weight(1f)) { managing = true }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Only the action list scrolls: short screens retain both the identity and the library key.
        key(managing) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                Column(Modifier.heightIn(min = if (canManage && !isCurrent) 276.dp else 224.dp)) {
                    ServerActionsGroup {
                        if (managing && canManage) {
                            ServerActionRow(AppIcons.Edit, "编辑服务器", onClick = overlayAction(onEdit))
                            ServerActionsDivider()
                            ServerActionRow(AppIcons.Grid, "图标与颜色", onClick = overlayAction(onIcon))
                            ServerActionsDivider()
                            ServerActionRow(AppIcons.Server, "服务器管理", onClick = overlayAction(onManage))
                            if (!isCurrent) {
                                ServerActionsDivider()
                                ServerActionRow(AppIcons.Check, "设为当前服务器", onClick = overlayAction(onSetDefault))
                            }
                        } else {
                            ServerActionRow(AppIcons.Refresh, "检测连接", onClick = onRefresh, navigates = false)
                            if (canManage) {
                                ServerActionsDivider()
                                ServerActionRow(AppIcons.Cast, "切换线路", onClick = overlayAction(onRoutes))
                            }
                            ServerActionsDivider()
                            ServerActionRow(
                                icon = if (copied) AppIcons.Check else AppIcons.Cloud,
                                label = if (copied) "地址已复制" else "复制地址",
                                navigates = false,
                                onClick = {
                                    onCopyAddress()
                                    copied = true
                                },
                            )
                            ServerActionsDivider()
                            ServerActionRow(AppIcons.Lock, "HTTPS 诊断", onClick = overlayAction(onDiagnostics))
                            // A child may choose its current server, but cannot edit or remove one.
                            if (!canManage && !isCurrent) {
                                ServerActionsDivider()
                                ServerActionRow(AppIcons.Check, "设为当前服务器", onClick = overlayAction(onSetDefault))
                            }
                        }
                    }
                    if (managing && canManage) {
                        Spacer(Modifier.height(12.dp))
                        ServerActionsGroup {
                            ServerActionRow(
                                AppIcons.Remove,
                                "移除服务器",
                                destructive = true,
                                onClick = overlayAction(onRemove),
                            )
                        }
                    }
                    if (!canManage) {
                        Text(
                            CHILD_PROFILE_NOTE,
                            style = AppTypography.caption.regular,
                            color = sheetPalette.sub2,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        Button(
            onClick = overlayAction(onOpenLibrary),
            modifier =
                Modifier
                    .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            shape = AppShapes.control,
            colors = ButtonDefaults.buttonColors(containerColor = accent.accent, contentColor = accent.onAccent),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                if (isCurrent) "打开媒体库" else "切换到此服务器",
                style = AppTypography.body.strong,
                color = accent.onAccent,
            )
            Spacer(Modifier.width(8.dp))
            Icon(AppIcons.ChevronRight, null, tint = accent.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ServerActionsHeader(
    server: SavedServer,
    isCurrent: Boolean,
    health: ServerHealth?,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val close = overlayDismiss(onDismiss)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(serverTintColor(server.id, server.iconTint), AppShapes.thumb),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                server.iconEmoji ?: server.serverName.take(1).uppercase(),
                style = AppTypography.section.strong,
                color = if (server.iconEmoji == null) Color.White else Color.Unspecified,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.serverName,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (isCurrent) {
                    Text(
                        "当前",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier =
                            Modifier
                                .background(
                                    accent.container,
                                    AppShapes.thumb,
                                ).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                "${server.kind.name} · ${server.baseUrl.removePrefix("https://").removePrefix("http://")}",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                buildString {
                    append(connectionLabel(health))
                    health?.latencyMs?.let { append(" · $it ms") }
                },
                style = AppTypography.caption.medium,
                color =
                    when (health?.status) {
                        ServerHealthStatus.Healthy -> palette.success
                        ServerHealthStatus.Degraded -> palette.warning
                        ServerHealthStatus.Offline, ServerHealthStatus.AuthRequired -> palette.error
                        else -> palette.sub2
                    },
                modifier = Modifier.padding(top = 5.dp).liveStatus(),
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .pressable(role = Role.Button, focusShape = CircleShape, onClickLabel = "关闭菜单", onClick = close)
                .background(palette.reducedFill.card3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Close, "关闭菜单", tint = palette.sub2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ServerActionsTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .pressable(role = Role.Tab, focusShape = AppShapes.thumb, onClick = onClick)
            .semantics { this.selected = selected }
            .background(if (selected) palette.text else Color.Transparent, AppShapes.thumb)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTypography.body.strong,
            color = if (selected) palette.background else palette.sub2,
        )
    }
}

@Composable
private fun ServerActionsGroup(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.control)
            .background(palette.reducedFill.card3)
            .border(1.dp, palette.sub2.copy(alpha = 0.45f), AppShapes.control),
        content = content,
    )
}

@Composable
private fun ServerActionsDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.text.copy(alpha = 0.1f)))
}

@Composable
private fun ServerActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    navigates: Boolean = true,
) {
    val palette = LocalPalette.current
    val ink = if (destructive) palette.error else palette.text
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, focusShape = AppShapes.control, onClick = onClick)
            .background(if (destructive) palette.errorContainer else Color.Transparent)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = AppTypography.body.medium, color = ink, modifier = Modifier.weight(1f))
        if (navigates) Icon(AppIcons.ChevronRight, null, tint = ink, modifier = Modifier.size(16.dp))
    }
}
