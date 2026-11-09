package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.glassSelection
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberGlassSelectionStyle
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
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
    displayDate: String,
    filter: CalendarFilter,
    filtersExpanded: Boolean,
    error: String?,
    hasConfirmedData: Boolean,
    changesCount: Int,
    changesExpanded: Boolean,
    onToggleChanges: () -> Unit,
    onToggleFilters: () -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val weekShowCount =
        remember(days, displayDate) {
            calendarWeekDays(days, displayDate)
                .flatMap(CalendarDay::entries)
                .distinctBy { it.episode.titleKey }
                .size
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal, vertical = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(
                        if (changesCount > 0) {
                            Modifier.pressable(
                                role = Role.Button,
                                onClickLabel = if (changesExpanded) "收起排期调整" else "查看 $changesCount 条排期调整",
                                onClick = onToggleChanges,
                            )
                        } else {
                            Modifier
                        },
                    ).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${displayDate
                        .substring(
                            5,
                            7,
                        ).toIntOrNull() ?: ""}月${displayDate.takeLast(2).toIntOrNull() ?: ""}日",
                    style = AppTypography.section.strong,
                    color = palette.text,
                )
                Text(
                    "${isoWeekdayLabel(displayDate)} · ${dayLabel(displayDate, today)} · 该周 $weekShowCount 部",
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                )
                if (changesCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            "$changesCount 条排期调整",
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                        )
                        Icon(
                            if (changesExpanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = palette.sub2,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .pressable(
                        onClickLabel = if (filtersExpanded) "收起日历筛选" else "展开日历筛选",
                        onClick = onToggleFilters,
                    ).heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp),
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
    selectedDate: String,
    onSelectDate: (String) -> Unit,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
    onPlay: (CalendarEntry) -> Unit,
    onFollow: (CalendarEntry) -> Unit,
    backdrop: suspend (CalendarEntry) -> String?,
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
            CalendarPosterResults(
                days = days,
                today = today,
                selectedDate = selectedDate,
                onSelectDate = onSelectDate,
                bottomContentInset = bottomContentInset,
                onOpen = onOpen,
                onPlay = onPlay,
                onFollow = onFollow,
                backdrop = backdrop,
                liftMenu = liftMenu,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarWeekNavigator(
    days: List<CalendarDay>,
    today: String,
    selectedDate: String?,
    onSelect: (CalendarDay) -> Unit,
) {
    val palette = LocalPalette.current
    val selection = rememberGlassSelectionStyle()
    val density = LocalDensity.current
    val fontScale = density.fontScale
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal, vertical = 4.dp),
    ) {
        // Keep the start of the week visible. Narrow widths and large text wrap whole dates.
        val gap = 4.dp
        val minimumDayWidth = maxOf(40.dp, (26f * fontScale + 8f).dp)
        val gapPx = with(density) { gap.roundToPx() }
        val minimumDayWidthPx = with(density) { minimumDayWidth.roundToPx() }
        val capacity = ((constraints.maxWidth + gapPx) / (minimumDayWidthPx + gapPx)).coerceAtLeast(1)
        val columns =
            when {
                days.size <= capacity -> days.size.coerceAtLeast(1)
                capacity >= 4 -> 4
                else -> capacity
            }
        val availableCellPx = (constraints.maxWidth - gapPx * (columns - 1)) / columns
        val dayWidth = minOf(76.dp * maxOf(1f, fontScale), with(density) { availableCellPx.toDp() })
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEach { day ->
                val active = day.date == selectedDate
                Column(
                    Modifier
                        .width(dayWidth)
                        .heightIn(min = 64.dp)
                        .pressable(
                            focusShape = AppShapes.thumb,
                            role = Role.Tab,
                            onClickLabel = "查看${day.date}排期",
                        ) { onSelect(day) }
                        .semantics { selected = active }
                        .then(
                            if (active) {
                                Modifier.glassSelection(AppShapes.thumb, selection)
                            } else {
                                Modifier
                            },
                        ).padding(horizontal = 4.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (day.date == today) "今天" else isoWeekdayLabel(day.date),
                        style = if (active) AppTypography.caption.strong else AppTypography.caption.medium,
                        color = if (active) selection.contentColor else palette.sub2,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        day.date.takeLast(2).trimStart('0'),
                        style = if (active) AppTypography.section.strong else AppTypography.section.medium,
                        color = if (active) selection.contentColor else palette.text,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
