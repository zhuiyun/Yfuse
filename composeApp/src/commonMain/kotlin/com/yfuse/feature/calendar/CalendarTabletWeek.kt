package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.isToday
import com.yfuse.core.data.missingCount
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.util.isoShortDate
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.core.util.shiftIsoDate
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.flatGlass as glass

@Composable
internal fun TabletWeekCalendar(
    days: List<CalendarDay>,
    today: String,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onOpen: (CalendarEntry) -> Unit,
    liftMenu: (CalendarDisplayEntry) -> LiftMenu,
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
            val displayEntries = remember(day.entries) { coalesceCalendarEntries(day.entries) }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .glass(AppShapes.card, palette.card2, palette.border)
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
                        motionItems(
                            displayEntries,
                            key = { it.entry.episode.mediaKey },
                        ) { display ->
                            TabletWeekEntryCard(display, onOpen, liftMenu = { liftMenu(display) })
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
    liftMenu: (() -> LiftMenu)? = null,
) {
    val palette = LocalPalette.current
    val entry = display.entry
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.chip)
            .background(palette.card)
            .liftable(menu = liftMenu, onOpen = { onOpen(entry) })
            .pressable(onClickLabel = "打开${entry.episode.showTitle}播出日历") { onOpen(entry) }
            .padding(8.dp),
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
