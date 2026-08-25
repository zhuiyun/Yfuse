package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.isPast
import com.yfuse.core.data.isToday
import com.yfuse.core.data.missingCount
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.motionAwareItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoShortDate
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.core.util.shiftIsoDate
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.flatGlass as glass

/**
 * 追剧日历 — broadcasts by day, each marked with what this library has.
 *
 * Ordered oldest-first including the past week, because the question people open this for
 * is "what have I missed", and a list that starts at today puts that answer above the
 * scroll line where it will never be seen.
 */
@Composable
fun CalendarScreen(component: CalendarComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val followedSeries by component.followStore.followed.collectAsState()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val days = state.visibleDays
    val weeklyStats =
        remember(state.confirmedDays, state.days, state.today) {
            // Refreshes retain the last confirmed statistic. Only a first-load preview has no
            // trustworthy library count yet, so it deliberately shows no aggregate number.
            state.confirmedDays
                .takeIf { it.isNotEmpty() }
                ?.let { calendarWeeklyStats(it, state.today) }
                ?: emptyMap()
        }
    val bottomContentInset = systemNavigationContentInset()
    val share = rememberShareHandler()
    var scheduleChanges by remember(state.loading, state.days) {
        mutableStateOf(component.scheduleChanges())
    }

    // Unlike the artwork-heavy home hero this route always has a quiet page background.
    // Re-assert the icon contrast when navigating here; otherwise the light icons selected
    // for the hero remain white on this light full-screen page.
    StatusBarIconStyle(darkIcons = !palette.isDark)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = Dimens.contentTop, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "返回", onClick = component.onBack)
                            .touchTarget()
                            .size(36.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("追剧中心", style = AppTypography.section.strong, color = palette.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "按原产地播出日期",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                Icon(
                    AppIcons.Download,
                    contentDescription = "导出 ICS 日历",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "导出 ICS 日历") {
                                share.shareCalendar(component.exportCalendar(state.days))
                            }.touchTarget()
                            .size(36.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                )
                Icon(
                    AppIcons.Info,
                    contentDescription = "导出日历诊断",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "导出日历诊断") {
                                share.shareText(component.diagnosticReport(state.days))
                            }.touchTarget()
                            .size(36.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                )
                // The schedule is cached for the day, so nothing else re-reads it; a new
                // download landing is exactly when someone wants 未入库 → 可播放 checked
                // again, and only they know it happened.
                Box(
                    Modifier
                        .pressable(
                            enabled = !state.loading,
                            onClickLabel = "刷新追剧日历",
                        ) { component.store.accept(CalendarIntent.Refresh) }
                        .touchTarget()
                        .size(36.dp)
                        .solidGlass(CircleShape, palette.card2, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            color = accent.accent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            AppIcons.Refresh,
                            contentDescription = "刷新",
                            tint = palette.text,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            CalendarSectionBar(
                selected = state.section,
                onSelect = { component.store.accept(CalendarIntent.SelectSection(it)) },
            )
            Spacer(Modifier.height(12.dp))

            if (scheduleChanges.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.pageHorizontal)
                        .glass(GlassShapes.card, palette.card2, palette.border)
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "官方排期有调整",
                        style = AppTypography.body.strong,
                        color = palette.text,
                    )
                    scheduleChanges.take(3).forEach { change ->
                        Text(
                            "${change.title} · ${change.message}",
                            style = AppTypography.caption.regular,
                            color = palette.sub,
                        )
                    }
                    Text(
                        "知道了",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .pressable {
                                    component.acknowledgeScheduleChanges()
                                    scheduleChanges = emptyList()
                                }.touchTarget(),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (state.section == CalendarSection.Schedule) {
                LazyRow(
                    modifier = Modifier.selectableGroup(),
                    contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(CalendarFilter.entries) { filter ->
                    val active = filter == state.filter
                    Text(
                        filter.label,
                        style = AppTypography.caption.strong,
                        color = if (active) accent.onAccent else palette.body,
                        modifier =
                            Modifier
                                .pressable(role = Role.RadioButton) {
                                    component.store.accept(CalendarIntent.SelectFilter(filter))
                                }.semantics { this.selected = active }
                                .touchTarget()
                                .clip(GlassShapes.chip)
                                .background(if (active) accent.accent else Color.Transparent)
                                .then(
                                    if (active) {
                                        Modifier
                                    } else {
                                        Modifier.glass(GlassShapes.chip, palette.card2, palette.border)
                                    },
                                ).padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (state.error != null && days.isNotEmpty()) {
                val fallbackMessage =
                    if (state.confirmedDays.isNotEmpty()) {
                        "状态更新失败，正在显示上一次成功结果 · 点击重试"
                    } else {
                        "媒体库状态未确认，当前显示排期预览 · 点击重试"
                    }
                Text(
                    fallbackMessage,
                    style = AppTypography.caption.medium,
                    color = palette.error,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pressable(onClickLabel = "重新加载追剧日历") {
                                component.store.accept(CalendarIntent.Refresh)
                            }.touchTarget()
                            .padding(horizontal = Dimens.pageHorizontal, vertical = 6.dp),
                )
            }

            when {
                state.loading && days.isEmpty() ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = accent.accent)
                    }

                state.error != null && days.isEmpty() ->
                    ErrorState(
                        message = state.error!!,
                        onRetry = { component.store.accept(CalendarIntent.Refresh) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                state.filteredToNothing ->
                    PageHint(
                        "这段时间「${state.filter.label}」没有更新",
                        Modifier.align(Alignment.CenterHorizontally),
                    )

                days.isEmpty() ->
                    PageHint(
                        "这段时间没有查到在播剧集",
                        Modifier.align(Alignment.CenterHorizontally),
                    )

                else ->
                    AdaptiveCalendarResults(
                        days = days,
                        today = state.today,
                        todayIndex = state.todayIndex,
                        filter = state.filter,
                        loading = state.loading,
                        weeklyStats = weeklyStats,
                        reduceMotion = reduceMotion,
                        bottomContentInset = bottomContentInset,
                        onOpen = { entry ->
                            entry.openItemId?.let {
                                component.onOpenItem(entry.serverId, it)
                            }
                        },
                    )
            }
            } else {
                CalendarAuxiliaryPane(
                    state = state,
                    followedSeries = followedSeries,
                    component = component,
                    bottomContentInset = bottomContentInset,
                    onExportCalendar = { share.shareText(component.exportCalendar(state.days)) },
                    onExportDiagnostics = {
                        share.shareText(component.diagnosticReport(state.days))
                    },
                )
            }
        }
    }
}

@Composable
private fun AdaptiveCalendarResults(
    days: List<CalendarDay>,
    today: String,
    todayIndex: Int,
    filter: CalendarFilter,
    loading: Boolean,
    weeklyStats: Map<Int, CalendarWeekStats>,
    reduceMotion: Boolean,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) {
            TabletWeekCalendar(
                days = days,
                today = today,
                bottomContentInset = bottomContentInset,
                onOpen = onOpen,
            )
        } else {
            CalendarListResults(
                days = days,
                today = today,
                todayIndex = todayIndex,
                filter = filter,
                loading = loading,
                weeklyStats = weeklyStats,
                reduceMotion = reduceMotion,
                bottomContentInset = bottomContentInset,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun CalendarListResults(
    days: List<CalendarDay>,
    today: String,
    todayIndex: Int,
    filter: CalendarFilter,
    loading: Boolean,
    weeklyStats: Map<Int, CalendarWeekStats>,
    reduceMotion: Boolean,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(filter, today, loading) {
        runCatching { listState.scrollToItem(todayIndex) }
    }
    val awayFromToday by remember(todayIndex) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 0 &&
                layout.visibleItemsInfo.isNotEmpty() &&
                layout.visibleItemsInfo.none { it.index == todayIndex }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DayStrip(
            days = days,
            today = today,
            onSelect = { index ->
                scope.launch {
                    if (reduceMotion) {
                        listState.scrollToItem(index)
                    } else {
                        listState.animateScrollToItem(index)
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = bottomContentInset),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(days, key = { it.date }) { day ->
                    DaySection(
                        day = day,
                        today = today,
                        weeklyStats = weeklyStats,
                        onOpen = onOpen,
                    )
                }
            }
            if (awayFromToday) {
                Text(
                    "回到今天",
                    style = AppTypography.caption.strong,
                    color = accent.onAccent,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomContentInset)
                            .pressable {
                                scope.launch {
                                    if (reduceMotion) {
                                        listState.scrollToItem(todayIndex)
                                    } else {
                                        listState.animateScrollToItem(todayIndex)
                                    }
                                }
                            }.touchTarget()
                            .clip(GlassShapes.chip)
                            .background(accent.accent)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TabletWeekCalendar(
    days: List<CalendarDay>,
    today: String,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
) {
    val palette = LocalPalette.current
    val weekdayIndex =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .indexOf(isoWeekdayLabel(today))
            .coerceAtLeast(0)
    val weekStart = shiftIsoDate(today, -weekdayIndex)
    val byDate = remember(days) { days.associateBy(CalendarDay::date) }
    val week =
        remember(days, today) {
            (0..6).map { offset ->
                val date = shiftIsoDate(weekStart, offset)
                byDate[date] ?: CalendarDay(date = date, entries = emptyList())
            }
        }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        week.forEach { day ->
            val isToday = day.date == today
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .glass(GlassShapes.card, palette.card2, palette.border)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    if (isToday) "今天" else isoWeekdayLabel(day.date),
                    style = AppTypography.caption.strong,
                    color = if (isToday) LocalAccentColors.current.accent else palette.text,
                )
                Text(
                    isoShortDate(day.date),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
                if (day.missingCount > 0) {
                    Text(
                        "${day.missingCount} 集待入库",
                        style = AppTypography.caption.strong,
                        color = palette.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (day.entries.isEmpty()) {
                    Text("无更新", style = AppTypography.caption.regular, color = palette.sub2)
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentInset),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(
                            coalesceCalendarEntries(day.entries),
                            key = { it.entry.episode.mediaKey },
                        ) { display ->
                            TabletWeekEntryCard(display, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletWeekEntryCard(
    display: CalendarDisplayEntry,
    onOpen: (CalendarEntry) -> Unit,
) {
    val palette = LocalPalette.current
    val entry = display.entry
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GlassShapes.chip)
            .background(palette.card)
            .then(
                if (entry.openItemId != null) {
                    Modifier.pressable { onOpen(entry) }
                } else {
                    Modifier
                },
            ).padding(8.dp),
    ) {
        Text(
            entry.episode.showTitle,
            style = AppTypography.caption.strong,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            display.episodeLabel,
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        StatusBadge(entry)
    }
}

@Composable
private fun CalendarSectionBar(
    selected: CalendarSection,
    onSelect: (CalendarSection) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    LazyRow(
        modifier = Modifier.selectableGroup(),
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(CalendarSection.entries) { section ->
            val active = selected == section
            Text(
                section.label,
                style = AppTypography.body.strong,
                color = if (active) accent.onAccent else palette.text,
                modifier =
                    Modifier
                        .pressable(role = Role.RadioButton) { onSelect(section) }
                        .semantics { this.selected = active }
                        .touchTarget()
                        .clip(GlassShapes.chip)
                        .background(if (active) accent.accent else Color.Transparent)
                        .then(
                            if (active) Modifier else
                                Modifier.glass(GlassShapes.chip, palette.card2, palette.border),
                        ).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CalendarAuxiliaryPane(
    state: CalendarState,
    followedSeries: List<FollowedSeries>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onExportCalendar: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    when (state.section) {
        CalendarSection.Schedule -> Unit
        CalendarSection.Tracking ->
            CalendarTrackingPane(
                followedSeries = followedSeries,
                component = component,
                bottomContentInset = bottomContentInset,
            )
        CalendarSection.Resources ->
            CalendarResourcesPane(
                days = state.confirmedDays.ifEmpty { state.days },
                component = component,
                bottomContentInset = bottomContentInset,
            )
        CalendarSection.Settings ->
            CalendarSettingsPane(
                state = state,
                component = component,
                bottomContentInset = bottomContentInset,
                onExportCalendar = onExportCalendar,
                onExportDiagnostics = onExportDiagnostics,
            )
    }
}

@Composable
private fun CalendarTrackingPane(
    followedSeries: List<FollowedSeries>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val scope = rememberCoroutineScope()
    var refreshingTmdbId by remember { mutableStateOf<Int?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var confirmUnfollowAll by remember { mutableStateOf(false) }

    if (followedSeries.isEmpty()) {
        PageHint(
            "还没有加入追剧的剧集，可在剧集详情的更多操作中添加",
            Modifier.fillMaxWidth().padding(top = 40.dp),
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
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(GlassShapes.card, palette.card2, palette.border)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "全部开启提醒",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .pressable {
                                confirmUnfollowAll = false
                                component.setReminderForAll(
                                    CalendarReminderMode.BeforeAndAtBroadcast,
                                    beforeMinutes = 30,
                                )
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
                                component.setReminderForAll(
                                    CalendarReminderMode.Off,
                                    beforeMinutes = 30,
                                )
                            }.touchTarget(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (confirmUnfollowAll) "确认取消全部" else "取消全部",
                    style = AppTypography.caption.strong,
                    color = palette.error,
                    modifier =
                        Modifier
                            .pressable {
                                if (confirmUnfollowAll) {
                                    component.unfollowAll()
                                } else {
                                    confirmUnfollowAll = true
                                }
                            }.touchTarget(),
                )
            }
        }
        actionMessage?.let { message ->
            item {
                Text(
                    message,
                    style = AppTypography.caption.medium,
                    color = palette.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        items(followedSeries, key = FollowedSeries::tmdbId) { series ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(GlassShapes.card, palette.card2, palette.border)
                    .then(
                        if (series.seriesItemId != null) {
                            Modifier.pressable {
                                component.onOpenItem(series.serverId, series.seriesItemId)
                            }
                        } else {
                            Modifier
                        },
                    ).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FallbackImage(
                    urls =
                        listOf(
                            TmdbImages.poster(series.posterPath, width = "w185"),
                            TmdbImages.media(series.posterPath, width = "w185"),
                        ),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .width(48.dp)
                            .height(68.dp)
                            .clip(GlassShapes.thumb)
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
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (refreshingTmdbId == series.tmdbId) "刷新中…" else "单剧刷新",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier =
                                Modifier.pressable(
                                    enabled = refreshingTmdbId == null,
                                    onClickLabel = "刷新${series.title}排期",
                                ) {
                                    refreshingTmdbId = series.tmdbId
                                    actionMessage = null
                                    scope.launch {
                                        component.refreshSeries(series)
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
                                Modifier.pressable {
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
                                Modifier.pressable {
                                    component.unfollow(series.tmdbId)
                                }.touchTarget(),
                        )
                    }
                }
                if (series.seriesItemId != null) {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.sub2,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private fun trackingReminderLabel(series: FollowedSeries): String =
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

private data class CalendarResourceSummary(
    val title: String,
    val entry: CalendarEntry,
    val episodeCount: Int,
    val availableCount: Int,
    val missingCount: Int,
    val servers: List<String>,
    val qualities: List<String>,
)

@Composable
private fun CalendarResourcesPane(
    days: List<CalendarDay>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
) {
    val palette = LocalPalette.current
    var enrichedDays by remember(days) { mutableStateOf(days) }
    var enriching by remember(days) { mutableStateOf(days.isNotEmpty()) }
    var enrichmentError by remember(days) { mutableStateOf<String?>(null) }
    LaunchedEffect(days) {
        if (days.isEmpty()) return@LaunchedEffect
        component.enrichResourceDetails(days)
            .onSuccess { enrichedDays = it }
            .onFailure { enrichmentError = it.message ?: "资源画质读取失败" }
        enriching = false
    }
    val summaries =
        remember(enrichedDays) {
            enrichedDays
                .flatMap(CalendarDay::entries)
                .groupBy { it.episode.showTmdbId }
                .values
                .map { entries ->
                    val representative =
                        entries.maxBy { entry ->
                            entry.sources.size * 10 +
                                if (entry.openItemId != null) 5 else 0 +
                                if (entry.inLibrary) 1 else 0
                        }
                    CalendarResourceSummary(
                        title = representative.episode.showTitle,
                        entry = representative,
                        episodeCount = entries.size,
                        availableCount =
                            entries.count {
                                it.status in
                                    setOf(
                                        LibraryStatus.Available,
                                        LibraryStatus.InProgress,
                                        LibraryStatus.Watched,
                                    )
                            },
                        missingCount = entries.count { it.status == LibraryStatus.Missing },
                        servers = entries.flatMap(CalendarEntry::serverNames).distinct(),
                        qualities = entries.flatMap(CalendarEntry::qualityTags).distinct(),
                    )
                }.sortedBy(CalendarResourceSummary::title)
        }

    if (summaries.isEmpty()) {
        PageHint("暂无可汇总的追剧资源", Modifier.fillMaxWidth().padding(top = 40.dp))
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
        if (enriching || enrichmentError != null) {
            item {
                Text(
                    if (enriching) "正在读取片源画质…" else enrichmentError.orEmpty(),
                    style = AppTypography.caption.medium,
                    color = if (enrichmentError == null) palette.sub2 else palette.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        items(summaries, key = { it.entry.episode.showTmdbId }) { summary ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(GlassShapes.card, palette.card2, palette.border)
                    .then(
                        summary.entry.openItemId?.let { itemId ->
                            Modifier.pressable {
                                component.onOpenItem(summary.entry.serverId, itemId)
                            }
                        } ?: Modifier,
                    ).padding(13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${summary.episodeCount} 集排期 · ${summary.availableCount} 集可用" +
                            if (summary.missingCount > 0) " · ${summary.missingCount} 集待入库" else "",
                        style = AppTypography.caption.regular,
                        color = if (summary.missingCount > 0) palette.error else palette.sub2,
                    )
                    val sources = (summary.servers + summary.qualities).take(5).joinToString(" · ")
                    if (sources.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            sources,
                            style = AppTypography.caption.medium,
                            color = palette.sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                summary.entry.openItemId?.let {
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.sub2,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarSettingsPane(
    state: CalendarState,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onExportCalendar: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = bottomContentInset,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("内容类型", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CalendarContentFilter.entries) { content ->
                    val active = state.contentFilter == content
                    Text(
                        content.label,
                        style = AppTypography.caption.strong,
                        color = if (active) accent.onAccent else palette.body,
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectContent(content))
                                }.touchTarget()
                                .clip(GlassShapes.chip)
                                .background(if (active) accent.accent else Color.Transparent)
                                .then(
                                    if (active) Modifier else
                                        Modifier.glass(GlassShapes.chip, palette.card2, palette.border),
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item {
            Text("播出平台", style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val active = state.platform == null
                    Text(
                        "全部平台",
                        style = AppTypography.caption.strong,
                        color = if (active) accent.onAccent else palette.body,
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectPlatform(null))
                                }.touchTarget()
                                .clip(GlassShapes.chip)
                                .background(if (active) accent.accent else Color.Transparent)
                                .then(
                                    if (active) Modifier else
                                        Modifier.glass(GlassShapes.chip, palette.card2, palette.border),
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
                items(state.availablePlatforms) { platform ->
                    val active = state.platform == platform
                    Text(
                        platform,
                        style = AppTypography.caption.strong,
                        color = if (active) accent.onAccent else palette.body,
                        modifier =
                            Modifier
                                .pressable {
                                    component.store.accept(CalendarIntent.SelectPlatform(platform))
                                }.touchTarget()
                                .clip(GlassShapes.chip)
                                .background(if (active) accent.accent else Color.Transparent)
                                .then(
                                    if (active) Modifier else
                                        Modifier.glass(GlassShapes.chip, palette.card2, palette.border),
                                ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item {
            CalendarSettingsAction(
                title = "导出 ICS 日历",
                description = "导入系统日历或其他日历应用",
                onClick = onExportCalendar,
            )
        }
        item {
            CalendarSettingsAction(
                title = "导出诊断",
                description = "包含排期版本、缓存与媒体库状态",
                onClick = onExportDiagnostics,
            )
        }
        item {
            CalendarSettingsAction(
                title = "立即刷新全部状态",
                description = "绕过排期和媒体库身份缓存",
                onClick = { component.store.accept(CalendarIntent.Refresh) },
            )
        }
        item {
            Text(
                "平台和内容筛选会同时作用于“日历”页；提醒的具体模式和提前量可在“追剧”页或剧集详情中调整。",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CalendarSettingsAction(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .touchTarget()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.body.strong, color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(description, style = AppTypography.caption.regular, color = palette.sub2)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = palette.sub2,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The days that have something on them, as a row of chips above the list.
 *
 * Built from the days that survived the filter rather than from the whole window: a chip
 * for a date the list does not contain would scroll to the wrong place, and an empty
 * Tuesday is not worth a tap target. Tapping scrolls rather than filters — the list stays
 * one continuous thing you can keep reading past the day you jumped to.
 */
@Composable
private fun DayStrip(
    days: List<CalendarDay>,
    today: String,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        itemsIndexed(days, key = { _, day -> day.date }) { index, day ->
            val isToday = day.isToday(today)
            Column(
                Modifier
                    // The strip is rebuilt whenever the filter changes.
                    .then(motionAwareItem())
                    .pressable(onClickLabel = "跳到${dayLabel(day.date, today)}") { onSelect(index) }
                    .touchTarget()
                    .clip(GlassShapes.chip)
                    .background(if (isToday) accent.accent else Color.Transparent)
                    .then(
                        if (isToday) {
                            Modifier
                        } else {
                            Modifier.glass(GlassShapes.chip, palette.card2, palette.border)
                        },
                    ).padding(horizontal = 11.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (isToday) "今天" else isoWeekdayLabel(day.date),
                    style = AppTypography.caption.strong,
                    color = if (isToday) accent.onAccent else palette.text,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    isoShortDate(day.date),
                    style = AppTypography.caption.medium,
                    color = if (isToday) accent.onAccent.copy(alpha = 0.8f) else palette.sub2,
                    maxLines = 1,
                )
                // A day with gaps is the only one worth marking from up here; the count
                // itself is on the day's own header, where there is room for it.
                if (day.missingCount > 0) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (isToday) accent.onAccent else palette.error),
                    )
                }
            }
        }
    }
}

@Composable
private fun DaySection(
    day: CalendarDay,
    today: String,
    weeklyStats: Map<Int, CalendarWeekStats>,
    onOpen: (CalendarEntry) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val isToday = day.isToday(today)
    val displayEntries = remember(day.entries) { coalesceCalendarEntries(day.entries) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                dayLabel(day.date, today),
                style = AppTypography.body.strong,
                color = if (isToday) accent.accent else palette.text,
            )
            Text(
                day.date,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.weight(1f),
            )
            // Only stated when there is something to act on; a zero would be noise on
            // every other row.
            if (day.missingCount > 0 && (day.isPast(today) || isToday)) {
                Text(
                    "${day.missingCount} 集未入库",
                    style = AppTypography.caption.strong,
                    color = palette.error,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 720.dp
            Column(
                Modifier.padding(horizontal = Dimens.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (twoColumns) {
                    displayEntries.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { display ->
                                EntryCard(
                                    display = display,
                                    weekStats = weeklyStats[display.entry.episode.showTmdbId],
                                    onOpen = { onOpen(display.entry) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    displayEntries.forEach { display ->
                        EntryCard(
                            display = display,
                            weekStats = weeklyStats[display.entry.episode.showTmdbId],
                            onOpen = { onOpen(display.entry) },
                        )
                    }
                }
            }
        }
    }
}

/** `今天` / `明天` / `3 天前` — a date alone makes the reader do the arithmetic. */
private fun dayLabel(
    date: String,
    today: String,
): String =
    when (val delta = daysBetweenIso(today, date)) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        -1 -> "昨天"
        else -> if (delta > 0) "$delta 天后" else "${-delta} 天前"
    }

@Composable
private fun EntryCard(
    display: CalendarDisplayEntry,
    weekStats: CalendarWeekStats?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    // Tappable for anything the library holds — the episode if it has it, the show if not.
    val openable = entry.openItemId != null
    Row(
        modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .then(if (openable) Modifier.pressable(onClick = onOpen) else Modifier)
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FallbackImage(
            urls =
                listOf(
                    TmdbImages.poster(entry.episode.posterPath, width = "w185"),
                    TmdbImages.media(entry.episode.posterPath, width = "w185"),
                ) + entry.posterUrls,
            contentDescription = null,
            modifier =
                Modifier
                    .width(68.dp)
                    .height(96.dp)
                    .clip(GlassShapes.thumb)
                    .background(palette.card2),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.episode.showTitle,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                display.episodeLabel,
                style = AppTypography.caption.regular,
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.episode.isMovie && weekStats != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本周预计 ${weekStats.scheduled} 集 · 已入库 ${weekStats.available} 集",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(entry)
                Text(
                    display.statusLine ?: broadcastStateLabel(entry),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val sourceFacts =
                (entry.serverNames.take(2) + entry.qualityTags.take(3))
                    .distinct()
                    .joinToString(" · ")
            if (sourceFacts.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    sourceFacts,
                    style = AppTypography.caption.medium,
                    color = palette.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.playedPercentage?.takeIf { it > 0.0 }?.let { percentage ->
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(palette.border),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((percentage / 100.0).toFloat().coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(accent.accent),
                    )
                }
            }
        }
        if (openable) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.sub2,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private data class CalendarDisplayEntry(
    val entry: CalendarEntry,
    val episodeLabel: String,
    val statusLine: String? = null,
)

/** Collapses a same-day multi-episode drop into one card without losing per-episode counts. */
private fun coalesceCalendarEntries(entries: List<CalendarEntry>): List<CalendarDisplayEntry> =
    entries
        .groupBy { entry ->
            listOf(
                entry.episode.kind.name,
                entry.episode.showTmdbId.toString(),
                entry.episode.seasonNumber.toString(),
                entry.episode.airTime.orEmpty(),
                entry.episode.platforms.joinToString("|"),
            ).joinToString(":")
        }.values
        .map { group ->
            if (group.size == 1 || group.first().episode.isMovie) {
                val entry = group.first()
                return@map CalendarDisplayEntry(entry, entry.episode.episodeLabel)
            }
            val sorted = group.sortedBy { it.episode.episodeNumber }
            val numbers = sorted.map { it.episode.episodeNumber }
            val label =
                if (numbers.zipWithNext().all { (a, b) -> b == a + 1 }) {
                    "第 ${numbers.first()}～${numbers.last()} 集"
                } else {
                    numbers.joinToString("、", prefix = "第 ", postfix = " 集")
                }
            val representative =
                sorted.maxBy { entry ->
                    val open = if (entry.openItemId != null) 10 else 0
                    open +
                        when (entry.status) {
                            LibraryStatus.InProgress -> 6
                            LibraryStatus.Available -> 5
                            LibraryStatus.Watched -> 4
                            LibraryStatus.Missing -> 3
                            LibraryStatus.Unaired -> 2
                            LibraryStatus.Unknown -> 1
                        }
                }
            val available =
                sorted.count {
                    it.status in setOf(LibraryStatus.Available, LibraryStatus.InProgress, LibraryStatus.Watched)
                }
            CalendarDisplayEntry(
                entry = representative,
                episodeLabel = label,
                statusLine = "$available/${sorted.size} 集已入库 · ${broadcastStateLabel(representative)}",
            )
        }.sortedWith(
            compareBy<CalendarDisplayEntry> { it.entry.episode.airTime ?: "99:99" }
                .thenBy { it.entry.episode.showTitle },
        )

@Composable
private fun StatusBadge(entry: CalendarEntry) {
    val palette = LocalPalette.current
    val status = entry.status
    val (label, tint) =
        if (entry.discoveryOnly && !entry.followed) {
            "未收录" to palette.sub2
        } else {
            when (status) {
            LibraryStatus.Unaired -> "未播出" to palette.sub2
            LibraryStatus.Missing -> "未入库" to palette.error
            LibraryStatus.Available -> "可播放" to Brand.Online
            LibraryStatus.InProgress -> "观看中" to Brand.Online
            LibraryStatus.Watched -> "已看" to palette.sub2
                LibraryStatus.Unknown -> "未知" to palette.sub2
            }
        }
    Text(
        label,
        style = AppTypography.caption.strong,
        color = tint,
        modifier =
            Modifier
                .clip(GlassShapes.chip)
                .background(tint.copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun broadcastStateLabel(entry: CalendarEntry): String {
    if (entry.discoveryOnly && !entry.followed) return "播出参考 · 不在媒体库"
    when (entry.dataIssue) {
        CalendarDataIssue.NoServer -> return "未连接媒体库，仅显示排期"
        CalendarDataIssue.LibraryLookupFailed -> return "媒体库查询失败，请刷新"
        CalendarDataIssue.IdentityUnmatched -> return "剧集身份待确认"
        null -> Unit
    }
    val episode = entry.episode
    val state =
        when (entry.status) {
            LibraryStatus.Unaired -> "等待播出"
            LibraryStatus.Missing -> "已播出，等待入库"
            LibraryStatus.Available -> "已入库未观看"
            LibraryStatus.InProgress -> "已入库"
            LibraryStatus.Watched -> "已完成"
            LibraryStatus.Unknown -> "仅供播出参考"
        }
    if (episode.scheduleAuthority != AiringScheduleAuthority.Official) return state
    val tier =
        when (episode.accessTier) {
            AiringAccessTier.Member -> "会员"
            AiringAccessTier.SviP -> "SVIP"
            AiringAccessTier.Free -> "免费"
            AiringAccessTier.Unknown -> null
        }
    return buildList {
        add("官方排期")
        episode.airTime?.let(::add)
        addAll(episode.platforms.take(2))
        tier?.let(::add)
        add(state)
    }.distinct().joinToString(" · ")
}

private data class CalendarWeekStats(
    val scheduled: Int,
    val available: Int,
)

private fun calendarWeeklyStats(
    days: List<CalendarDay>,
    today: String,
): Map<Int, CalendarWeekStats> {
    val weekdayIndex =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .indexOf(isoWeekdayLabel(today))
            .coerceAtLeast(0)
    return days
        .flatMap(CalendarDay::entries)
        .filter { entry ->
            val delta = daysBetweenIso(today, entry.episode.airDate)
            delta in -weekdayIndex..(6 - weekdayIndex)
        }.groupBy { it.episode.showTmdbId }
        .mapValues { (_, entries) ->
            CalendarWeekStats(
                scheduled = entries.size,
                available =
                    entries.count {
                        it.status == LibraryStatus.Available ||
                            it.status == LibraryStatus.InProgress ||
                            it.status == LibraryStatus.Watched
                    },
            )
        }
}
