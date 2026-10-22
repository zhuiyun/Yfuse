package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.CalendarTrackingOrigin
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DisclosureContent
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.UndoWindow
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.rememberUndoWindow
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.network.TmdbImages
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.flatGlass as glass

/**
 * 取消追剧 used to be one tap with no way back, unlike 取消全部's confirm step. Its undo, rather
 * than a second confirmation, is 先做，给 5 秒撤销 (see [UndoWindow]): the row leaves at once, and
 * the follow — reminders and all — only once the toast has gone. It used to be a row of its own
 * drawn into the list, re-following when 撤销 was pressed; the toast is the app's one undo now.
 */
@Composable
internal fun CalendarTrackingPane(
    followedSeries: List<FollowedSeries>,
    calendarDays: List<CalendarDay>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
) {
    val unfollows = rememberUndoWindow<FollowedSeries> { component.unfollow(it.tmdbId) }
    var unfollowing by remember { mutableStateOf<FollowedSeries?>(null) }
    // A fresh toast for every 取消追剧, so two in a row each get their own.
    var unfollowToast by remember { mutableIntStateOf(0) }

    fun unfollow(series: FollowedSeries) {
        unfollows.hold(series)
        unfollowing = series
        unfollowToast++
    }

    fun undoUnfollow(series: FollowedSeries) {
        if (unfollows.undo { it.tmdbId == series.tmdbId } != null) unfollowing = null
    }

    // The toast left — timed out, swiped away, the app sent to the background: the follow goes now.
    // Leaving the pane is the toast leaving too, and the window settles itself then.
    fun settleUnfollow() {
        unfollows.settle()
        unfollowing = null
    }
    val pendingTmdbId = unfollowing?.tmdbId
    Box(Modifier.fillMaxSize()) {
        CalendarTrackingList(
            followedSeries = followedSeries.filterNot { it.tmdbId == pendingTmdbId },
            calendarDays = calendarDays,
            component = component,
            bottomContentInset = bottomContentInset,
            onUnfollow = ::unfollow,
        )
        key(unfollowToast) {
            val pending = unfollowing
            ActionToast(
                message = pending?.let { "已取消追剧《${it.title}》" },
                onDismiss = ::settleUnfollow,
                action = pending?.let { series -> ToastAction("撤销") { undoUnfollow(series) } },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CalendarTrackingList(
    followedSeries: List<FollowedSeries>,
    calendarDays: List<CalendarDay>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onUnfollow: (FollowedSeries) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val scope = rememberCoroutineScope()
    var refreshingTmdbId by remember { mutableStateOf<Int?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var confirmUnfollowAll by remember { mutableStateOf(false) }
    var bulkExpanded by remember { mutableStateOf(false) }
    val schedulePosterUrls =
        remember(calendarDays) {
            calendarDays
                .flatMap(CalendarDay::entries)
                .groupBy { it.episode.showTmdbId }
                .mapValues { (_, entries) ->
                    entries
                        .flatMap { entry ->
                            listOf(
                                TmdbImages.poster(entry.episode.posterPath, width = "w185"),
                                TmdbImages.media(entry.episode.posterPath, width = "w185"),
                            ) + entry.posterUrls
                        }.filterNotNull()
                        .distinct()
                }
        }

    if (followedSeries.isEmpty()) {
        // Every show in 日历 opens a sheet with 加入追剧, so that is the way out of an empty list.
        PageHint(
            "还没有加入追剧的剧集，可在日历或剧集详情的更多操作中添加",
            Modifier.fillMaxWidth().padding(top = 40.dp),
            actionLabel = "查看日历",
            onAction = { component.store.accept(CalendarIntent.SelectSection(CalendarSection.Schedule)) },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = bottomContentInset,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        motionItem {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已追 ${followedSeries.size} 部", style = AppTypography.section.strong, color = palette.text)
                        Text(
                            "${followedSeries.count { it.reminderMode != CalendarReminderMode.Off }} 部已开启提醒",
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                        )
                    }
                    Text(
                        if (bulkExpanded) "完成" else "管理",
                        style = AppTypography.body.strong,
                        color = accent.accent,
                        modifier =
                            Modifier
                                .pressable {
                                    bulkExpanded = !bulkExpanded
                                    confirmUnfollowAll = false
                                }.touchTarget(),
                    )
                }
                DisclosureContent(bulkExpanded, rememberDisclosureProgress(bulkExpanded)) {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .glass(
                                AppShapes.card,
                                palette.card2,
                                palette.border,
                            ).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "全部开启提醒",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier =
                                Modifier
                                    .pressable {
                                        confirmUnfollowAll = false
                                        component.setReminderForAll(CalendarReminderMode.BeforeAndAtBroadcast)
                                    }.touchTarget(),
                        )
                        Text(
                            "全部关闭提醒",
                            style = AppTypography.caption.strong,
                            color = palette.sub,
                            modifier =
                                Modifier
                                    .pressable {
                                        confirmUnfollowAll = false
                                        component.setReminderForAll(CalendarReminderMode.Off)
                                    }.touchTarget(),
                        )
                        Text(
                            if (confirmUnfollowAll) "确认取消全部" else "取消全部",
                            style = AppTypography.caption.strong,
                            color = palette.error,
                            modifier =
                                Modifier
                                    .pressable {
                                        if (confirmUnfollowAll) component.unfollowAll() else confirmUnfollowAll = true
                                    }.touchTarget(),
                        )
                    }
                }
            }
        }
        actionMessage?.let { message ->
            motionItem {
                Text(
                    message,
                    style = AppTypography.caption.medium,
                    color = palette.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        motionItems(followedSeries, key = FollowedSeries::tmdbId) { series ->
            var rowActionsExpanded by remember { mutableStateOf(false) }
            var resolvedPosters by remember(
                series.serverId,
                series.seriesItemId,
            ) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(series.serverId, series.seriesItemId) {
                resolvedPosters = component.resolvedTrackingPosterUrls(series)
            }
            // These rows push the detail page, so their artwork is the same artwork the detail
            // hero is about to show — the one list in 追剧中心 that can hand its poster over
            // instead of cross-fading a new page in over it. A row with nothing in the library
            // has no item to open and therefore no key.
            val seriesItemId = series.seriesItemId
            val sharedKey = seriesItemId?.let { MediaSharedElementKey(series.serverId, it) }
            val posterUrls =
                resolvedPosters + component.trackingPosterUrls(series) +
                    listOf(
                        TmdbImages.poster(series.posterPath, width = "w185"),
                        TmdbImages.media(series.posterPath, width = "w185"),
                    ) + schedulePosterUrls[series.tmdbId].orEmpty()
            val openSeriesDetail =
                sharedMediaOnClick(sharedKey, sourceShape = AppShapes.thumb) {
                    seriesItemId?.let { component.onOpenItem(series.serverId, it) }
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card, palette.card2, palette.border)
                    .then(
                        if (seriesItemId != null) {
                            Modifier.pressable(onClick = openSeriesDetail)
                        } else {
                            Modifier
                        },
                    ).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FallbackImage(
                    urls = posterUrls,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .sharedMediaArtwork(sharedKey, sourceShape = AppShapes.thumb, sourceUrls = posterUrls)
                            .width(46.dp)
                            .height(64.dp)
                            .clip(AppShapes.thumb)
                            .background(palette.card2),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        series.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        trackingReminderLabel(series),
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                    DisclosureContent(rowActionsExpanded, rememberDisclosureProgress(rowActionsExpanded)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (refreshingTmdbId == series.tmdbId) "刷新中…" else "单剧刷新",
                                style = AppTypography.caption.strong,
                                color = accent.accent,
                                modifier =
                                    Modifier
                                        .pressable(
                                            enabled = refreshingTmdbId == null,
                                            onClickLabel = "刷新${series.title}排期",
                                        ) {
                                            refreshingTmdbId = series.tmdbId
                                            actionMessage = null
                                            scope.launch {
                                                component
                                                    .refreshSeries(series)
                                                    .onSuccess { refreshed ->
                                                        component.store.accept(
                                                            CalendarIntent.ApplySeriesRefresh(
                                                                tmdbId = series.tmdbId,
                                                                days = refreshed,
                                                            ),
                                                        )
                                                    }.onFailure {
                                                        actionMessage = "${series.title}刷新失败：${it.message ?: "未知错误"}"
                                                    }
                                                refreshingTmdbId = null
                                            }
                                        }.touchTarget(),
                            )
                            Text(
                                "切换提醒",
                                style = AppTypography.caption.strong,
                                color = palette.sub,
                                modifier =
                                    Modifier
                                        .pressable {
                                            component.setReminder(
                                                series.tmdbId,
                                                nextTrackingReminder(series.reminderMode),
                                                series.remindBeforeMinutes,
                                            )
                                        }.touchTarget(),
                            )
                            Text(
                                "取消追剧",
                                style = AppTypography.caption.strong,
                                color = palette.error,
                                modifier =
                                    Modifier
                                        .pressable { onUnfollow(series) }
                                        .touchTarget(),
                            )
                        }
                    }
                }
                Icon(
                    AppIcons.More,
                    contentDescription = if (rowActionsExpanded) "收起${series.title}操作" else "管理${series.title}",
                    tint = accent.accent,
                    modifier =
                        Modifier.pressable { rowActionsExpanded = !rowActionsExpanded }.touchTarget().size(
                            17.dp,
                        ),
                )
            }
        }
    }
}

private fun trackingReminderLabel(series: FollowedSeries): String =
    (if (series.trackingOrigin == CalendarTrackingOrigin.LibraryAuto) "自动追更 · " else "") +
        when (series.reminderMode) {
            CalendarReminderMode.Off -> "提醒已关闭"
            CalendarReminderMode.AtBroadcast -> "播出时提醒"
            CalendarReminderMode.BeforeAndAtBroadcast ->
                "提前 ${series.remindBeforeMinutes} 分钟和播出时提醒"
            CalendarReminderMode.WhenAvailable -> "新集入库时提醒"
        }

private fun nextTrackingReminder(mode: CalendarReminderMode): CalendarReminderMode =
    when (mode) {
        CalendarReminderMode.Off -> CalendarReminderMode.BeforeAndAtBroadcast
        CalendarReminderMode.BeforeAndAtBroadcast -> CalendarReminderMode.AtBroadcast
        CalendarReminderMode.AtBroadcast -> CalendarReminderMode.WhenAvailable
        CalendarReminderMode.WhenAvailable -> CalendarReminderMode.Off
    }
