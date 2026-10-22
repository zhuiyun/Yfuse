package com.yfuse.feature.servers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassButtonEmphasis
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.SettingIconTile
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.resolveGlassButtonVisuals
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
    val sheetShape = RoundedCornerShape(28.dp)
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
                .semantics { paneTitle = "${server.serverName}服务器操作" },
        scrollable = false,
        liquidButtons = false,
        contentPadding = 0.dp,
        alignment = Alignment.BottomCenter,
        windowPadding = PaddingValues(start = 12.dp, top = 24.dp, end = 12.dp, bottom = 12.dp),
        shape = sheetShape,
        maxWidth = 480.dp,
        animation = DialogAnimation.Slide,
    ) {
        val sheetPalette = LocalPalette.current
        val accent = LocalAccentColors.current
        val primaryVisuals = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, sheetPalette, accent)
        // Keep the primary action reachable even in landscape or with enlarged system text.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ServerActionsHeader(server, isCurrent, health, onDismiss)

            if (canManage) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .selectableGroup()
                        .flatGlass(AppShapes.pill, sheetPalette.card2, sheetPalette.text.copy(alpha = 0.06f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ServerActionsTab("连接", selected = !managing, modifier = Modifier.weight(1f)) { managing = false }
                    ServerActionsTab("管理", selected = managing, modifier = Modifier.weight(1f)) { managing = true }
                }
                Spacer(Modifier.height(8.dp))
            }

            key(managing) {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp),
                ) {
                    Column {
                        ServerActionsGroup {
                            if (managing && canManage) {
                                ServerActionRow(
                                    AppIcons.Edit,
                                    "编辑服务器",
                                    "名称、地址与登录信息",
                                    SettingTint.general,
                                    onClick = overlayAction(onEdit),
                                )
                                ServerActionsDivider()
                                ServerActionRow(
                                    AppIcons.Grid,
                                    "图标与颜色",
                                    "为服务器设置专属标识",
                                    SettingTint.appearance,
                                    onClick = overlayAction(onIcon),
                                )
                                ServerActionsDivider()
                                ServerActionRow(
                                    AppIcons.Server,
                                    "服务器管理",
                                    "媒体库扫描与服务器任务",
                                    SettingTint.servers,
                                    onClick = overlayAction(onManage),
                                )
                                if (!isCurrent) {
                                    ServerActionsDivider()
                                    ServerActionRow(
                                        AppIcons.Check,
                                        "设为当前服务器",
                                        "将此服务器设为当前来源",
                                        SettingTint.sync,
                                        onClick = overlayAction(onSetDefault),
                                    )
                                }
                            } else {
                                ServerActionRow(
                                    AppIcons.Refresh,
                                    "检测连接",
                                    "重新检查可用性与响应速度",
                                    SettingTint.sync,
                                    onClick = onRefresh,
                                    navigates = false,
                                )
                                if (canManage) {
                                    ServerActionsDivider()
                                    ServerActionRow(
                                        AppIcons.PlaybackSource,
                                        "切换线路",
                                        "选择更适合当前网络的地址",
                                        SettingTint.servers,
                                        onClick = overlayAction(onRoutes),
                                    )
                                }
                                ServerActionsDivider()
                                ServerActionRow(
                                    icon = if (copied) AppIcons.Check else AppIcons.Cloud,
                                    label = if (copied) "地址已复制" else "复制地址",
                                    description = if (copied) "可以粘贴到其他应用" else "复制此服务器的连接地址",
                                    tint = if (copied) SettingTint.sync else SettingTint.general,
                                    navigates = false,
                                    onClick = {
                                        onCopyAddress()
                                        copied = true
                                    },
                                )
                                ServerActionsDivider()
                                ServerActionRow(
                                    AppIcons.Lock,
                                    "HTTPS 诊断",
                                    "检查证书与安全连接",
                                    SettingTint.advanced,
                                    onClick = overlayAction(onDiagnostics),
                                )
                                // A child may choose its current server, but cannot edit or remove one.
                                if (!canManage && !isCurrent) {
                                    ServerActionsDivider()
                                    ServerActionRow(
                                        AppIcons.Check,
                                        "设为当前服务器",
                                        "将此服务器设为当前来源",
                                        SettingTint.sync,
                                        onClick = overlayAction(onSetDefault),
                                    )
                                }
                            }
                        }
                        if (managing && canManage) {
                            Spacer(Modifier.height(4.dp))
                            ServerActionsDivider()
                            ServerActionsGroup {
                                ServerActionRow(
                                    AppIcons.Remove,
                                    "移除服务器",
                                    "从已保存的服务器列表中移除",
                                    sheetPalette.error,
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
        }

        Row(
            modifier =
                Modifier
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .pressable(
                        role = Role.Button,
                        focusShape = AppShapes.pill,
                        onClick = overlayAction(onOpenLibrary),
                    ).liquidGlass(
                        shape = AppShapes.pill,
                        fill = primaryVisuals.fill,
                        border = primaryVisuals.content.copy(alpha = 0.28f),
                        over = sheetPalette.background,
                        sheen = primaryVisuals.sheen,
                    ).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Movie, null, tint = primaryVisuals.content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (isCurrent) "打开媒体库" else "切换到此服务器",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = AppTypography.body.medium,
                color = primaryVisuals.content,
            )
            Spacer(Modifier.width(8.dp))
            Icon(AppIcons.ChevronRight, null, tint = primaryVisuals.content, modifier = Modifier.size(16.dp))
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
    val close = overlayDismiss(onDismiss)
    val statusTint =
        when (health?.status) {
            ServerHealthStatus.Healthy -> palette.success
            ServerHealthStatus.Degraded -> palette.warning
            ServerHealthStatus.Offline, ServerHealthStatus.AuthRequired -> palette.error
            else -> palette.sub2
        }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val serverTint = serverTintColor(server.id, server.iconTint)
        if (server.iconEmoji == null) {
            SettingIconTile(AppIcons.Server, serverTint, size = 44.dp)
        } else {
            Box(
                Modifier.size(44.dp).background(serverTint.copy(alpha = 0.18f), AppShapes.thumb),
                contentAlignment = Alignment.Center,
            ) {
                Text(server.iconEmoji.orEmpty(), style = AppTypography.section.strong, color = Color.Unspecified)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                server.serverName,
                style = AppTypography.section.medium,
                color = palette.text,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${server.kind.name} · ${server.baseUrl.removePrefix("https://").removePrefix("http://")}",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            Row(
                Modifier.fillMaxWidth().liveStatus(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (health?.status) {
                        ServerHealthStatus.Healthy -> AppIcons.Check
                        ServerHealthStatus.AuthRequired -> AppIcons.Lock
                        ServerHealthStatus.Degraded, ServerHealthStatus.Offline -> AppIcons.Info
                        else -> AppIcons.Refresh
                    },
                    null,
                    tint = statusTint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    buildString {
                        append(connectionLabel(health))
                        health?.latencyMs?.let { append(" · $it ms") }
                        if (isCurrent) append(" · 当前服务器")
                    },
                    style = AppTypography.caption.regular,
                    color = statusTint,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .pressable(role = Role.Button, focusShape = CircleShape, onClickLabel = "关闭菜单", onClick = close),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(32.dp).flatGlass(CircleShape, palette.card2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Close, "关闭菜单", tint = palette.sub2, modifier = Modifier.size(18.dp))
            }
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
    val accent = LocalAccentColors.current
    Box(
        modifier
            .pressable(role = Role.Tab, focusShape = AppShapes.pill, onClick = onClick)
            .semantics { this.selected = selected }
            .then(
                if (selected) {
                    Modifier.flatGlass(AppShapes.pill, accent.accent.copy(alpha = 0.08f))
                } else {
                    Modifier
                },
            ).defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTypography.body.medium,
            color = if (selected) accent.accent else palette.sub2,
        )
    }
}

@Composable
private fun ServerActionsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.control),
        content = content,
    )
}

@Composable
private fun ServerActionsDivider() {
    Box(
        Modifier
            .padding(start = 52.dp, end = 10.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPalette.current.text.copy(alpha = 0.07f)),
    )
}

@Composable
private fun ServerActionRow(
    icon: ImageVector,
    label: String,
    description: String,
    tint: Color,
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
            .defaultMinSize(minHeight = if (destructive) 48.dp else 64.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (destructive) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            }
        } else {
            SettingIconTile(icon, tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = AppTypography.body.medium, color = ink)
            if (!destructive) {
                Text(description, style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
        if (navigates &&
            !destructive
        ) {
            Icon(AppIcons.ChevronRight, null, tint = palette.sub2, modifier = Modifier.size(16.dp))
        }
    }
}
