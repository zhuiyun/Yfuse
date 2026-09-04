package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
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
import com.yfuse.core.designsystem.resolveAccentColors
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoWeekdayLabel
import kotlin.math.abs

private val SeriesCalendarHeroHeight = 148.dp
private val SeriesCalendarFallbackArtwork = Color(0xFFDAD4E8)
private val SeriesCalendarTeal = Color(0xFF147E79)
private val SeriesCalendarCoral = Color(0xFFC96662)
private val SeriesCalendarAmber = Color(0xFFC4872E)
private val SeriesCalendarPlum = Color(0xFF76527E)
private val SeriesCalendarEmerald = Color(0xFF238963)
private val SeriesCalendarLavender = Color(0xFF9582B3)
private val SeriesCalendarMinutes = listOf(10, 30, 60, 120, 360)

private data class SeriesScheduleInfo(
    val subtitle: String,
    val sourceUrl: String?,
    val sourceDescription: String?,
)

/**
 * Compact schedule sheet for a series. The detail backdrop is the visual source; the poster
 * remains its fallback. No calendar-specific bitmap is bundled with the application.
 */
@Composable
internal fun SeriesAiringCalendarDialog(
    title: String,
    days: List<CalendarDay>,
    loading: Boolean,
    error: String?,
    artworkUrls: List<String?> = emptyList(),
    artworkColorUrl: String? = null,
    identityCandidates: List<TmdbSeriesIdentityCandidate> = emptyList(),
    followed: Boolean = false,
    reminderMode: CalendarReminderMode = CalendarReminderMode.Off,
    remindBeforeMinutes: Int = 30,
    onSelectIdentity: (TmdbSeriesIdentityCandidate) -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onSetReminder: (CalendarReminderMode, Int) -> Unit = { _, _ -> },
    onRebindIdentity: () -> Unit = {},
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val today = currentIsoDate()
    val uriHandler = LocalUriHandler.current
    val scheduleInfo = remember(days) { seriesScheduleInfo(days) }
    val candidates =
        remember(artworkUrls) {
            artworkUrls.filterNotNull().filter(String::isNotBlank).distinct()
        }
    var resolvedArtworkUrl by remember(candidates) { mutableStateOf<String?>(null) }
    val sampledArtwork =
        rememberDominantColor(
            resolvedArtworkUrl ?: artworkColorUrl ?: candidates.firstOrNull(),
            SeriesCalendarFallbackArtwork,
        )
    val darkArtwork = sampledArtwork.luminance() < 0.38f
    val dialogBackground =
        remember(sampledArtwork, darkArtwork) {
            artworkPageSurface(sampledArtwork, darkTheme = darkArtwork)
        }

    val dates = remember(days) { days.map(CalendarDay::date) }
    var selectedDate by remember(dates, today) {
        mutableStateOf(seriesCalendarInitialDate(dates, today))
    }
    var reminderExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(dates, today) {
        if (selectedDate !in dates) selectedDate = seriesCalendarInitialDate(dates, today)
    }
    LaunchedEffect(followed) {
        if (!followed) reminderExpanded = false
    }

    ArtworkPageTheme(
        background = dialogBackground,
        artworkAccent = sampledArtwork,
    ) {
        GlassDialog(
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxHeight(0.82f),
            scrollable = false,
            liquidButtons = false,
            contentPadding = 0.dp,
            alignment = Alignment.BottomCenter,
            windowPadding = PaddingValues(start = 12.dp, top = 72.dp, end = 12.dp, bottom = 0.dp),
            shape = GlassShapes.sheet,
        ) {
            val palette = LocalPalette.current
            val lavender = resolveAccentColors(SeriesCalendarLavender, palette.isDark)
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(GlassShapes.sheet)
                    .background(
                        Brush.verticalGradient(
                            0f to palette.background,
                            1f to lerp(palette.background, lavender.container, 0.48f),
                        ),
                    ),
            ) {
                SeriesCalendarHero(
                    title = title,
                    subtitle = scheduleInfo.subtitle,
                    artworkUrls = candidates,
                    onResolvedUrl = { resolvedArtworkUrl = it },
                    onDismiss = onDismiss,
                )

                if (identityCandidates.isNotEmpty()) {
                    SeriesIdentityCandidates(
                        candidates = identityCandidates,
                        onSelect = onSelectIdentity,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SeriesCalendarSummaryControls(
                        followed = followed,
                        reminderMode = reminderMode,
                        remindBeforeMinutes = remindBeforeMinutes,
                        reminderExpanded = reminderExpanded,
                        onToggleFollow = onToggleFollow,
                        onToggleReminder = { reminderExpanded = !reminderExpanded },
                    )
                    if (reminderExpanded && followed) {
                        SeriesReminderPicker(
                            selected = reminderMode,
                            beforeMinutes = remindBeforeMinutes,
                            onSelect = { mode ->
                                onSetReminder(mode, remindBeforeMinutes)
                                if (mode != CalendarReminderMode.BeforeAndAtBroadcast) {
                                    reminderExpanded = false
                                }
                            },
                            onMinutes = { minutes ->
                                onSetReminder(CalendarReminderMode.BeforeAndAtBroadcast, minutes)
                                reminderExpanded = false
                            },
                        )
                    }
                    if (error != null && days.isNotEmpty()) {
                        SeriesCalendarInlineNotice(message = error, onRetry = onRetry)
                    }
                    if (days.isNotEmpty()) {
                        SeriesCalendarDateNavigation(
                            days = days,
                            selectedDate = selectedDate,
                            onSelected = { selectedDate = it },
                        )
                    }
                    SeriesCalendarEpisodeContent(
                        days = days,
                        selectedDate = selectedDate,
                        today = today,
                        loading = loading,
                        error = error,
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                    SeriesCalendarFooter(
                        sourceUrl = scheduleInfo.sourceUrl,
                        sourceDescription = scheduleInfo.sourceDescription,
                        onOpenSource =
                            scheduleInfo.sourceUrl?.let { url ->
                                { runCatching { uriHandler.openUri(url) } }
                            },
                        onRebindIdentity = onRebindIdentity,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesCalendarHero(
    title: String,
    subtitle: String,
    artworkUrls: List<String>,
    onResolvedUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(SeriesCalendarHeroHeight)
            .background(
                Brush.linearGradient(
                    listOf(SeriesCalendarPlum, SeriesCalendarTeal, SeriesCalendarCoral),
                ),
            ),
    ) {
        if (artworkUrls.isNotEmpty()) {
            FallbackImage(
                urls = artworkUrls,
                contentDescription = "$title 背景图",
                modifier =
                    Modifier
                        .matchParentSize()
                        .blur(10.dp)
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                        },
                progressive = false,
                alphaOnly = true,
                onResolvedUrl = onResolvedUrl,
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.48f to SeriesCalendarPlum.copy(alpha = 0.24f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.72f)),
        )
        Icon(
            AppIcons.Close,
            contentDescription = "关闭播出日历",
            tint = Color.White,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .pressable(onClick = onDismiss)
                    .touchTarget()
                    .size(36.dp)
                    .flatGlass(
                        CircleShape,
                        Color.Black.copy(alpha = 0.34f),
                        Color.White.copy(alpha = 0.24f),
                    ).padding(9.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 62.dp, bottom = 15.dp),
        ) {
            Text(
                "$title · 播出日历",
                style = AppTypography.section.strong,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SeriesCalendarSummaryControls(
    followed: Boolean,
    reminderMode: CalendarReminderMode,
    remindBeforeMinutes: Int,
    reminderExpanded: Boolean,
    onToggleFollow: () -> Unit,
    onToggleReminder: () -> Unit,
) {
    val palette = LocalPalette.current
    val follow = resolveAccentColors(SeriesCalendarTeal, palette.isDark)
    val reminder = resolveAccentColors(SeriesCalendarCoral, palette.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 66.dp)
                .pressable(role = Role.Switch, onClick = onToggleFollow)
                .semantics { stateDescription = if (followed) "已加入追剧" else "未加入追剧" }
                .flatGlass(
                    GlassShapes.card,
                    lerp(palette.card2, follow.container, 0.72f),
                    follow.border.copy(alpha = 0.55f),
                ).padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (followed) "已加入追剧" else "加入追剧",
                    style = AppTypography.caption.strong,
                    color = follow.accent,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (followed) "追剧中心优先显示" else "关注排期和入库",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SeriesTogglePill(checked = followed, activeColor = follow.accent)
        }

        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 66.dp)
                .pressable(enabled = followed, role = Role.Button, onClick = onToggleReminder)
                .flatGlass(
                    GlassShapes.card,
                    lerp(palette.card2, reminder.container, if (followed) 0.72f else 0.22f),
                    if (followed) reminder.border.copy(alpha = 0.55f) else palette.border,
                ).padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                AppIcons.Bell,
                contentDescription = null,
                tint = if (followed) reminder.accent else palette.hint,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "更新提醒",
                    style = AppTypography.caption.strong,
                    color = if (followed) reminder.accent else palette.hint,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (followed) reminderModeLabel(reminderMode, remindBeforeMinutes) else "先加入追剧",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                AppIcons.ChevronDown,
                contentDescription = if (reminderExpanded) "收起提醒方式" else "选择提醒方式",
                tint = if (followed) reminder.accent else palette.hint,
                modifier =
                    Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = if (reminderExpanded) 180f else 0f },
            )
        }
    }
}

@Composable
private fun SeriesTogglePill(
    checked: Boolean,
    activeColor: Color,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(if (checked) activeColor else palette.card3)
            .border(1.dp, if (checked) activeColor else palette.border, CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else palette.sub2),
        )
    }
}

@Composable
private fun SeriesReminderPicker(
    selected: CalendarReminderMode,
    beforeMinutes: Int,
    onSelect: (CalendarReminderMode) -> Unit,
    onMinutes: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val reminder = resolveAccentColors(SeriesCalendarCoral, palette.isDark)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CalendarReminderMode.entries.forEach { mode ->
            val active = mode == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(role = Role.RadioButton) { onSelect(mode) }
                    .semantics { this.selected = active }
                    .clip(GlassShapes.chip)
                    .background(if (active) reminder.container else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    reminderModeLabel(mode, beforeMinutes),
                    style = AppTypography.caption.medium,
                    color = if (active) reminder.accent else palette.text,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = reminder.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (mode == CalendarReminderMode.BeforeAndAtBroadcast && active) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(SeriesCalendarMinutes) { minutes ->
                        val minuteActive = beforeMinutes == minutes
                        Text(
                            if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时",
                            style = AppTypography.caption.strong,
                            color = if (minuteActive) reminder.accent else palette.sub,
                            modifier =
                                Modifier
                                    .pressable(role = Role.RadioButton) { onMinutes(minutes) }
                                    .semantics { this.selected = minuteActive }
                                    .clip(CircleShape)
                                    .background(if (minuteActive) reminder.container else palette.card3)
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCalendarDateNavigation(
    days: List<CalendarDay>,
    selectedDate: String?,
    onSelected: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val dates = remember(days) { days.map(CalendarDay::date) }
    val selectedIndex = dates.indexOf(selectedDate).coerceAtLeast(0)
    val visibleDates =
        remember(dates, selectedDate) {
            seriesCalendarDateWindow(dates, selectedDate ?: dates.first())
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            AppIcons.ChevronLeft,
            contentDescription = "前一个播出日",
            tint = if (selectedIndex > 0) palette.sub else palette.hint.copy(alpha = 0.42f),
            modifier =
                Modifier
                    .pressable(enabled = selectedIndex > 0) { onSelected(dates[selectedIndex - 1]) }
                    .touchTarget()
                    .size(34.dp)
                    .padding(9.dp),
        )
        visibleDates.forEachIndexed { index, date ->
            SeriesCalendarDateChip(
                date = date,
                active = date == selectedDate,
                roleColor = listOf(SeriesCalendarTeal, SeriesCalendarCoral, SeriesCalendarAmber)[index],
                onClick = { onSelected(date) },
                modifier = Modifier.weight(1f),
            )
        }
        repeat((3 - visibleDates.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = "后一个播出日",
            tint = if (selectedIndex < dates.lastIndex) palette.sub else palette.hint.copy(alpha = 0.42f),
            modifier =
                Modifier
                    .pressable(enabled = selectedIndex < dates.lastIndex) {
                        onSelected(dates[selectedIndex + 1])
                    }.touchTarget()
                    .size(34.dp)
                    .padding(9.dp),
        )
    }
}

@Composable
private fun SeriesCalendarDateChip(
    date: String,
    active: Boolean,
    roleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val role = resolveAccentColors(roleColor, palette.isDark)
    Column(
        modifier
            .heightIn(min = 54.dp)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = active }
            .flatGlass(
                GlassShapes.chip,
                lerp(palette.card2, role.container, if (active) 0.92f else 0.44f),
                if (active) role.border else role.border.copy(alpha = 0.28f),
            ).padding(horizontal = 4.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            seriesCalendarMonthDay(date),
            style = AppTypography.body.strong,
            color = role.accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            isoWeekdayLabel(date),
            style = AppTypography.caption.regular,
            color = if (active) role.accent else palette.sub2,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeriesCalendarEpisodeContent(
    days: List<CalendarDay>,
    selectedDate: String?,
    today: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val selectedDay = days.firstOrNull { it.date == selectedDate } ?: days.firstOrNull()
    when {
        selectedDay != null -> {
            LazyColumn(
                modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            seriesCalendarDayLabel(selectedDay.date, today),
                            style = AppTypography.body.strong,
                            color = LocalAccentColors.current.accent,
                        )
                        Text(
                            "${selectedDay.date} · ${isoWeekdayLabel(selectedDay.date)}",
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                        )
                    }
                }
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .flatGlass(GlassShapes.card, palette.card2, palette.border),
                    ) {
                        selectedDay.entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(palette.border),
                                )
                            }
                            SeriesCalendarEpisodeRow(entry)
                        }
                    }
                }
                if (loading) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    8.dp,
                                    Alignment.CenterHorizontally,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Text(
                                "正在刷新排期…",
                                style = AppTypography.caption.regular,
                                color = palette.sub2,
                            )
                        }
                    }
                }
            }
        }

        loading ->
            SeriesCalendarCenteredState(
                message = "正在读取该剧播出安排…",
                progress = true,
                modifier = modifier,
            )

        error != null ->
            SeriesCalendarCenteredState(
                message = error,
                action = "重新加载",
                onAction = onRetry,
                modifier = modifier,
            )

        else ->
            SeriesCalendarCenteredState(
                message = "服务器暂未提供该剧的播出排期。",
                modifier = modifier,
            )
    }
}

