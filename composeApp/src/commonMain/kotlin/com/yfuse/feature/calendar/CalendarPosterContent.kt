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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.shiftIsoDate
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

internal fun calendarFeaturedEntry(entries: List<CalendarDisplayEntry>): CalendarDisplayEntry? =
    entries.firstOrNull { it.entry.canOpenCalendarPlayback() }
        ?: entries.firstOrNull { it.entry.followed }
        ?: entries.firstOrNull()

internal fun CalendarEntry.canOpenCalendarPlayback(): Boolean =
    itemId != null && status in setOf(LibraryStatus.Available, LibraryStatus.InProgress, LibraryStatus.Watched)

internal fun calendarDateAfterFiltering(
    days: List<CalendarDay>,
    today: String,
    selectedDate: String,
    filter: CalendarFilter,
): String =
    when {
        filter == CalendarFilter.Today -> today
        filter == CalendarFilter.All || days.isEmpty() || days.any { it.date == selectedDate } -> selectedDate
        else -> days.firstOrNull { it.date >= today }?.date ?: days.last().date
    }

@Composable
internal fun CalendarPosterResults(
    days: List<CalendarDay>,
    today: String,
    selectedDate: String,
    onSelectDate: (String) -> Unit,
    bottomContentInset: Dp,
    onOpen: (CalendarEntry) -> Unit,
    onPlay: (CalendarEntry) -> Unit,
    onFollow: (CalendarEntry) -> Unit,
    backdrop: suspend (CalendarEntry) -> String?,
    liftMenu: (CalendarDisplayEntry) -> LiftMenu,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val entries =
        remember(days, selectedDate) {
            coalesceCalendarEntries(days.firstOrNull { it.date == selectedDate }?.entries.orEmpty())
        }
    val featured = calendarFeaturedEntry(entries)
    val rest = entries.filterNot { it === featured }
    val week = remember(days, selectedDate) { calendarWeekDays(days, selectedDate) }
    val firstWeek = currentWeekStart(minOf(days.minOfOrNull { it.date } ?: today, today))
    val lastWeek = currentWeekStart(maxOf(days.maxOfOrNull { it.date } ?: today, today))
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ChevronLeft,
                "上一周",
                tint = palette.sub,
                modifier =
                    Modifier
                        .pressable(enabled = week.first().date > firstWeek) {
                            onSelectDate(shiftIsoDate(selectedDate, -7))
                        }.touchTarget()
                        .padding(10.dp)
                        .size(16.dp),
            )
            Text(
                "${selectedDate.take(4)}年${selectedDate.substring(5, 7).toIntOrNull() ?: ""}月",
                style = AppTypography.caption.medium,
                color = palette.sub,
            )
            Text(
                "回到今天",
                style = AppTypography.caption.strong,
                color = accent.accent,
                modifier = Modifier.pressable { onSelectDate(today) }.touchTarget().padding(horizontal = 8.dp),
            )
            Icon(
                AppIcons.ChevronRight,
                "下一周",
                tint = palette.sub,
                modifier =
                    Modifier
                        .pressable(enabled = week.first().date < lastWeek) {
                            onSelectDate(shiftIsoDate(selectedDate, 7))
                        }.touchTarget()
                        .padding(10.dp)
                        .size(16.dp),
            )
        }
        CalendarWeekNavigator(week, today, selectedDate) { onSelectDate(it.date) }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding =
                PaddingValues(
                    start = Dimens.pageHorizontal,
                    end = Dimens.pageHorizontal,
                    bottom =
                        bottomContentInset + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (featured != null) {
                motionItem(key = "featured:${featured.entry.episode.mediaKey}") {
                    CalendarFeaturedPoster(featured, backdrop, {
                        onOpen(featured.entry)
                    }, { onPlay(featured.entry) }, { liftMenu(featured) })
                }
            } else {
                motionItem {
                    Text(
                        "这一天没有排期，试试其他日期。",
                        style = AppTypography.body.regular,
                        color = palette.sub,
                        modifier = Modifier.padding(vertical = 36.dp),
                    )
                }
            }
            if (rest.isNotEmpty()) {
                motionItem(key = "remaining-heading") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("更多更新", style = AppTypography.body.strong, color = palette.text)
                        Text("${rest.size}部", style = AppTypography.caption.regular, color = palette.sub)
                    }
                }
                rest.forEach { display ->
                    motionItem(key = display.entry.episode.mediaKey) {
                        CalendarPosterRow(
                            display,
                            { onOpen(display.entry) },
                            { onFollow(display.entry) },
                            { liftMenu(display) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarFeaturedPoster(
    display: CalendarDisplayEntry,
    resolveBackdrop: suspend (CalendarEntry) -> String?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    liftMenu: () -> LiftMenu,
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    var backdropPath by remember(entry.episode.titleKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.episode.titleKey) { backdropPath = resolveBackdrop(entry) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 210.dp)
            .clip(AppShapes.card)
            .background(palette.card)
            .liftable(menu = liftMenu, onOpen = onOpen)
            .pressable(onClickLabel = "查看${entry.episode.showTitle}排期", onClick = onOpen),
    ) {
        FallbackImage(
            urls =
                listOf(
                    TmdbImages.backdrop(backdropPath),
                    TmdbImages.media(backdropPath, "w780"),
                    TmdbImages.poster(entry.episode.posterPath),
                ) +
                    entry.posterUrls,
            contentDescription = null,
            fitNarrow = true,
            modifier = Modifier.matchParentSize(),
        )
        // The gradient and all foreground colours follow the current theme, including light mode.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to palette.background.copy(alpha = 0f),
                    0.55f to palette.background.copy(alpha = 0.9f),
                    1f to palette.background,
                ),
            ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .align(
                    Alignment.BottomStart,
                ).padding(start = 16.dp, end = 12.dp, top = 120.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    entry.episode.showTitle,
                    style = AppTypography.section.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    display.episodeLabel,
                    style = AppTypography.caption.medium,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    calendarPosterStatus(entry),
                    style = AppTypography.caption.strong,
                    color = if (entry.canOpenCalendarPlayback()) accent.accent else palette.sub,
                )
            }
            if (entry.canOpenCalendarPlayback()) {
                Icon(
                    AppIcons.Play,
                    "打开可播放内容",
                    tint = accent.onAccent,
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(accent.accent)
                            .pressable(focusShape = CircleShape, onClick = onPlay)
                            .padding(15.dp),
                )
            } else {
                Icon(AppIcons.ChevronRight, "查看排期", tint = palette.text, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun CalendarPosterRow(
    display: CalendarDisplayEntry,
    onOpen: () -> Unit,
    onFollow: () -> Unit,
    liftMenu: () -> LiftMenu,
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val posters =
        listOf(
            TmdbImages.poster(entry.episode.posterPath, "w185"),
            TmdbImages.media(entry.episode.posterPath, "w185"),
        ) +
            entry.posterUrls
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .liftable(
                    menu = { liftMenu().withArtwork(posters.filterNotNull()) },
                    onOpen = onOpen,
                ).pressable(onClick = onOpen)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FallbackImage(
                posters,
                null,
                Modifier
                    .width(58.dp)
                    .height(80.dp)
                    .clip(AppShapes.thumb)
                    .background(palette.card),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.episode.showTitle,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    display.episodeLabel,
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(calendarPosterStatus(entry), style = AppTypography.caption.regular, color = palette.sub)
            }
            if (entry.episode.canFollow) {
                Icon(
                    if (entry.followed) AppIcons.Check else AppIcons.Bell,
                    if (entry.followed) "取消追剧" else "追剧",
                    tint = accent.accent,
                    modifier =
                        Modifier
                            .pressable(onClick = onFollow)
                            .touchTarget()
                            .padding(12.dp)
                            .size(20.dp),
                )
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
    }
}

internal fun calendarPosterStatus(entry: CalendarEntry): String =
    listOfNotNull(
        broadcastTimeLabel(entry.episode) ?: "时间待定",
        when (entry.status) {
            LibraryStatus.Available -> "可播放"
            LibraryStatus.InProgress -> "继续观看"
            LibraryStatus.Watched -> "已看"
            LibraryStatus.Unaired -> "未播出"
            LibraryStatus.Missing -> "待入库"
            LibraryStatus.Unknown -> "待确认"
        },
    ).joinToString(" · ")
