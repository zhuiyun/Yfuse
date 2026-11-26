package com.yfuse.feature.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.isoWeekdayLabel
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
    val haptics = LocalHaptics.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val daysByDate = remember(days) { days.associateBy(CalendarDay::date) }
    val entries =
        remember(daysByDate, selectedDate) {
            coalesceCalendarEntries(daysByDate[selectedDate]?.entries.orEmpty())
        }
    val week = remember(days, selectedDate) { calendarWeekDays(days, selectedDate) }
    val firstWeek = remember(days, today) { currentWeekStart(minOf(days.minOfOrNull { it.date } ?: today, today)) }
    val lastWeek = remember(days, today) { currentWeekStart(maxOf(days.maxOfOrNull { it.date } ?: today, today)) }
    val minDate = firstWeek
    val maxDate = remember(lastWeek) { shiftIsoDate(lastWeek, 6) }
    val selectDateWithHaptic: (String) -> Unit = { target ->
        if (target != selectedDate) {
            haptics.play(HapticSignal.Select)
            onSelectDate(target)
        }
    }
    val dateSummary =
        remember(selectedDate, today, entries.size) {
            val month = selectedDate.substring(5, 7).toIntOrNull()?.toString().orEmpty()
            val day = selectedDate.takeLast(2).toIntOrNull()?.toString().orEmpty()
            "${month}月${day}日 · ${isoWeekdayLabel(selectedDate)} · ${dayLabel(selectedDate, today)} · ${entries.size}部"
        }

    Column(Modifier.fillMaxSize()) {
        // Compact single-line week control + date readout (replaces two stacked legacy bars).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .pressable(enabled = week.first().date > firstWeek, focusShape = CircleShape) {
                        onSelectDate(shiftIsoDate(selectedDate, -7))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    "上一周",
                    tint = if (week.first().date > firstWeek) palette.sub else palette.hint.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                dateSummary,
                style = AppTypography.caption.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selectedDate != today) {
                Text(
                    "今天",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .clip(AppShapes.pill)
                            .background(accent.accent.copy(alpha = if (palette.isDark) 0.18f else 0.10f))
                            .pressable(focusShape = AppShapes.pill) { onSelectDate(today) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .pressable(enabled = week.first().date < lastWeek, focusShape = CircleShape) {
                        onSelectDate(shiftIsoDate(selectedDate, 7))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.ChevronRight,
                    "下一周",
                    tint = if (week.first().date < lastWeek) palette.sub else palette.hint.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        CalendarWeekNavigator(week, today, selectedDate) { selectDateWithHaptic(it.date) }
        AnimatedContent(
            targetState = selectedDate,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(selectedDate, minDate, maxDate) {
                        var accumulated = 0f
                        val thresholdPx = 72.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f },
                            onDragEnd = {
                                if (accumulated <= -thresholdPx) {
                                    val next = shiftIsoDate(selectedDate, 1)
                                    if (next <= maxDate) selectDateWithHaptic(next)
                                } else if (accumulated >= thresholdPx) {
                                    val prev = shiftIsoDate(selectedDate, -1)
                                    if (prev >= minDate) selectDateWithHaptic(prev)
                                }
                                accumulated = 0f
                            },
                            onHorizontalDrag = { _, dragAmount -> accumulated += dragAmount },
                        )
                    },
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(Motion.tween(Motion.QUICK)) togetherWith fadeOut(Motion.tween(Motion.QUICK))
                } else {
                    val forward = targetState > initialState
                    val enter =
                        slideInHorizontally(Motion.tween(Motion.STANDARD)) { full ->
                            if (forward) full / 4 else -full / 4
                        } + fadeIn(Motion.tween(Motion.STANDARD))
                    val exit =
                        slideOutHorizontally(Motion.tween(Motion.QUICK)) { full ->
                            if (forward) -full / 5 else full / 5
                        } + fadeOut(Motion.tween(Motion.QUICK))
                    enter togetherWith exit
                }
            },
            label = "calendarDaySwipe",
        ) { activeDate ->
            val dayEntries =
                remember(daysByDate, activeDate) {
                    coalesceCalendarEntries(daysByDate[activeDate]?.entries.orEmpty())
                }
            val featured = remember(dayEntries) { calendarFeaturedEntry(dayEntries) }
            val rest = remember(dayEntries, featured) { dayEntries.filterNot { it === featured } }
            val wideCalendar = com.yfuse.app.LocalWideWindow.current
            if (wideCalendar && featured != null) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = Dimens.pageHorizontal,
                            end = Dimens.pageHorizontal,
                            top = 4.dp,
                            bottom = bottomContentInset + 16.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.weight(0.46f)) {
                        CalendarFeaturedPoster(
                            display = featured,
                            resolveBackdrop = backdrop,
                            onOpen = { onOpen(featured.entry) },
                            onPlay = { onPlay(featured.entry) },
                        )
                    }
                    LazyColumn(
                        Modifier.weight(0.54f).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (rest.isNotEmpty()) {
                            item(key = "remaining-heading", contentType = "calendar-heading") {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("当日更多", style = AppTypography.body.strong, color = palette.text)
                                    Text("${rest.size} 部", style = AppTypography.caption.medium, color = palette.sub)
                                }
                            }
                            items(
                                items = rest,
                                key = { it.entry.episode.mediaKey },
                                contentType = { "calendar-row" },
                            ) { display ->
                                CalendarPosterRow(
                                    display = display,
                                    onOpen = { onOpen(display.entry) },
                                    onFollow = { onFollow(display.entry) },
                                )
                            }
                        } else {
                            item(key = "calendar-wide-single") {
                                Text(
                                    "今日仅有左侧一部排期更新。",
                                    style = AppTypography.body.regular,
                                    color = palette.sub,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = Dimens.pageHorizontal,
                            end = Dimens.pageHorizontal,
                            top = 4.dp,
                            bottom = bottomContentInset + 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (featured != null) {
                        item(key = "featured:${featured.entry.episode.mediaKey}", contentType = "calendar-featured") {
                            CalendarFeaturedPoster(
                                display = featured,
                                resolveBackdrop = backdrop,
                                onOpen = { onOpen(featured.entry) },
                                onPlay = { onPlay(featured.entry) },
                            )
                        }
                    } else {
                        item(key = "calendar-empty", contentType = "calendar-empty") {
                            Text(
                                "这一天没有排期，左右滑动或点选其他日期。",
                                style = AppTypography.body.regular,
                                color = palette.sub,
                                modifier = Modifier.padding(vertical = 36.dp),
                            )
                        }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "remaining-heading", contentType = "calendar-heading") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("当日更多", style = AppTypography.body.strong, color = palette.text)
                                Text("${rest.size} 部", style = AppTypography.caption.medium, color = palette.sub)
                            }
                        }
                        items(
                            items = rest,
                            key = { it.entry.episode.mediaKey },
                            contentType = { "calendar-row" },
                        ) { display ->
                            CalendarPosterRow(
                                display = display,
                                onOpen = { onOpen(display.entry) },
                                onFollow = { onFollow(display.entry) },
                            )
                        }
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
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    var backdropPath by remember(entry.episode.titleKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.episode.titleKey) { backdropPath = resolveBackdrop(entry) }
    val urls =
        remember(backdropPath, entry.episode.posterPath, entry.posterUrls) {
            listOf(
                TmdbImages.backdrop(backdropPath),
                TmdbImages.media(backdropPath, "w780"),
                TmdbImages.poster(entry.episode.posterPath),
            ) + entry.posterUrls
        }
    val statusText = remember(entry) { calendarPosterStatus(entry) }
    val cardBorder = palette.text.copy(alpha = if (palette.isDark) 0.14f else 0.08f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(AppShapes.card)
            .background(palette.card3)
            .border(0.75.dp, cardBorder, AppShapes.card)
            .pressable(onClickLabel = "查看${entry.episode.showTitle}排期", focusShape = AppShapes.card, onClick = onOpen),
    ) {
        FallbackImage(
            urls = urls,
            contentDescription = null,
            fitNarrow = true,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.26f to HeroInk.copy(alpha = 0.14f),
                    0.56f to HeroInk.copy(alpha = 0.56f),
                    0.82f to HeroInk.copy(alpha = 0.82f),
                    1f to HeroInk.copy(alpha = 0.92f),
                ),
            ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.episode.showTitle,
                    style = AppTypography.section.strong.copy(shadow = HeroTextShadow),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${display.episodeLabel} · $statusText",
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.canOpenCalendarPlayback()) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accent.accent)
                        .pressable(focusShape = CircleShape, onClickLabel = "打开可播放内容", onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Play, null, tint = accent.onAccent, modifier = Modifier.size(16.dp))
                }
            } else {
                Icon(AppIcons.ChevronRight, "查看排期", tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CalendarPosterRow(
    display: CalendarDisplayEntry,
    onOpen: () -> Unit,
    onFollow: () -> Unit,
) {
    val entry = display.entry
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val cardSurface = palette.card.compositeOver(palette.background)
    val cardBorder = palette.text.copy(alpha = if (palette.isDark) 0.11f else 0.07f)
    val posters =
        remember(entry.episode.posterPath, entry.posterUrls) {
            listOf(
                TmdbImages.poster(entry.episode.posterPath, "w185"),
                TmdbImages.media(entry.episode.posterPath, "w185"),
            ) + entry.posterUrls
        }
    val statusText = remember(entry) { calendarPosterStatus(entry) }
    val playable = entry.canOpenCalendarPlayback()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(cardSurface)
            .border(0.75.dp, cardBorder, AppShapes.card)
            .pressable(focusShape = AppShapes.card, onClickLabel = "查看${entry.episode.showTitle}排期", onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FallbackImage(
            posters,
            null,
            Modifier
                .width(50.dp)
                .height(72.dp)
                .clip(AppShapes.thumb)
                .background(palette.card3),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.episode.showTitle,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                display.episodeLabel,
                style = AppTypography.caption.medium,
                color = if (playable) accent.accent else palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusText,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.episode.canFollow) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.followed) {
                            accent.accent.copy(alpha = if (palette.isDark) 0.22f else 0.12f)
                        } else {
                            palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.05f)
                        },
                    ).pressable(focusShape = CircleShape, onClickLabel = if (entry.followed) "取消追剧" else "追剧", onClick = onFollow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.followed) AppIcons.Check else AppIcons.Bell,
                    null,
                    tint = if (entry.followed) accent.accent else palette.sub,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
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
