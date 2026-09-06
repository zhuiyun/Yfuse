package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.resolveAccentColors
import com.yfuse.core.designsystem.touchTarget

private val DetailMoreHeroHeight = 136.dp
private val DetailMoreTeal = Color(0xFF147E79)
private val DetailMoreCoral = Color(0xFFC96662)
private val DetailMoreAmber = Color(0xFFC4872E)
private val DetailMorePlum = Color(0xFF76527E)
private val DetailMoreEmerald = Color(0xFF238963)
private val DetailMoreLavender = Color(0xFF9582B3)

private data class DetailQuickAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit,
)

/**
 * The detail overflow is a decision sheet rather than a flat command list. The title artwork
 * provides its atmosphere; semantic role colours keep the actions distinct when that artwork
 * is quiet or nearly monochrome.
 */
@Composable
internal fun DetailMoreActionsDialog(
    title: String,
    artworkUrls: List<String?>,
    isSeries: Boolean,
    followed: Boolean,
    played: Boolean,
    isPlex: Boolean,
    watchAvailable: Boolean,
    watchActive: Boolean,
    onDownload: () -> Unit,
    onCalendar: () -> Unit,
    onToggleFollow: () -> Unit,
    onTogglePlayed: () -> Unit,
    onOrganization: () -> Unit,
    onRefresh: () -> Unit,
    onAnalyze: () -> Unit,
    onWatchTogether: () -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(artworkUrls) {
        artworkUrls.filterNotNull().filter(String::isNotBlank).distinct()
    }
    val quickActions =
        buildList {
            add(
                DetailQuickAction(
                    icon = AppIcons.Download,
                    label = "下载到本地",
                    color = DetailMoreTeal,
                    onClick = onDownload,
                ),
            )
            if (isSeries) {
                add(
                    DetailQuickAction(
                        icon = AppIcons.WatchCalendar,
                        label = "播出日历",
                        color = DetailMoreCoral,
                        onClick = onCalendar,
                    ),
                )
            }
            add(
                DetailQuickAction(
                    icon = AppIcons.Check,
                    label = if (played) "标记未看" else "标记已看",
                    color = DetailMoreEmerald,
                    onClick = onTogglePlayed,
                ),
            )
        }

    GlassDialog(
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(0.74f),
        scrollable = false,
        liquidButtons = false,
        contentPadding = 0.dp,
        alignment = Alignment.BottomCenter,
        windowPadding = PaddingValues(start = 12.dp, top = 72.dp, end = 12.dp, bottom = 0.dp),
        shape = GlassShapes.sheet,
    ) {
        val palette = LocalPalette.current
        val lavender = resolveAccentColors(DetailMoreLavender, palette.isDark)
        Column(
            Modifier
                .fillMaxSize()
                .clip(GlassShapes.sheet)
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.04f),
                        1f to lavender.container.copy(alpha = 0.08f),
                    ),
                ),
        ) {
            DetailMoreHero(
                title = title,
                artworkUrls = candidates,
                onDismiss = onDismiss,
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailMoreSectionLabel(label = "观看", color = DetailMoreTeal)
                if (watchAvailable) {
                    DetailWatchTogetherAction(
                        active = watchActive,
                        onClick = onWatchTogether,
                    )
                }
                DetailQuickActionStrip(actions = quickActions)

                Spacer(Modifier.height(2.dp))
                DetailMoreSectionLabel(label = "管理", color = DetailMorePlum)
                DetailManagementActions(
                    isSeries = isSeries,
                    followed = followed,
                    isPlex = isPlex,
                    onToggleFollow = onToggleFollow,
                    onOrganization = onOrganization,
                    onRefresh = onRefresh,
                    onAnalyze = onAnalyze,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DetailMoreHero(
    title: String,
    artworkUrls: List<String>,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DetailMoreHeroHeight)
            .background(
                Brush.linearGradient(
                    listOf(DetailMorePlum, DetailMoreTeal, DetailMoreCoral),
                ),
            ),
    ) {
        if (artworkUrls.isNotEmpty()) {
            FallbackImage(
                urls = artworkUrls,
                contentDescription = "$title 背景图",
                modifier =
                    Modifier
                        .matchParentSize()
                        .blur(10.dp)
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                        },
                progressive = false,
                alphaOnly = true,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.46f to DetailMorePlum.copy(alpha = 0.2f),
                        1f to Color.Black.copy(alpha = 0.76f),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.72f)),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "关闭更多操作",
            tint = Color.White,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .pressable(onClick = overlayDismiss(onDismiss))
                    .touchTarget()
                    .size(36.dp)
                    .flatGlass(
                        CircleShape,
                        Color.Black.copy(alpha = 0.34f),
                        Color.White.copy(alpha = 0.24f),
                    ).padding(9.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 62.dp, bottom = 15.dp),
        ) {
            Text(
                title,
                style = AppTypography.display.strong,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "更多操作",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DetailMoreSectionLabel(
    label: String,
    color: Color,
) {
    val palette = LocalPalette.current
    val colors = resolveAccentColors(color, palette.isDark)
    Row(
        Modifier.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(15.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
        Text(
            label,
            style = AppTypography.caption.strong,
            color = palette.sub,
        )
    }
}

@Composable
private fun DetailWatchTogetherAction(
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val artwork = LocalAccentColors.current
    val plum = resolveAccentColors(DetailMorePlum, palette.isDark)
    val iconFill = lerp(plum.container, artwork.container, 0.46f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics {
                stateDescription = if (active) "一起看房间已创建" else "尚未创建一起看房间"
            }.flatGlass(
                GlassShapes.card,
                lerp(palette.card2, iconFill, 0.76f),
                lerp(plum.border, artwork.border, 0.4f).copy(alpha = 0.72f),
            ).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(iconFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.User,
                contentDescription = null,
                tint = plum.accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "一起看",
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (active) "房间已创建，继续分享邀请" else "创建房间并邀请朋友同步观看",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = plum.accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DetailQuickActionStrip(actions: List<DetailQuickAction>) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(82.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) DetailQuickActionDivider()
            DetailQuickActionItem(action)
        }
    }
}

@Composable
private fun RowScope.DetailQuickActionItem(action: DetailQuickAction) {
    val palette = LocalPalette.current
    val colors = resolveAccentColors(action.color, palette.isDark)
    Column(
        Modifier
            .weight(1f)
            .height(82.dp)
            .pressable(role = Role.Button, onClick = action.onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            action.label,
            style = AppTypography.caption.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailQuickActionDivider() {
    val palette = LocalPalette.current
    Box(Modifier.width(1.dp).height(38.dp).background(palette.border))
}

@Composable
private fun DetailManagementActions(
    isSeries: Boolean,
    followed: Boolean,
    isPlex: Boolean,
    onToggleFollow: () -> Unit,
    onOrganization: () -> Unit,
    onRefresh: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
    ) {
        var needsDivider = false

        if (isSeries) {
            DetailManagementRow(
                icon = AppIcons.Bell,
                label = if (followed) "已加入追剧" else "加入追剧",
                description = if (followed) "追剧中心优先显示并接收更新提醒" else "关注排期和新集入库",
                color = DetailMoreTeal,
                checked = followed,
                onClick = onToggleFollow,
            )
            needsDivider = true
        }

        if (needsDivider) DetailManagementDivider()
        DetailManagementRow(
            icon = AppIcons.Grid,
            label = "加入合集或播放列表",
            description = "选择服务器上已有的容器",
            color = DetailMoreAmber,
            onClick = onOrganization,
        )
        DetailManagementDivider()
        DetailManagementRow(
            icon = AppIcons.Refresh,
            label = "刷新服务器元数据",
            description = "保留已锁定字段与现有图片",
            color = DetailMoreCoral,
            onClick = onRefresh,
        )
        if (isPlex) {
            DetailManagementDivider()
            DetailManagementRow(
                icon = AppIcons.Cloud,
                label = "分析 Plex 媒体",
                description = "重新分析文件、音视频轨与章节",
                color = DetailMorePlum,
                onClick = onAnalyze,
            )
        }
    }
}

@Composable
private fun DetailManagementRow(
    icon: ImageVector,
    label: String,
    description: String,
    color: Color,
    onClick: () -> Unit,
    checked: Boolean? = null,
) {
    val palette = LocalPalette.current
    val colors = resolveAccentColors(color, palette.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(
                role = if (checked == null) Role.Button else Role.Switch,
                onClick = onClick,
            ).then(
                if (checked == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        stateDescription = if (checked) "已开启" else "已关闭"
                    }
                },
            ).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = AppTypography.body.medium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                description,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (checked == null) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.hint,
                modifier = Modifier.size(15.dp),
            )
        } else {
            DetailMoreToggle(checked = checked, activeColor = colors.accent)
        }
    }
}

@Composable
private fun DetailManagementDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 12.dp)
            .height(1.dp)
            .background(palette.border),
    )
}

@Composable
private fun DetailMoreToggle(
    checked: Boolean,
    activeColor: Color,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(if (checked) activeColor else palette.card3)
            .border(1.dp, if (checked) activeColor else palette.border, CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else palette.sub2),
        )
    }
}
