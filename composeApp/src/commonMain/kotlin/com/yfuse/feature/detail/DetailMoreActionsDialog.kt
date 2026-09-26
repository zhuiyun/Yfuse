package com.yfuse.feature.detail

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DecorativeTints
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PillSwitch
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.resolveAccentColors
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.feature.personal.PersonalMediaLists
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private val DetailMoreHeroHeight = 136.dp

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
    /** Whether this server kind keeps favourites at all. */
    serverFavoriteAvailable: Boolean,
    serverFavorite: Boolean,
    serverWatchLater: Boolean,
    serverWatchLaterMutating: Boolean,
    /** The selected profile's own lists; null when the title has no server to key them by. */
    personalLists: PersonalMediaLists?,
    onToggleServerFavorite: () -> Unit,
    onToggleServerWatchLater: () -> Unit,
    onDownload: () -> Unit,
    onCalendar: () -> Unit,
    onToggleFollow: () -> Unit,
    onTogglePlayed: () -> Unit,
    onOrganization: () -> Unit,
    onRefresh: () -> Unit,
    onAnalyze: () -> Unit,
    onEditMetadata: () -> Unit,
    onWatchTogether: () -> Unit,
    onDismiss: () -> Unit,
    /** 分享 as a poster card; null where sharing is unavailable. */
    onShare: (() -> Unit)? = null,
) {
    val candidates =
        remember(artworkUrls) {
            artworkUrls.filterNotNull().filter(String::isNotBlank).distinct()
        }
    val quickActions =
        buildList {
            add(
                DetailQuickAction(
                    icon = AppIcons.Download,
                    label = "下载到本地",
                    color = DecorativeTints.teal,
                    onClick = onDownload,
                ),
            )
            if (isSeries) {
                add(
                    DetailQuickAction(
                        icon = AppIcons.WatchCalendar,
                        label = "播出日历",
                        color = DecorativeTints.coral,
                        onClick = onCalendar,
                    ),
                )
            }
            add(
                DetailQuickAction(
                    icon = AppIcons.Check,
                    label = if (played) "标记未看" else "标记已看",
                    color = DecorativeTints.emerald,
                    onClick = onTogglePlayed,
                ),
            )
            if (onShare != null) {
                add(
                    DetailQuickAction(
                        icon = AppIcons.Share,
                        label = "分享",
                        color = DecorativeTints.plum,
                        onClick = onShare,
                    ),
                )
            }
        }

    GlassDialog(
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(0.74f),
        scrollable = false,
        liquidButtons = false,
        contentPadding = 0.dp,
        alignment = Alignment.BottomCenter,
        windowPadding = PaddingValues(start = 12.dp, top = 72.dp, end = 12.dp, bottom = 0.dp),
        shape = AppShapes.sheet,
        // The artwork header carries the handle; the panel itself takes the drag.
        dragHandle = false,
        // Pinned to the bottom edge, so it rises from it whatever the chosen 弹窗动画.
        animation = DialogAnimation.Slide,
    ) {
        val palette = LocalPalette.current
        val lavender = resolveAccentColors(DecorativeTints.lavender, palette.isDark)
        Column(
            Modifier
                .fillMaxSize()
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
                DetailMoreSectionLabel(label = "观看", color = DecorativeTints.teal)
                if (watchAvailable) {
                    DetailWatchTogetherAction(
                        active = watchActive,
                        onClick = overlayAction(onWatchTogether),
                    )
                }
                DetailQuickActionStrip(actions = quickActions)

                Spacer(Modifier.height(2.dp))
                DetailMoreSectionLabel(label = "收藏", color = DecorativeTints.coral)
                DetailCollectionActions(
                    serverFavoriteAvailable = serverFavoriteAvailable,
                    serverFavorite = serverFavorite,
                    serverWatchLater = serverWatchLater,
                    serverWatchLaterMutating = serverWatchLaterMutating,
                    personalLists = personalLists,
                    onToggleServerFavorite = onToggleServerFavorite,
                    onToggleServerWatchLater = onToggleServerWatchLater,
                )

                Spacer(Modifier.height(2.dp))
                DetailMoreSectionLabel(label = "管理", color = DecorativeTints.plum)
                // Each of these closes the sheet, several to open another: the sheet leaves first, so
                // the next panel rises over the page instead of replacing this one in a single frame.
                DetailManagementActions(
                    isSeries = isSeries,
                    followed = followed,
                    isPlex = isPlex,
                    onToggleFollow = overlayAction(onToggleFollow),
                    onOrganization = overlayAction(onOrganization),
                    onRefresh = overlayAction(onRefresh),
                    onAnalyze = overlayAction(onAnalyze),
                    onEditMetadata = overlayAction(onEditMetadata),
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
                    listOf(DecorativeTints.plum, DecorativeTints.teal, DecorativeTints.coral),
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
                        0.46f to DecorativeTints.plum.copy(alpha = 0.2f),
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
    val plum = resolveAccentColors(DecorativeTints.plum, palette.isDark)
    val iconFill = lerp(plum.container, artwork.container, 0.46f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics {
                stateDescription = if (active) "一起看房间已创建" else "尚未创建一起看房间"
            }.flatGlass(
                AppShapes.card,
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
            .flatGlass(AppShapes.card, palette.card2, palette.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) DetailQuickActionDivider()
            DetailQuickActionItem(action)
        }
    }
}

/** Every quick action closes the sheet; 下载 and 播出日历 open the next panel once it has left. */
@Composable
private fun RowScope.DetailQuickActionItem(action: DetailQuickAction) {
    val palette = LocalPalette.current
    val colors = resolveAccentColors(action.color, palette.isDark)
    Column(
        Modifier
            .weight(1f)
            .height(82.dp)
            .pressable(role = Role.Button, onClick = overlayAction(action.onClick)),
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

/**
 * The four list switches that used to sit under the play key. They change state in place, so the
 * sheet stays open and each row shows its new state at once.
 */
@Composable
private fun DetailCollectionActions(
    serverFavoriteAvailable: Boolean,
    serverFavorite: Boolean,
    serverWatchLater: Boolean,
    serverWatchLaterMutating: Boolean,
    personalLists: PersonalMediaLists?,
    onToggleServerFavorite: () -> Unit,
    onToggleServerWatchLater: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(AppShapes.card, palette.card2, palette.border),
    ) {
        if (serverFavoriteAvailable) {
            DetailManagementRow(
                icon = if (serverFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = "服务器收藏",
                description = "保存在当前服务器的收藏中",
                color = DecorativeTints.coral,
                checked = serverFavorite,
                onClick = onToggleServerFavorite,
            )
            DetailManagementDivider()
        }
        DetailManagementRow(
            icon = if (serverWatchLater) AppIcons.Check else AppIcons.Bookmark,
            label = "服务器稍后看",
            description = if (serverWatchLaterMutating) "正在同步到服务器…" else "加入当前服务器的稍后观看",
            color = DecorativeTints.amber,
            checked = serverWatchLater,
            onClick = { if (!serverWatchLaterMutating) onToggleServerWatchLater() },
        )
        personalLists?.let { lists ->
            DetailManagementDivider()
            DetailManagementRow(
                icon = if (lists.favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = "收藏到个人清单",
                description = if (lists.allowed) "只属于当前资料，跨服务器同步" else "当前资料无权访问此服务器",
                color = DecorativeTints.coral,
                checked = lists.favorite,
                onClick = lists.toggleFavorite,
            )
            DetailManagementDivider()
            DetailManagementRow(
                icon = if (lists.wanted) AppIcons.Check else AppIcons.Bookmark,
                label = "加入个人想看",
                description = if (lists.allowed) "只属于当前资料，跨服务器同步" else "当前资料无权访问此服务器",
                color = DecorativeTints.teal,
                checked = lists.wanted,
                onClick = lists.toggleWanted,
            )
        }
    }
    personalLists?.error?.let { error ->
        Text(error, style = AppTypography.caption.regular, color = palette.error)
    }
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
    onEditMetadata: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(AppShapes.card, palette.card2, palette.border),
    ) {
        var needsDivider = false

        if (isSeries) {
            DetailManagementRow(
                icon = AppIcons.Bell,
                label = if (followed) "已加入追剧" else "加入追剧",
                description = if (followed) "追剧中心优先显示并接收更新提醒" else "关注排期和新集入库",
                color = DecorativeTints.teal,
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
            color = DecorativeTints.amber,
            onClick = onOrganization,
        )
        DetailManagementDivider()
        DetailManagementRow(
            icon = AppIcons.Edit,
            label = "编辑元数据与图片",
            description = "修改标题、简介或选择海报",
            color = DecorativeTints.plum,
            onClick = onEditMetadata,
        )
        DetailManagementDivider()
        DetailManagementRow(
            icon = AppIcons.Refresh,
            label = "刷新服务器元数据",
            description = "保留已锁定字段与现有图片",
            color = DecorativeTints.coral,
            onClick = onRefresh,
        )
        if (isPlex) {
            DetailManagementDivider()
            DetailManagementRow(
                icon = AppIcons.Cloud,
                label = "分析 Plex 媒体",
                description = "重新分析文件、音视频轨与章节",
                color = DecorativeTints.plum,
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
                // A switch answers the way every 设置 switch does.
                haptic = if (checked == null) null else HapticSignal.Select,
                onClick = onClick,
            ).then(
                if (checked == null) {
                    Modifier
                } else {
                    Modifier.semantics { toggleableState = ToggleableState(checked) }
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
            PillSwitch(checked = checked, activeColor = colors.accent)
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
