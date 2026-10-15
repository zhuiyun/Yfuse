package com.yfuse.feature.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.isToday
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DisclosureContent
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.animateRotationAsState
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.util.isoShortDate
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** Keep the calendar's header and day-row geometry while the first request is pending. */
@Composable
internal fun CalendarLoadingContent() {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.pageHorizontal)
            .clip(AppShapes.card)
            .background(palette.card2)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonBlock(Modifier.width(130.dp).height(20.dp))
        repeat(3) { index ->
            SkeletonBlock(Modifier.width(100.dp).height(16.dp), phaseMs = index * Motion.SKELETON_PHASE_STEP)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(Modifier.width(62.dp).height(88.dp), phaseMs = index * Motion.SKELETON_PHASE_STEP)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(
                        Modifier.fillMaxWidth(0.8f).height(16.dp),
                        phaseMs =
                            index * Motion.SKELETON_PHASE_STEP,
                    )
                    SkeletonBlock(
                        Modifier.fillMaxWidth(0.55f).height(14.dp),
                        phaseMs =
                            index * Motion.SKELETON_PHASE_STEP,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CalendarWeekHeader(
    days: List<CalendarDay>,
    today: String,
    filter: CalendarFilter,
    filtersExpanded: Boolean,
    error: String?,
    hasConfirmedData: Boolean,
    onToggleFilters: () -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val weekShowCount =
        remember(days, today) {
            calendarWeekDays(days, today)
                .flatMap(CalendarDay::entries)
                .distinctBy { it.episode.showTmdbId }
                .size
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                AppIcons.WatchCalendar,
                contentDescription = null,
                tint = palette.sub,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "本周 $weekShowCount 部更新",
                style = AppTypography.caption.medium,
                color = palette.sub,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .pressable(
                        onClickLabel = if (filtersExpanded) "收起日历筛选" else "展开日历筛选",
                        onClick = onToggleFilters,
                    ).touchTarget()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    filter.label,
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                )
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (error != null && days.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text(
                if (hasConfirmedData) {
                    "状态更新失败，正在显示上次结果 · 点击重试"
                } else {
                    "媒体库状态待确认 · 点击重试"
                },
                style = AppTypography.caption.medium,
                color = palette.error,
                modifier =
                    Modifier
                        .pressable(onClickLabel = "重新加载追剧日历", onClick = onRetry)
                        .touchTarget(),
            )
        }
    }
}

@Composable
internal fun AdaptiveCalendarResults(
    days: List<CalendarDay>,
    today: String,
    filter: CalendarFilter,
    weeklyStats: Map<Int, CalendarWeekStats>,
    reduceMotion: Boolean,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
    liftMenu: (CalendarDisplayEntry) -> LiftMenu,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 900.dp) {
            TabletWeekCalendar(
                days = days,
                today = today,
                bottomContentInset = bottomContentInset,
                onOpen = onOpen,
                liftMenu = liftMenu,
            )
        } else {
            CalendarListResults(
                days = days,
                today = today,
                filter = filter,
                weeklyStats = weeklyStats,
                reduceMotion = reduceMotion,
                bottomContentInset = bottomContentInset,
                onOpen = onOpen,
                liftMenu = liftMenu,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarListResults(
    days: List<CalendarDay>,
    today: String,
    filter: CalendarFilter,
    weeklyStats: Map<Int, CalendarWeekStats>,
    reduceMotion: Boolean,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
    liftMenu: (CalendarDisplayEntry) -> LiftMenu,
) {
    val palette = LocalPalette.current
    val timelineDays =
        remember(days, today, filter) {
            calendarTimelineDays(days, today, filter)
        }
    val weekStart = remember(today) { currentWeekStart(today) }
    val initialWeekIndex =
        remember(timelineDays, weekStart) {
            timelineDays.indexOfFirst { it.date >= weekStart }.coerceAtLeast(0)
        }
    val displayEntriesByDate =
        remember(timelineDays) {
            timelineDays.associate { day -> day.date to coalesceCalendarEntries(day.entries) }
        }
    val listState = rememberLazyListState()
    val initiallyExpandedDate =
        timelineDays.firstOrNull { it.date == today }?.date
            ?: timelineDays.firstOrNull()?.date
    var expandedDates by
        remember(today, filter, timelineDays) {
            mutableStateOf(setOfNotNull(initiallyExpandedDate))
        }
    val allContentExpanded =
        timelineDays.isNotEmpty() && expandedDates.size == timelineDays.size

    LaunchedEffect(filter, today, initialWeekIndex) {
        runCatching { listState.scrollToItem(initialWeekIndex * 2 + 1) }
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = Dimens.pageHorizontal)
                .clip(AppShapes.card)
                .background(palette.card2)
                .border(1.dp, palette.border, AppShapes.card),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomContentInset),
    ) {
        motionItem(key = "expand-all-content", contentType = "calendar-expand-all") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(
                        onClickLabel =
                            if (allContentExpanded) {
                                "收起所有内容"
                            } else {
                                "展开所有内容"
                            },
                    ) {
                        expandedDates =
                            if (allContentExpanded) {
                                setOfNotNull(initiallyExpandedDate)
                            } else {
                                timelineDays.mapTo(linkedSetOf(), CalendarDay::date)
                            }
                    }.touchTarget()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (allContentExpanded) "收起所有内容" else "展开所有内容",
                    style = AppTypography.body.strong,
                    color = LocalAccentColors.current.accent,
                )
                Text(
                    "共 ${displayEntriesByDate.values.sumOf(List<CalendarDisplayEntry>::size)} 项",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        }
        timelineDays.forEachIndexed { dayIndex, day ->
            val expanded = day.date in expandedDates
            val displayEntries = displayEntriesByDate[day.date].orEmpty()
            // A day and its rows are one block, and the date it belongs to used to scroll out
            // of the viewport long before the block did — so a reader half way down 周四 was
            // looking at episodes with no date over them. The header stays instead.
            stickyHeader(key = "day-header:${day.date}", contentType = "calendar-day-header") {
                // Which item this header is: the 展开全部 row, then two items per day.
                val headerIndex = dayIndex * 2 + 1
                val stuck by remember(listState, headerIndex) {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > headerIndex ||
                            (
                                listState.firstVisibleItemIndex == headerIndex &&
                                    listState.firstVisibleItemScrollOffset > 0
                            )
                    }
                }
                CalendarDayHeader(
                    day = day,
                    today = today,
                    expanded = expanded,
                    showCount = displayEntries.size,
                    stuck = stuck,
                    onToggle = {
                        expandedDates = if (expanded) expandedDates - day.date else expandedDates + day.date
                    },
                )
            }
            motionItem(key = "day-content:${day.date}", contentType = "calendar-day-content") {
                val disclosure = rememberDisclosureProgress(expanded)
                DisclosureContent(expanded = expanded, progress = disclosure) {
                    if (displayEntries.isEmpty()) {
                        EmptyCalendarDay(
                            expanded = true,
                            onToggle = { expandedDates = expandedDates - day.date },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        )
                    } else {
                        displayEntries.forEach { display ->
                            androidx.compose.runtime.key(display.entry.episode.mediaKey) {
                                AccordionCalendarEntry(
                                    display = display,
                                    weekStats = weeklyStats[display.entry.episode.showTmdbId],
                                    expanded = true,
                                    extraCount = 0,
                                    showChevron = false,
                                    onToggle = { expandedDates = expandedDates - day.date },
                                    onOpen = { onOpen(display.entry) },
                                    liftMenu = { liftMenu(display) },
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = 12.dp,
                                        ).height(1.dp)
                                        .background(palette.border),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayHeader(
    day: CalendarDay,
    today: String,
    expanded: Boolean,
    showCount: Int,
    stuck: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val rotation = animateRotationAsState(if (expanded) 90f else 0f)
    val isToday = day.isToday(today)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Pinned, this header has the rows it belongs to sliding underneath it: it takes the
    // list's own ground so they cannot read through, and casts a short shade past its lower
    // edge so it reads as being in front of them. Released, both fade back out over
    // Motion.QUICK — and snap, like every other animation here, under 减弱动态效果.
    val pinned =
        animateFloatAsState(
            targetValue = if (stuck) 1f else 0f,
            animationSpec = if (reduceMotion) snap() else tween(Motion.QUICK, easing = Motion.Curve),
            label = "calendarDayHeaderPinned",
        )
    val ground = palette.card2.compositeOver(palette.background)
    val shade = (if (palette.isDark) Color.Black else Color(0xFF1E2846)).copy(alpha = 0.16f)
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val amount = pinned.value
            if (amount <= 0f) return@drawBehind
            drawRect(ground, alpha = amount)
            val depth = CalendarStuckShade.toPx()
            drawRect(
                brush =
                    Brush.verticalGradient(
                        0f to shade.copy(alpha = shade.alpha * amount),
                        1f to Color.Transparent,
                        startY = size.height,
                        endY = size.height + depth,
                    ),
                topLeft = Offset(0f, size.height),
                size = Size(size.width, depth),
            )
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .pressable(
                    onClickLabel = if (expanded) "收起${day.date}" else "展开${day.date}",
                    onClick = onToggle,
                ).background(selectionColor(if (expanded) accent.container.copy(alpha = 0.24f) else Color.Transparent))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (expanded) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(30.dp)
                        .clip(CircleShape)
                        .background(accent.accent),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        isoWeekdayLabel(day.date),
                        style = AppTypography.body.strong,
                        color = if (expanded) accent.accent else palette.text,
                    )
                    if (isToday) {
                        Text(
                            "今天",
                            style = AppTypography.caption.strong,
                            color = accent.onAccent,
                            modifier =
                                Modifier
                                    .clip(AppShapes.chip)
                                    .background(accent.accent)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    isoShortDate(day.date),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            Text(
                if (showCount > 0) "$showCount 部更新" else "无更新",
                style = AppTypography.caption.medium,
                color = if (showCount > 0) palette.sub else palette.sub2,
            )
            Icon(
                AppIcons.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = if (expanded) accent.accent else palette.sub2,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation.value },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
    }
}

/** How far a pinned day header's shade reaches past its own lower edge. */
private val CalendarStuckShade = 6.dp

@Composable
private fun CalendarDateRail(
    day: CalendarDay,
    today: String,
    expanded: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val isToday = day.isToday(today)
    Column(
        Modifier
            .width(70.dp)
            .heightIn(min = minHeight)
            .pressable(onClickLabel = if (expanded) "收起${day.date}" else "展开${day.date}", onClick = onToggle)
            .touchTarget()
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            isoWeekdayLabel(day.date),
            style = AppTypography.body.strong,
            color = if (isToday || expanded) accent.accent else palette.text,
        )
        Text(
            isoShortDate(day.date),
            style = AppTypography.caption.regular,
            color = if (isToday || expanded) accent.accent else palette.sub2,
        )
        Text(
            dayLabel(day.date, today),
            style = AppTypography.caption.medium,
            color = if (isToday) accent.accent else palette.sub2,
        )
    }
}

@Composable
private fun EmptyCalendarDay(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .pressable(onClickLabel = if (expanded) "收起日期" else "展开日期", onClick = onToggle)
            .touchTarget()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "无更新",
            style = AppTypography.caption.regular,
            color = palette.sub2,
            modifier = Modifier.weight(1f),
        )
        Icon(
            AppIcons.ChevronDown,
            // The row already carries the state via its own pressable onClickLabel, so the
            // chevron stays decorative rather than announcing "收起"/"展开" a second time.
            contentDescription = null,
            tint = palette.sub2,
            modifier = Modifier.size(16.dp),
        )
    }
}
