package com.yfuse.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.artworkPageSurface
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoShortDate
import com.yfuse.core.util.isoWeekdayLabel

private val DialogMaximumHeight = 740.dp
private val DialogHeroHeight = 206.dp
private val EpisodeRowMinimumHeight = 58.dp
private val DialogFallbackArtwork = Color(0xFF27384B)

private enum class ReminderTiming(
    val label: String,
) {
    Off("提醒关闭"),
    Before("提前30分钟"),
    Airing("播出时"),
    Added("新入库时"),
}

internal data class AiringShowDay(
    val date: String,
    val entries: List<CalendarEntry>,
)

internal fun airingShowDays(
    days: List<CalendarDay>,
    showTmdbId: Int,
): List<AiringShowDay> =
    days
        .mapNotNull { day ->
            day.entries
                .filter { it.episode.showTmdbId == showTmdbId }
                .takeIf { it.isNotEmpty() }
                ?.let { AiringShowDay(day.date, it) }
        }.sortedBy(AiringShowDay::date)

internal fun airingDateWindow(
    dates: List<String>,
    selectedDate: String,
    windowSize: Int = 3,
): List<String> {
    if (dates.size <= windowSize) return dates
    val selected = dates.indexOf(selectedDate).coerceAtLeast(0)
    val start = (selected - windowSize / 2).coerceIn(0, dates.size - windowSize)
    return dates.subList(start, start + windowSize)
}

/** Immersive, poster-coloured schedule for one title. */
@Composable
internal fun AiringShowCalendarDialog(
    initialEntry: CalendarEntry,
    days: List<CalendarDay>,
    today: String,
    refreshing: Boolean,
    onOpen: (CalendarEntry) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val showId = initialEntry.episode.showTmdbId
    val showDays = remember(days, showId) { airingShowDays(days, showId) }
    if (showDays.isEmpty()) return

    var selectedDate by rememberSaveable(showId) {
        mutableStateOf(initialEntry.episode.airDate)
    }
    var reminderIndex by rememberSaveable(showId) { mutableIntStateOf(ReminderTiming.Off.ordinal) }
    var reminderExpanded by rememberSaveable(showId) { mutableStateOf(false) }
    LaunchedEffect(showDays) {
        if (showDays.none { it.date == selectedDate }) selectedDate = showDays.first().date
    }

    val allEntries = remember(showDays) { showDays.flatMap(AiringShowDay::entries) }
    val selectedDay = showDays.firstOrNull { it.date == selectedDate } ?: showDays.first()
    val title = initialEntry.episode.showTitle
    val posterPath = initialEntry.episode.posterPath
    val artworkUrls =
        remember(posterPath) {
            listOf(
                TmdbImages.poster(posterPath, width = "w780"),
                TmdbImages.media(posterPath, width = "w780"),
            )
        }
    val artworkUrl = artworkUrls.firstOrNull { !it.isNullOrBlank() }
    val sampledArtwork = rememberDominantColor(artworkUrl, DialogFallbackArtwork)
    // The poster decides whether this is a dark or light surface. App/system theme is not read.
    val darkArtwork = sampledArtwork.luminance() < 0.38f
    val dialogBackground = remember(sampledArtwork, darkArtwork) {
        artworkPageSurface(sampledArtwork, darkTheme = darkArtwork)
    }

    ArtworkPageTheme(
        background = dialogBackground,
        artworkAccent = sampledArtwork,
    ) {
        GlassDialog(
            onDismiss = onDismiss,
            modifier = Modifier.heightIn(max = DialogMaximumHeight),
            scrollable = false,
            liquidButtons = false,
            contentPadding = 0.dp,
        ) {
            val palette = LocalPalette.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(GlassShapes.card)
                    .background(palette.background)
                    .verticalScroll(rememberScrollState()),
            ) {
                DialogHero(
                    title = title,
                    subtitle = dialogSubtitle(allEntries),
                    artworkUrls = artworkUrls,
                    background = palette.background,
                    onDismiss = onDismiss,
                )
                CalendarQuickActions(
                    tracked = allEntries.any(CalendarEntry::inLibrary),
                    reminder = ReminderTiming.entries[reminderIndex],
                    reminderExpanded = reminderExpanded,
                    refreshing = refreshing,
                    onTracking = {
                        allEntries.firstOrNull { it.seriesItemId != null || it.itemId != null }?.let(onOpen)
                    },
                    onReminder = { reminderExpanded = !reminderExpanded },
                    onRefresh = onRefresh,
                )
                if (reminderExpanded) {
                    ReminderOptions(
                        selected = ReminderTiming.entries[reminderIndex],
                        onSelect = { timing ->
                            reminderIndex = timing.ordinal
                            reminderExpanded = false
                        },
                    )
                }
                DialogDateNavigation(
                    days = showDays,
                    selectedDate = selectedDay.date,
                    onSelected = { selectedDate = it },
                )
                DialogEpisodePanel(
                    selectedDay = selectedDay,
                    firstDay = showDays.first(),
                    today = today,
                    onOpen = onOpen,
                    onSelectFirst = { selectedDate = showDays.first().date },
                )
            }
        }
    }
}

