package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.data.isPast
import com.yfuse.core.data.isToday
import com.yfuse.core.data.missingCount
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.flatGlass as glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.daysBetweenIso

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
    val palette = LocalPalette.current
    val days = state.visibleDays

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
                    modifier = Modifier
                        .size(36.dp)
                        .pressable(onClick = component.onBack)
                        .solidGlass(CircleShape, palette.card2, palette.border)
                        .padding(10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("追剧日历", style = sc(17f, 800), color = palette.text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "按原产地播出日期",
                        style = mr(10f, 400),
                        color = palette.sub2,
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CalendarFilter.entries) { filter ->
                    val active = filter == state.filter
                    Text(
                        filter.label,
                        style = sc(11.5f, 600),
                        color = if (active) Color.White else palette.body,
                        modifier = Modifier
                            .pressable {
                                component.store.accept(CalendarIntent.SelectFilter(filter))
                            }
                            .clip(GlassShapes.chip)
                            .background(if (active) Brand.Primary else Color.Transparent)
                            .then(
                                if (active) {
                                    Modifier
                                } else {
                                    Modifier.glass(GlassShapes.chip, palette.card2, palette.border)
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                state.loading && days.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Brand.Primary)
                }

                state.error != null && days.isEmpty() -> ErrorState(
                    message = state.error!!,
                    onRetry = { component.store.accept(CalendarIntent.Refresh) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                days.isEmpty() -> PageHint(
                    "这段时间没有查到在播剧集",
                    Modifier.align(Alignment.CenterHorizontally),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = TabBarInset),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(days, key = { it.date }) { day ->
                        DaySection(
                            day = day,
                            today = state.today,
                            onOpen = { entry ->
                                entry.itemId?.let { component.onOpenItem(entry.serverId, it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySection(day: CalendarDay, today: String, onOpen: (CalendarEntry) -> Unit) {
    val palette = LocalPalette.current
    val isToday = day.isToday(today)
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
                style = sc(14f, 800),
                color = if (isToday) Brand.Primary else palette.text,
            )
            Text(day.date, style = mr(10f, 400), color = palette.sub2, modifier = Modifier.weight(1f))
            // Only stated when there is something to act on; a zero would be noise on
            // every other row.
            if (day.missingCount > 0 && day.isPast(today) || day.missingCount > 0 && isToday) {
                Text(
                    "${day.missingCount} 集未入库",
                    style = mr(10f, 600),
                    color = Brand.Danger,
                )
            }
        }
        Column(
            Modifier.padding(horizontal = Dimens.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            day.entries.forEach { entry ->
                EntryRow(entry = entry, onOpen = { onOpen(entry) })
            }
        }
    }
}

/** `今天` / `明天` / `3 天前` — a date alone makes the reader do the arithmetic. */
private fun dayLabel(date: String, today: String): String = when (val delta = daysBetweenIso(today, date)) {
    0 -> "今天"
    1 -> "明天"
    2 -> "后天"
    -1 -> "昨天"
    else -> if (delta > 0) "$delta 天后" else "${-delta} 天前"
}

@Composable
private fun EntryRow(entry: CalendarEntry, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val playable = entry.itemId != null
    Row(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapes.card, palette.card2, palette.border)
            .then(if (playable) Modifier.pressable(onClick = onOpen) else Modifier)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FallbackImage(
            urls = listOf(
                TmdbImages.poster(entry.episode.posterPath, width = "w185"),
                TmdbImages.media(entry.episode.posterPath, width = "w185"),
            ),
            contentDescription = null,
            modifier = Modifier
                .width(42.dp)
                .height(60.dp)
                .clip(GlassShapes.thumb)
                .background(palette.card2),
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.episode.showTitle,
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                entry.episode.episodeLabel,
                style = mr(10.5f, 400),
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            StatusBadge(entry.status)
        }
        if (playable) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.sub2,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun StatusBadge(status: LibraryStatus) {
    val palette = LocalPalette.current
    val (label, tint) = when (status) {
        LibraryStatus.Unaired -> "未播出" to palette.sub2
        LibraryStatus.Missing -> "未入库" to Brand.Danger
        LibraryStatus.Available -> "可播放" to Brand.Online
        LibraryStatus.Watched -> "已看" to palette.sub2
        LibraryStatus.Unknown -> "未知" to palette.sub2
    }
    Text(
        label,
        style = mr(9.5f, 700),
        color = tint,
        modifier = Modifier
            .clip(GlassShapes.chip)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
