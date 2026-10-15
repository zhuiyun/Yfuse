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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.LatencySeverity
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverTintColor
import com.yfuse.core.model.SavedServer
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

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
internal fun ServerActionsDialog(
    server: SavedServer,
    isCurrent: Boolean,
    /** False for a child profile: what changes the server is left out, and the note says why. */
    canManage: Boolean,
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
                                    .glass(AppShapes.chip, accent.container, accent.border)
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
                .glass(AppShapes.chip, palette.card2, palette.border)
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
        if (canManage) {
            Spacer(Modifier.height(8.dp))
            ServerActionRow(
                icon = AppIcons.Cast,
                label = "线路",
                description = routesSummary(server, health),
                onClick = overlayAction(onRoutes),
            )
        }
        Spacer(Modifier.height(8.dp))
        ServerActionRow(
            icon = AppIcons.Lock,
            label = "HTTPS 诊断",
            description = "检查主线路与备用地址的传输安全",
            onClick = overlayAction(onDiagnostics),
        )
        if (canManage) {
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
        } else {
            Spacer(Modifier.height(12.dp))
            Text(CHILD_PROFILE_NOTE, style = AppTypography.caption.regular, color = palette.sub2)
        }
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
                shape = AppShapes.chip,
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
                .clip(AppShapes.thumb)
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