@Composable
private fun SeriesCalendarEpisodeRow(entry: CalendarEntry) {
    val palette = LocalPalette.current
    val (status, tint) = seriesCalendarStatus(entry.status, palette.isDark, palette.sub2)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            entry.episode.episodeLabel,
            style = AppTypography.body.medium,
            color = palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            style = AppTypography.caption.strong,
            color = tint,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SeriesCalendarInlineNotice(
    message: String,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val error = resolveAccentColors(SeriesCalendarCoral, palette.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .pressable(onClick = onRetry)
            .clip(GlassShapes.chip)
            .background(error.container)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            style = AppTypography.caption.regular,
            color = error.accent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("重试", style = AppTypography.caption.strong, color = error.accent)
    }
}

@Composable
private fun SeriesCalendarCenteredState(
    message: String,
    modifier: Modifier = Modifier,
    progress: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            message,
            style = AppTypography.body.regular,
            color = palette.sub,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                action,
                style = AppTypography.body.strong,
                color = LocalAccentColors.current.accent,
                modifier =
                    Modifier
                        .pressable(onClick = onAction)
                        .touchTarget()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SeriesIdentityCandidates(
    candidates: List<TmdbSeriesIdentityCandidate>,
    onSelect: (TmdbSeriesIdentityCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val plum = resolveAccentColors(SeriesCalendarPlum, palette.isDark)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "媒体库缺少可靠的 TMDB 标识。请选择一次，结果会保存到本机。",
                style = AppTypography.body.regular,
                color = palette.sub,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
        items(candidates, key = { it.tmdbId }) { candidate ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable { onSelect(candidate) }
                    .flatGlass(
                        GlassShapes.card,
                        lerp(palette.card2, plum.container, 0.46f),
                        plum.border.copy(alpha = 0.36f),
                    ).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(candidate.year?.toString(), "TMDB ${candidate.tmdbId}")
                            .joinToString(" · "),
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = plum.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SeriesCalendarFooter(
    sourceUrl: String?,
    sourceDescription: String?,
    onOpenSource: (() -> Unit)?,
    onRebindIdentity: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 13.dp)
            .flatGlass(GlassShapes.card, palette.card2, palette.border),
    ) {
        if (sourceUrl != null && onOpenSource != null) {
            SeriesCalendarFooterRow(
                label = "排期来源",
                value = sourceDescription ?: "查看官方证据页面",
                color = SeriesCalendarAmber,
                onClick = onOpenSource,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        }
        SeriesCalendarFooterRow(
            label = "剧集匹配",
            value = "TMDB 已匹配 · 点击更换",
            color = SeriesCalendarPlum,
            onClick = onRebindIdentity,
        )
    }
}

@Composable
private fun SeriesCalendarFooterRow(
    label: String,
    value: String,
    color: Color,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val role = resolveAccentColors(color, palette.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AppTypography.caption.strong, color = role.accent)
        Text(
            value,
            style = AppTypography.caption.regular,
            color = palette.sub2,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = role.accent,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun seriesScheduleInfo(days: List<CalendarDay>): SeriesScheduleInfo {
    val entries = days.flatMap(CalendarDay::entries)
    val episodeCount = entries.size
    val libraryEpisodeCount = entries.mapNotNull { it.libraryEpisodeCount }.maxOrNull()
    val trustedSchedule =
        entries
            .map(CalendarEntry::episode)
            .firstOrNull { it.scheduleAuthority != AiringScheduleAuthority.Tmdb }
    val subtitle =
        when {
            trustedSchedule != null ->
                buildList {
                    add("$episodeCount 集排期")
                    libraryEpisodeCount?.let { add("已入库 $it 集") }
                    add(
                        when (trustedSchedule.scheduleAuthority) {
                            AiringScheduleAuthority.Official -> "官方会员日历"
                            AiringScheduleAuthority.Verified -> "多源确认排期"
                            AiringScheduleAuthority.Library -> "媒体服务器日期"
                            else -> "预计排期"
                        },
                    )
                    trustedSchedule.releaseAtBeijing
                        ?.takeIf {
                            trustedSchedule.origin == com.yfuse.core.model.ShowOrigin.Foreign &&
                                it.length >= 16
                        }?.substring(11, 16)
                        ?.let { add("北京时间 $it") }
                        ?: trustedSchedule.airTime?.let { time ->
                            add(
                                if (trustedSchedule.timeZoneId == "Asia/Shanghai") {
                                    "北京时间 $time"
                                } else {
                                    "$time（${trustedSchedule.timeZoneId ?: "原播时区"}）"
                                },
                            )
                        }
                    trustedSchedule.platforms
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("/")
                        ?.let(::add)
                }.joinToString(" · ")

            episodeCount > 0 -> "$episodeCount 集 · 按原产地播出日期"
            else -> "按原产地播出日期"
        }
    val sourceDescription =
        trustedSchedule
            ?.scheduleEvidence
            ?.map { it.publisher }
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.take(3)
            ?.joinToString(" / ")
            ?.ifBlank { null }
    return SeriesScheduleInfo(
        subtitle = subtitle,
        sourceUrl = trustedSchedule?.sourceUrl,
        sourceDescription = sourceDescription,
    )
}

internal fun seriesCalendarInitialDate(
    dates: List<String>,
    today: String,
): String? = dates.minByOrNull { abs(daysBetweenIso(today, it)) }

internal fun seriesCalendarDateWindow(
    dates: List<String>,
    selectedDate: String,
    windowSize: Int = 3,
): List<String> {
    if (dates.size <= windowSize) return dates
    val selectedIndex = dates.indexOf(selectedDate).coerceAtLeast(0)
    val start = (selectedIndex - windowSize / 2).coerceIn(0, dates.size - windowSize)
    return dates.subList(start, start + windowSize)
}

internal fun seriesCalendarMonthDay(date: String): String {
    val parts = date.split('-')
    if (parts.size < 3) return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    return "${month}月${day}日"
}

private fun seriesCalendarStatus(
    status: LibraryStatus,
    darkTheme: Boolean,
    mutedColor: Color,
): Pair<String, Color> {
    val labelAndBase =
        when (status) {
            LibraryStatus.Unaired -> "待播" to SeriesCalendarAmber
            LibraryStatus.Missing -> "未入库" to SeriesCalendarCoral
            LibraryStatus.Available -> "可播放" to SeriesCalendarEmerald
            LibraryStatus.InProgress -> "观看中" to SeriesCalendarTeal
            LibraryStatus.Watched -> "已看" to SeriesCalendarPlum
            LibraryStatus.Unknown -> return "仅供参考" to mutedColor
        }
    return labelAndBase.first to resolveAccentColors(labelAndBase.second, darkTheme).accent
}