@Composable
private fun DialogHero(
    title: String,
    subtitle: String,
    artworkUrls: List<String?>,
    background: Color,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DialogHeroHeight)
            .background(background),
    ) {
        FallbackImage(
            urls = artworkUrls,
            contentDescription = "$title 海报",
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.52f to background.copy(alpha = 0.18f),
                        1f to background,
                    ),
                ),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "关闭播出日历",
            tint = Color.White,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(15.dp)
                    .pressable(onClick = onDismiss)
                    .touchTarget()
                    .size(38.dp)
                    .flatGlass(
                        CircleShape,
                        Color.Black.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.22f),
                    ).padding(10.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                title,
                style = AppTypography.display.strong,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                subtitle,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun dialogSubtitle(entries: List<CalendarEntry>): String {
    val weekdays = entries.map { isoWeekdayLabel(it.episode.airDate) }.filter(String::isNotBlank).distinct()
    val servers = entries.flatMap(CalendarEntry::serverNames).distinct()
    return buildList {
        add("${entries.size}集")
        weekdays.take(2).takeIf { it.isNotEmpty() }?.joinToString("/")?.let(::add)
        servers.take(2).takeIf { it.isNotEmpty() }?.joinToString("/")?.let(::add)
    }.joinToString(" · ")
}

@Composable
private fun CalendarQuickActions(
    tracked: Boolean,
    reminder: ReminderTiming,
    reminderExpanded: Boolean,
    refreshing: Boolean,
    onTracking: () -> Unit,
    onReminder: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(72.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarQuickAction(
            icon = AppIcons.Check,
            label = if (tracked) "已追剧" else "未追剧",
            active = tracked,
            onClick = onTracking,
        )
        QuickActionDivider()
        CalendarQuickAction(
            icon = AppIcons.Bell,
            label = reminder.label,
            active = reminder != ReminderTiming.Off || reminderExpanded,
            onClick = onReminder,
        )
        QuickActionDivider()
        CalendarQuickAction(
            icon = AppIcons.Refresh,
            label = if (refreshing) "匹配中" else "重新匹配",
            active = false,
            enabled = !refreshing,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun RowScope.CalendarQuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val content = if (active) accent.accent else palette.text
    Column(
        Modifier
            .weight(1f)
            .height(72.dp)
            .pressable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = AppTypography.caption.strong,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickActionDivider() {
    val palette = LocalPalette.current
    Box(Modifier.width(1.dp).height(32.dp).background(palette.border))
}

@Composable
private fun ReminderOptions(
    selected: ReminderTiming,
    onSelect: (ReminderTiming) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "更新提醒",
            style = AppTypography.caption.strong,
            color = palette.sub,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        ReminderTiming.entries.forEach { timing ->
            val active = timing == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(role = Role.RadioButton) { onSelect(timing) }
                    .semantics { this.selected = active }
                    .clip(GlassShapes.chip)
                    .background(if (active) accent.container else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    timing.label,
                    style = AppTypography.body.medium,
                    color = if (active) accent.accent else palette.text,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = accent.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogDateNavigation(
    days: List<AiringShowDay>,
    selectedDate: String,
    onSelected: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val dates = remember(days) { days.map(AiringShowDay::date) }
    val selectedIndex = dates.indexOf(selectedDate).coerceAtLeast(0)
    val visibleDates = airingDateWindow(dates, selectedDate)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            AppIcons.ChevronLeft,
            contentDescription = "前一个播出日",
            tint = if (selectedIndex > 0) palette.sub else palette.hint.copy(alpha = 0.45f),
            modifier =
                Modifier
                    .pressable(enabled = selectedIndex > 0) { onSelected(dates[selectedIndex - 1]) }
                    .touchTarget()
                    .size(36.dp)
                    .padding(10.dp),
        )
        visibleDates.forEach { date ->
            DialogDateChip(
                date = date,
                active = date == selectedDate,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(date) },
            )
        }
        repeat((3 - visibleDates.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = "后一个播出日",
            tint = if (selectedIndex < dates.lastIndex) palette.sub else palette.hint.copy(alpha = 0.45f),
            modifier =
                Modifier
                    .pressable(enabled = selectedIndex < dates.lastIndex) { onSelected(dates[selectedIndex + 1]) }
                    .touchTarget()
                    .size(36.dp)
                    .padding(10.dp),
        )
    }
}

@Composable
private fun DialogDateChip(
    date: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(
        modifier
            .defaultMinSize(minHeight = 54.dp)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = active }
            .flatGlass(
                GlassShapes.chip,
                if (active) accent.container else palette.card2,
                if (active) accent.border else palette.border,
            ).padding(horizontal = 5.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            isoShortDate(date).replace('-', '.'),
            style = AppTypography.body.strong,
            color = if (active) accent.accent else palette.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            isoWeekdayLabel(date),
            style = AppTypography.caption.regular,
            color = if (active) accent.accent else palette.sub2,
            maxLines = 1,
        )
    }
}

@Composable
private fun DialogEpisodePanel(
    selectedDay: AiringShowDay,
    firstDay: AiringShowDay,
    today: String,
    onOpen: (CalendarEntry) -> Unit,
    onSelectFirst: () -> Unit,
) {
    val palette = LocalPalette.current
    val playable = selectedDay.entries.count { it.itemId != null }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                relativeDayLabel(selectedDay.date, today),
                style = AppTypography.body.strong,
                color = LocalAccentColors.current.accent,
            )
            Text(
                " · ${selectedDay.date}",
                style = AppTypography.caption.regular,
                color = palette.sub2,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (playable > 0) {
                    "${selectedDay.entries.size}集 · ${playable}集可播放"
                } else {
                    "${selectedDay.entries.size}集"
                },
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
        }
        selectedDay.entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            DialogEpisodeRow(entry = entry, onOpen = { onOpen(entry) })
        }
        if (selectedDay.date != firstDay.date) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pressable(onClick = onSelectFirst)
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = palette.sub,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "查看 ${isoShortDate(firstDay.date)} · ${firstDay.entries.size}集",
                    style = AppTypography.caption.medium,
                    color = palette.sub,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.sub2,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun DialogEpisodeRow(
    entry: CalendarEntry,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val canOpen = entry.openItemId != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = EpisodeRowMinimumHeight)
            .pressable(enabled = canOpen, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (entry.episode.isMovie) "电影" else "第${entry.episode.episodeNumber}集",
            style = AppTypography.caption.strong,
            color = palette.text,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.episode.episodeTitle?.takeIf(String::isNotBlank) ?: entry.episode.showTitle,
                style = AppTypography.caption.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                episodeMeta(entry),
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.itemId != null) {
            Row(
                Modifier
                    .pressable(onClick = onOpen)
                    .touchTarget()
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(19.dp)
                        .border(1.dp, accent.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Play,
                        contentDescription = null,
                        tint = accent.accent,
                        modifier = Modifier.size(9.dp),
                    )
                }
                Text(
                    if (entry.status == LibraryStatus.InProgress) "继续" else "可播放",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                )
            }
        } else {
            Text(
                dialogStatusLabel(entry.status),
                style = AppTypography.caption.strong,
                color = if (entry.status == LibraryStatus.Missing) palette.error else palette.sub2,
                maxLines = 1,
            )
        }
    }
}

private fun episodeMeta(entry: CalendarEntry): String =
    buildList {
        entry.serverNames.firstOrNull()?.let(::add)
        when (entry.status) {
            LibraryStatus.Watched -> add("已看完")
            LibraryStatus.InProgress -> entry.playedPercentage?.let { add("已看 ${it.toInt()}%") }
            LibraryStatus.Available -> add("已入库")
            LibraryStatus.Missing -> add("等待入库")
            LibraryStatus.Unaired -> add("等待播出")
            LibraryStatus.Unknown -> add("状态未知")
        }
    }.distinct().joinToString(" · ")

private fun dialogStatusLabel(status: LibraryStatus): String =
    when (status) {
        LibraryStatus.Watched -> "已看"
        LibraryStatus.InProgress -> "观看中"
        LibraryStatus.Available -> "可播放"
        LibraryStatus.Missing -> "未入库"
        LibraryStatus.Unaired -> "待播"
        LibraryStatus.Unknown -> "未知"
    }

private fun relativeDayLabel(
    date: String,
    today: String,
): String =
    when (val delta = daysBetweenIso(today, date)) {
        0 -> "今天"
        1 -> "明天"
        -1 -> "昨天"
        else -> if (delta > 0) "${delta}天后" else "${-delta}天前"
    }
