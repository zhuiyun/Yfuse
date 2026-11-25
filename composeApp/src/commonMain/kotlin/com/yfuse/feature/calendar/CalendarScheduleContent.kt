package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
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
            .flatGlass(AppShapes.card, palette.card2, palette.border)
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

/** Single-row lightweight 7-day strip: zero subcomposition, never wraps into 2 rows, shows dot indicators. */
@Composable
internal fun CalendarWeekNavigator(
    days: List<CalendarDay>,
    today: String,
    selectedDate: String?,
    onSelect: (CalendarDay) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal, vertical = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { day ->
            val active = day.date == selectedDate
            val isToday = day.date == today
            val hasEntries = day.entries.isNotEmpty()
            val weekday = remember(day.date, isToday) {
                if (isToday) "今" else isoWeekdayLabel(day.date).removePrefix("周")
            }
            val dayNumber = remember(day.date) { day.date.takeLast(2).trimStart('0') }
            Column(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(AppShapes.thumb)
                    .then(
                        when {
                            active ->
                                Modifier
                                    .background(accent.accent)
                            isToday ->
                                Modifier
                                    .background(accent.accent.copy(alpha = if (palette.isDark) 0.16f else 0.10f))
                                    .border(0.75.dp, accent.accent.copy(alpha = 0.35f), AppShapes.thumb)
                            else ->
                                Modifier.background(
                                    palette.text.copy(alpha = if (palette.isDark) 0.05f else 0.035f),
                                )
                        },
                    ).pressable(
                        haptic = HapticSignal.Select,
                        focusShape = AppShapes.thumb,
                        role = Role.Tab,
                        onClickLabel = "查看${day.date}排期",
                    ) { onSelect(day) }
                    .semantics { selected = active }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    weekday,
                    style = AppTypography.caption.medium,
                    color = if (active) accent.onAccent.copy(alpha = 0.86f) else if (isToday) accent.accent else palette.sub2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    dayNumber,
                    style = if (active) AppTypography.body.strong else AppTypography.body.medium,
                    color = if (active) accent.onAccent else palette.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !hasEntries -> androidx.compose.ui.graphics.Color.Transparent
                                active -> accent.onAccent.copy(alpha = 0.90f)
                                else -> accent.accent.copy(alpha = 0.72f)
                            },
                        ),
                )
            }
        }
    }
}
