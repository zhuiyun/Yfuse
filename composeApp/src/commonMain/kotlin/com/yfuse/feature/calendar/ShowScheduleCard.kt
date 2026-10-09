package com.yfuse.feature.calendar

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.CssShadow
import com.yfuse.core.designsystem.DecorativeTints
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DisclosureContent
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassButtonEmphasis
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogPaneTitle
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.Palette
import com.yfuse.core.designsystem.TOAST_UNDO_WINDOW_MS
import com.yfuse.core.designsystem.artworkPageSurface
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.resolveAccentColors
import com.yfuse.core.designsystem.resolveGlassButtonVisuals
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.isoEpochDay
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.core.util.posterCardRating
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.core.util.tmdbTitleUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private val SchedulePosterWidth = 84.dp
private val SchedulePosterShadow = CssShadow(0.dp, 6.dp, 16.dp, 0.dp, Color.Black.copy(alpha = 0.22f))
private val ScheduleFallbackArtwork = Color(0xFFDAD4E8)

/** The band a date and its brush stroke share, and the line under it for the episodes. */
private val ScheduleDateBand = 36.dp
private val ScheduleStrokeThickness = 26.dp
private val ScheduleLabelSlot = 18.dp
private val ScheduleTodayDot = 6.dp
private val ScheduleReminderMinutes = listOf(10, 30, 60, 120, 360)
private val ScheduleWeekdays = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/** How long a follow change may take to reach the store before the card shows the truth again. */
private const val FOLLOW_SETTLE_MS = 3_000L

/**
 * One title's 播出日历, as a card: its poster and where it stands, the month with a stroke of ink
 * under every day that broadcasts and the episodes it brings written beneath, and 分享 / 提醒 /
 * 追剧 at the foot. The detail page's sheet and the one 追剧日历 opens for a show both show this,
 * so the two can no longer drift apart.
 *
 * [days] holds this title's broadcasts only. [replacement] takes the calendar's place while the
 * title still has to be matched to a TMDB identity.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShowScheduleDialog(
    title: String,
    days: List<CalendarDay>,
    today: String,
    posterUrls: List<String>,
    followed: Boolean,
    reminderMode: CalendarReminderMode,
    remindBeforeMinutes: Int,
    onToggleFollow: () -> Unit,
    onSetReminder: (CalendarReminderMode, Int) -> Unit,
    onDismiss: () -> Unit,
    artworkColorUrl: String? = null,
    overview: String? = null,
    rating: Double? = null,
    initialSelectedDate: String? = null,
    loading: Boolean = false,
    refreshing: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onOpenEntry: ((CalendarEntry) -> Unit)? = null,
    onRebindIdentity: (() -> Unit)? = null,
    replacement: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val share = rememberShareHandler()
    val entriesByDate =
        remember(days) {
            days
                .filter { it.entries.isNotEmpty() }
                .groupBy(CalendarDay::date)
                .mapValues { (_, sameDay) -> sameDay.flatMap(CalendarDay::entries) }
        }
    val entries = remember(entriesByDate) { entriesByDate.values.flatten() }
    val months = remember(entriesByDate) { scheduleMonths(entriesByDate.keys) }
    val summary = remember(entries, today) { scheduleSeasonSummary(entries, today) }
    val provenance = remember(entries) { scheduleProvenance(entries) }
    val footer =
        remember(entries, provenance) {
            listOfNotNull(scheduleLibraryLine(entries), provenance.authority, provenance.airTime).joinToString(" · ")
        }
    val shareLink =
        remember(entries) {
            entries.firstOrNull()?.episode?.let { episode ->
                tmdbTitleUrl(
                    tmdbId = episode.showTmdbId.takeIf { it > 0 }?.toString(),
                    mediaType = if (episode.isMovie) "movie" else "tv",
                )
            }
        }
    val posters = remember(posterUrls) { posterUrls.filter(String::isNotBlank).distinct() }
    var resolvedPoster by remember(posters) { mutableStateOf<String?>(null) }
    val sampledArtwork =
        rememberDominantColor(artworkColorUrl ?: resolvedPoster ?: posters.firstOrNull(), ScheduleFallbackArtwork)
    // The poster decides whether this is a dark or light surface, as it does for the detail page.
    val darkArtwork = sampledArtwork.luminance() < 0.38f
    val dialogBackground =
        remember(sampledArtwork, darkArtwork) {
            artworkPageSurface(sampledArtwork, darkTheme = darkArtwork)
        }

    var selectedDate by remember { mutableStateOf(initialSelectedDate?.takeIf { it in entriesByDate }) }
    // What the day panel shows, kept while it folds away after the date is let go.
    var panelDate by remember { mutableStateOf(selectedDate) }
    LaunchedEffect(entriesByDate) {
        if (selectedDate?.let { it !in entriesByDate } == true) selectedDate = null
    }
    // The month the card opens on: the tapped day's, or that of the broadcast closest to today.
    // The detail page fills its schedule in twice, and the second, fuller one can move it.
    val anchorMonth =
        remember(entriesByDate, today, initialSelectedDate) {
            (
                initialSelectedDate?.takeIf { it in entriesByDate }
                    ?: scheduleInitialDate(entriesByDate.keys.sorted(), today)
            )?.let(::scheduleMonthOf)
        }
    val panelRequester = remember { BringIntoViewRequester() }
    // Counts the person's picks, so the card does not scroll by itself when it opens on a day.
    var picks by remember { mutableIntStateOf(0) }
    LaunchedEffect(picks) {
        if (picks > 0) {
            // Once the panel has opened, so the whole of it is brought into view.
            delay(Motion.DISCLOSURE.toLong())
            panelRequester.bringIntoView()
        }
    }

    // 取消追剧 clears the title's reminders with it, so it is done first and given 5 秒撤销; the
    // follow goes once the window closes, or with the card.
    var pendingUnfollow by remember { mutableStateOf(false) }
    // The follow state last asked for, shown until the store reports it: a second tap meanwhile
    // would otherwise toggle it straight back, and the bin would flash up as the unfollow lands.
    var requestedFollow by remember { mutableStateOf<Boolean?>(null) }
    val latestFollowed by rememberUpdatedState(followed)
    val latestToggleFollow by rememberUpdatedState(onToggleFollow)
    LaunchedEffect(pendingUnfollow) {
        if (pendingUnfollow) {
            delay(TOAST_UNDO_WINDOW_MS)
            if (latestFollowed) {
                requestedFollow = false
                latestToggleFollow()
            }
            pendingUnfollow = false
        }
    }
    LaunchedEffect(followed, requestedFollow) {
        when (requestedFollow) {
            null -> Unit
            followed -> requestedFollow = null
            // Not taken — the match was ambiguous, say: show what is true again.
            else -> {
                delay(FOLLOW_SETTLE_MS)
                requestedFollow = null
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (pendingUnfollow && latestFollowed) latestToggleFollow()
        }
    }
    val shownFollowed = !pendingUnfollow && (requestedFollow ?: followed)
    var reminderOpen by remember { mutableStateOf(false) }
    LaunchedEffect(shownFollowed) {
        if (!shownFollowed) reminderOpen = false
    }
    val reminderShown = reminderOpen && shownFollowed
    val reminderProgress = rememberDisclosureProgress(reminderShown)
    val undoProgress = rememberDisclosureProgress(pendingUnfollow)
    val panelProgress = rememberDisclosureProgress(selectedDate != null)
    val calendarShown = replacement == null && months.isNotEmpty()

    ArtworkPageTheme(
        background = dialogBackground,
        artworkAccent = sampledArtwork,
    ) {
        GlassDialog(
            onDismiss = onDismiss,
            scrollable = false,
            contentPadding = 0.dp,
            windowPadding = PaddingValues(horizontal = Dimens.space.lg, vertical = Dimens.space.xl),
        ) {
            val palette = LocalPalette.current
            val paneTitle = LocalDialogPaneTitle.current
            if (paneTitle != null) SideEffect { paneTitle.value = "$title 播出日历" }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                ScheduleHeader(
                    title = title,
                    posterUrls = posters,
                    summary = summary,
                    overview = overview,
                    rating = posterCardRating(rating),
                    platformLabel =
                        provenance.platforms
                            .take(2)
                            .joinToString(" / ")
                            .ifEmpty { if (provenance.sourceUrl != null) "排期来源" else null },
                    onOpenSource =
                        provenance.sourceUrl?.let { url ->
                            { runCatching { uriHandler.openUri(url) } }
                        },
                    onPosterResolved = { resolvedPoster = it },
                )
                when {
                    replacement != null -> replacement()
                    months.isEmpty() && loading ->
                        ScheduleState(message = "正在读取该剧播出安排…", progress = true)

                    months.isEmpty() && error != null ->
                        ScheduleState(message = error, action = "重新加载", onAction = onRetry)

                    months.isEmpty() -> ScheduleState(message = "服务器暂未提供该剧的播出排期。")
                    else -> {
                        if (error != null) ScheduleNotice(message = error, onRetry = onRetry)
                        ScheduleMonths(
                            months = months,
                            anchorMonth = anchorMonth,
                            entriesByDate = entriesByDate,
                            today = today,
                            selectedDate = selectedDate,
                            onSelect = { date ->
                                if (selectedDate == date) {
                                    selectedDate = null
                                } else {
                                    selectedDate = date
                                    panelDate = date
                                    picks++
                                }
                            },
                            onMonthShown = { month ->
                                if (selectedDate?.let(::scheduleMonthOf) != month) selectedDate = null
                            },
                        )
                        DisclosureContent(
                            expanded = selectedDate != null,
                            progress = panelProgress,
                            modifier = Modifier.bringIntoViewRequester(panelRequester),
                        ) {
                            val date = panelDate
                            val dayEntries = date?.let(entriesByDate::get)
                            if (date != null && dayEntries != null) {
                                ScheduleDayPanel(
                                    date = date,
                                    entries = dayEntries,
                                    today = today,
                                    onOpenEntry = onOpenEntry,
                                )
                            }
                        }
                        ScheduleFooter(
                            text = footer,
                            busy = refreshing || loading,
                            onRebindIdentity = onRebindIdentity,
                        )
                    }
                }
            }
            if (calendarShown) {
                DisclosureContent(pendingUnfollow, undoProgress) {
                    ScheduleUndoNotice(onUndo = { pendingUnfollow = false })
                }
                DisclosureContent(reminderShown, reminderProgress) {
                    ScheduleReminderPicker(
                        selected = reminderMode,
                        beforeMinutes = remindBeforeMinutes,
                        onSelect = { mode ->
                            onSetReminder(mode, remindBeforeMinutes)
                            if (mode != CalendarReminderMode.BeforeAndAtBroadcast) reminderOpen = false
                        },
                        onMinutes = { minutes ->
                            onSetReminder(CalendarReminderMode.BeforeAndAtBroadcast, minutes)
                            reminderOpen = false
                        },
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.border),
            )
            ScheduleActionBar(
                followed = shownFollowed,
                reminding = shownFollowed && reminderMode != CalendarReminderMode.Off,
                reminderOpen = reminderShown,
                refreshing = refreshing,
                onShare =
                    if (calendarShown) {
                        { share.shareText(scheduleShareText(title, days, today, shareLink)) }
                    } else {
                        null
                    },
                onReminder =
                    if (calendarShown) {
                        {
                            when {
                                shownFollowed -> reminderOpen = !reminderOpen
                                // A reminder needs the follow: give it back, or take it up, first.
                                pendingUnfollow -> {
                                    pendingUnfollow = false
                                    reminderOpen = true
                                }

                                requestedFollow != null -> Unit
                                else -> {
                                    requestedFollow = true
                                    onToggleFollow()
                                    reminderOpen = true
                                }
                            }
                        }
                    } else {
                        null
                    },
                onRefresh = onRefresh?.takeIf { calendarShown },
                onFollow =
                    if (calendarShown) {
                        {
                            when {
                                pendingUnfollow -> pendingUnfollow = false
                                // The last change is still on its way; this tap would undo it.
                                requestedFollow != null -> Unit
                                followed -> {
                                    pendingUnfollow = true
                                    reminderOpen = false
                                }

                                else -> {
                                    requestedFollow = true
                                    onToggleFollow()
                                }
                            }
                        }
                    } else {
                        null
                    },
                onDone = onDismiss,
            )
        }
    }
}

@Composable
private fun ScheduleHeader(
    title: String,
    posterUrls: List<String>,
    summary: String?,
    overview: String?,
    rating: String?,
    platformLabel: String?,
    onOpenSource: (() -> Unit)?,
    onPosterResolved: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.space.lg,
                top = Dimens.space.lg,
                end = Dimens.space.lg,
                bottom = Dimens.space.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
    ) {
        Box(
            Modifier
                .width(SchedulePosterWidth)
                .aspectRatio(2f / 3f)
                .shadow(SchedulePosterShadow, AppShapes.thumb)
                .clip(AppShapes.thumb)
                .background(palette.card3),
        ) {
            if (posterUrls.isNotEmpty()) {
                FallbackImage(
                    urls = posterUrls,
                    contentDescription = "$title 海报",
                    modifier = Modifier.fillMaxSize(),
                    onResolvedUrl = onPosterResolved,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = AppTypography.section.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics { heading() },
                )
                if (platformLabel != null) SchedulePlatformBadge(platformLabel, onOpenSource)
            }
            if (summary != null) {
                Spacer(Modifier.height(Dimens.space.xs))
                Text(
                    summary,
                    style = AppTypography.body.medium,
                    color = palette.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!overview.isNullOrBlank()) {
                Spacer(Modifier.height(Dimens.space.sm))
                Text(
                    overview,
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rating != null) {
                Spacer(Modifier.height(Dimens.space.sm))
                ScheduleRating(rating)
            }
        }
    }
}

/** The publishing platform, top right as the card's source mark; it opens the evidence page. */
@Composable
private fun SchedulePlatformBadge(
    label: String,
    onOpenSource: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .padding(start = Dimens.space.sm)
            .then(
                if (onOpenSource != null) {
                    Modifier.pressable(onClickLabel = "查看排期来源", onClick = onOpenSource)
                } else {
                    Modifier
                },
            ).padding(vertical = Dimens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            AppIcons.Play,
            contentDescription = null,
            tint = accent.accent,
            modifier = Modifier.size(11.dp),
        )
        Text(
            label,
            style = AppTypography.caption.strong,
            color = palette.sub,
            maxLines = 1,
        )
        if (onOpenSource != null) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.sub2,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/** The score as the detail page names it: the server's community rating, from TMDB. */
@Composable
private fun ScheduleRating(rating: String) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.xs),
    ) {
        Text(
            "TMDB",
            style = AppTypography.caption.strong,
            color = accent.accent,
            modifier =
                Modifier
                    .clip(AppShapes.micro)
                    .background(accent.container)
                    .padding(horizontal = Dimens.space.xs, vertical = 1.dp),
        )
        Text(rating, style = AppTypography.body.strong, color = palette.sub)
    }
}

@Composable
private fun ScheduleMonths(
    months: List<ScheduleMonth>,
    anchorMonth: ScheduleMonth?,
    entriesByDate: Map<String, List<CalendarEntry>>,
    today: String,
    selectedDate: String?,
    onSelect: (String) -> Unit,
    onMonthShown: (ScheduleMonth) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val initialPage = remember { anchorMonth?.let(months::indexOf)?.takeIf { it >= 0 } ?: 0 }
    val pagerState = rememberPagerState(initialPage = initialPage) { months.size }
    val latestMonths by rememberUpdatedState(months)
    val latestOnMonthShown by rememberUpdatedState(onMonthShown)
    var shownMonth by remember { mutableStateOf(months.getOrNull(initialPage)) }
    // Once the person has turned the page or picked a day, the month is theirs; until then it
    // follows the anchor.
    var browsed by remember { mutableStateOf(false) }
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) browsed = true
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val month = latestMonths.getOrNull(page) ?: return@collect
            if (month != shownMonth) {
                shownMonth = month
                latestOnMonthShown(month)
            }
        }
    }
    // New dates must not move the page off the month being read — nor keep it on a month the
    // first, partial schedule opened on when the full one puts today's broadcasts elsewhere.
    LaunchedEffect(months, anchorMonth) {
        val target = (if (browsed) shownMonth else anchorMonth) ?: return@LaunchedEffect
        val page = months.indexOf(target)
        if (page >= 0 && page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    fun show(page: Int) {
        browsed = true
        scope.launch {
            if (still) {
                pagerState.scrollToPage(page)
            } else {
                pagerState.animateScrollToPage(page, animationSpec = Motion.tween(Motion.EMPHASIZED))
            }
        }
    }
    val page = pagerState.currentPage
    ScheduleMonthHeader(
        label = months.getOrNull(page)?.label.orEmpty(),
        paged = months.size > 1,
        canGoBack = page > 0,
        canGoForward = page < months.lastIndex,
        onBack = { show(page - 1) },
        onForward = { show(page + 1) },
    )
    ScheduleWeekdayRow()
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        // An Int, which the pager can save; the month itself could not be.
        key = { index -> months.getOrNull(index)?.let { it.year * 12 + it.month } ?: -1 - index },
    ) { index ->
        val month = months.getOrNull(index)
        if (month != null) {
            ScheduleMonthGrid(
                month = month,
                entriesByDate = entriesByDate,
                today = today,
                selectedDate = selectedDate,
                onSelect = { date ->
                    // A day picked here keeps its month on screen, whatever a fuller schedule says.
                    browsed = true
                    onSelect(date)
                },
            )
        }
    }
}

/** `—— 2026年10月 ——`, with a key either side when the schedule runs over more than one month. */
@Composable
private fun ScheduleMonthHeader(
    label: String,
    paged: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = if (paged) Dimens.space.xs else Dimens.space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paged) ScheduleMonthKey(AppIcons.ChevronLeft, "前一个播出月", canGoBack, onBack)
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(palette.border),
        )
        Text(
            label,
            style = AppTypography.section.strong,
            color = palette.text,
            maxLines = 1,
            modifier =
                Modifier
                    .padding(horizontal = Dimens.space.md)
                    .semantics { heading() },
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(palette.border),
        )
        if (paged) ScheduleMonthKey(AppIcons.ChevronRight, "后一个播出月", canGoForward, onForward)
    }
}

@Composable
private fun ScheduleMonthKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Icon(
        icon,
        contentDescription = description,
        tint = if (enabled) palette.sub else palette.hint.copy(alpha = 0.42f),
        modifier =
            Modifier
                .pressable(enabled = enabled, onClick = onClick)
                .touchTarget()
                .size(34.dp)
                .padding(9.dp),
    )
}

@Composable
private fun ScheduleWeekdayRow() {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.md)
            .padding(bottom = Dimens.space.xs),
    ) {
        ScheduleWeekdays.forEach { weekday ->
            Text(
                weekday,
                style = AppTypography.caption.regular,
                color = palette.sub2,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScheduleMonthGrid(
    month: ScheduleMonth,
    entriesByDate: Map<String, List<CalendarEntry>>,
    today: String,
    selectedDate: String?,
    onSelect: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val weeks = remember(month) { scheduleMonthWeeks(month) }
    // Ink with a breath of the poster's colour in it; the dates written on it take the page colour.
    val ink = lerp(palette.text, accent.accent, 0.14f).copy(alpha = 0.82f)
    val reveal = rememberInkReveal(month)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.space.xs),
    ) {
        weeks.forEach { week ->
            val runs = scheduleStrokeRuns(week.map { date -> date != null && !entriesByDate[date].isNullOrEmpty() })
            Box(Modifier.fillMaxWidth()) {
                if (runs.isNotEmpty()) {
                    InkStrokes(
                        runs = runs,
                        seeds = runs.map { run -> week[run.first]?.let(::isoEpochDay) ?: 0L },
                        ink = ink,
                        reveal = reveal,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ScheduleDateBand),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.space.md),
                ) {
                    week.forEach { date ->
                        ScheduleDayCell(
                            date = date,
                            entries = date?.let(entriesByDate::get),
                            today = today,
                            selected = date != null && date == selectedDate,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDayCell(
    date: String?,
    entries: List<CalendarEntry>?,
    today: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (date == null) {
        Spacer(modifier.height(ScheduleDateBand + ScheduleLabelSlot))
        return
    }
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val span = remember(entries) { entries?.let(::scheduleDaySpan) }
    val isToday = date == today
    val description =
        remember(date, span, isToday) {
            buildList {
                add("${scheduleMonthDay(date)} ${isoWeekdayLabel(date)}")
                if (isToday) add("今天")
                span?.let { add(it.phrase) }
            }.joinToString("，")
        }
    Column(
        modifier
            .height(ScheduleDateBand + ScheduleLabelSlot)
            .then(
                if (span != null) {
                    Modifier.pressable(
                        pressedScale = 0.92f,
                        lightFeedback = false,
                        stateLayer = false,
                        haptic = HapticSignal.Select,
                        onClickLabel = if (selected) "收起当天剧集" else "查看当天剧集",
                    ) { onSelect(date) }
                } else {
                    Modifier
                },
            ).clearAndSetSemantics {
                contentDescription = description
                if (span != null) this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ScheduleDateBand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.takeLast(2).trimStart('0'),
                style = AppTypography.section.medium,
                // A date on a stroke is written in the page colour, out of the ink.
                color = if (span != null) palette.background else palette.text,
                maxLines = 1,
            )
            if (isToday) {
                Box(
                    Modifier
                        .offset(x = 12.dp, y = (-11).dp)
                        .size(ScheduleTodayDot)
                        .clip(CircleShape)
                        .background(palette.success),
                )
            }
        }
        if (span != null) {
            Text(
                span.cellLabel,
                style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
                color = selectionColor(if (selected) accent.accent else palette.sub2),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(selectionColor(if (selected) accent.container else Color.Transparent))
                        .padding(horizontal = 5.dp),
            )
        }
    }
}

/** How far the month's strokes have been painted; they run on from where the brush came down. */
@Composable
private fun rememberInkReveal(month: ScheduleMonth): State<Float> {
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val progress = remember(month) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(progress, still) {
        if (still) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, Motion.tween(Motion.ARRIVAL_REVEAL))
        }
    }
    return progress.asState()
}

/**
 * One week's strokes, drawn across the card's whole width: a stroke's start and its dry tail
 * reach past the outer dates, into the grid's margin, and an offscreen layer is cut at its edges.
 */
@Composable
private fun InkStrokes(
    runs: List<IntRange>,
    seeds: List<Long>,
    ink: Color,
    reveal: State<Float>,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            // Offscreen, so the dry streaks take ink back out of the stroke rather than paint on it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val margin = Dimens.space.md.toPx()
                val cell = (size.width - margin * 2f) / 7f
                val inset = cell * 0.06f
                val strokes =
                    runs.mapIndexed { index, run ->
                        inkStroke(
                            left = margin + run.first * cell + inset,
                            right = margin + (run.last + 1) * cell - inset,
                            centerY = size.height / 2f,
                            thickness = ScheduleStrokeThickness.toPx(),
                            seed = seeds.getOrElse(index) { 0L },
                        )
                    }
                onDrawBehind {
                    val shown = reveal.value
                    strokes.forEach { stroke ->
                        clipRect(left = stroke.start, right = stroke.start + (stroke.end - stroke.start) * shown) {
                            drawPath(stroke.body, ink)
                            stroke.streaks.forEach { streak ->
                                drawLine(
                                    color = Color.Black,
                                    start = streak.from,
                                    end = streak.to,
                                    strokeWidth = streak.width,
                                    cap = StrokeCap.Round,
                                    blendMode = BlendMode.DstOut,
                                )
                            }
                        }
                    }
                }
            },
    )
}

private class InkStroke(
    /** Leftmost x, the swollen start included. */
    val start: Float,
    /** Rightmost x, the dry tail included. */
    val end: Float,
    val body: Path,
    val streaks: List<InkStreak>,
)

private class InkStreak(
    val from: Offset,
    val to: Offset,
    val width: Float,
)

/**
 * One pass of a loaded brush from [left] to [right]: set down blunt, its edges swelling and
 * trembling as it travels, thinning as it runs dry until the bristles part at the end. Seeded by
 * the stroke's first date, so it keeps its shape from frame to frame and every time the month is
 * opened.
 */
private fun inkStroke(
    left: Float,
    right: Float,
    centerY: Float,
    thickness: Float,
    seed: Long,
): InkStroke {
    val random = Random(seed)
    val half = thickness / 2f
    val length = (right - left).coerceAtLeast(thickness)
    val steps = (length / (thickness * 0.45f)).roundToInt().coerceAtLeast(3)
    // The hand drifts a little up or down over the length of the stroke, and the pressure swells.
    val drift = (random.nextFloat() - 0.5f) * thickness * 0.22f
    val swell = random.nextFloat() * 2f * PI.toFloat()

    fun edge(side: Float): List<Offset> =
        List(steps + 1) { step ->
            val along = step / steps.toFloat()
            val width = half * (1f - 0.16f * along + 0.05f * sin(swell + along * 3.1f))
            Offset(
                x = left + length * along,
                y = centerY + side * width + drift * (along - 0.5f) + (random.nextFloat() - 0.5f) * thickness * 0.09f,
            )
        }
    val top = edge(-1f)
    val bottom = edge(1f)
    val tipTop = top.last()
    val tipBottom = bottom.last()
    var reach = 0f
    val body =
        Path().apply {
            moveTo(bottom.first().x, bottom.first().y)
            // Where the brush was set down: blunt, and a little swollen.
            cubicTo(
                left - thickness * 0.34f,
                bottom.first().y - thickness * 0.06f,
                left - thickness * 0.3f,
                top.first().y + thickness * 0.04f,
                top.first().x,
                top.first().y,
            )
            smoothThrough(top)
            // Where it ran dry: the bristles part into uneven tips.
            val prongs = 4
            for (prong in 0 until prongs) {
                val upper = tipTop.y + (tipBottom.y - tipTop.y) * prong / prongs
                val lower = tipTop.y + (tipBottom.y - tipTop.y) * (prong + 1) / prongs
                val tip = thickness * (0.05f + random.nextFloat() * 0.24f)
                reach = maxOf(reach, tip)
                val tipY = upper + (lower - upper) * (0.35f + random.nextFloat() * 0.3f)
                cubicTo(
                    tipTop.x + tip * 0.55f,
                    upper,
                    tipTop.x + tip,
                    tipY - (lower - upper) * 0.15f,
                    tipTop.x + tip,
                    tipY,
                )
                if (prong < prongs - 1) {
                    val notch = thickness * (0.02f + random.nextFloat() * 0.1f)
                    cubicTo(tipTop.x + tip * 0.45f, lower, tipTop.x - notch * 0.5f, lower, tipTop.x - notch, lower)
                } else {
                    cubicTo(tipTop.x + tip * 0.45f, lower, tipBottom.x, tipBottom.y, tipBottom.x, tipBottom.y)
                }
            }
            smoothThrough(bottom.asReversed())
            close()
        }
    // Dry streaks along the edges, clear of the date written across the middle.
    val streaks =
        List(3 + random.nextInt(3)) {
            val side = if (random.nextFloat() < 0.5f) -1f else 1f
            val y = centerY + side * thickness * (0.28f + random.nextFloat() * 0.14f)
            InkStreak(
                from = Offset(left + length * (0.45f + random.nextFloat() * 0.35f), y),
                to = Offset(right + reach, y + (random.nextFloat() - 0.5f) * thickness * 0.06f),
                width = thickness * (0.025f + random.nextFloat() * 0.035f),
            )
        }
    return InkStroke(start = left - thickness * 0.34f, end = right + reach, body = body, streaks = streaks)
}

/** On through [points] by their midpoints, so the waver reads as a hand rather than a polygon. */
private fun Path.smoothThrough(points: List<Offset>) {
    for (index in 1 until points.lastIndex) {
        val point = points[index]
        val next = points[index + 1]
        cubicTo(point.x, point.y, point.x, point.y, (point.x + next.x) / 2f, (point.y + next.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

/** The broadcasts of the date picked in the month: each episode, when and where, and its state. */
@Composable
private fun ScheduleDayPanel(
    date: String,
    entries: List<CalendarEntry>,
    today: String,
    onOpenEntry: ((CalendarEntry) -> Unit)?,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val playable = entries.count { it.itemId != null }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.lg)
            .padding(top = Dimens.space.xs, bottom = Dimens.space.sm)
            .flatGlass(AppShapes.card, palette.card2, palette.border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${scheduleMonthDay(date)} ${isoWeekdayLabel(date)}",
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
            )
            Text(
                " · ${scheduleRelativeDay(date, today)}",
                style = AppTypography.body.medium,
                color = accent.accent,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    entries.all { it.episode.isMovie } -> "上映"
                    playable > 0 -> "${entries.size} 集 · $playable 集可播放"
                    else -> "${entries.size} 集"
                },
                style = AppTypography.caption.regular,
                color = palette.sub2,
                maxLines = 1,
            )
        }
        entries.forEach { entry ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.border),
            )
            ScheduleEpisodeRow(
                entry = entry,
                onOpen = onOpenEntry?.takeIf { entry.openItemId != null }?.let { open -> { open(entry) } },
            )
        }
    }
}

@Composable
private fun ScheduleEpisodeRow(
    entry: CalendarEntry,
    onOpen: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val (status, tint) = scheduleStatus(entry.status, palette)
    val meta = remember(entry.episode) { scheduleEpisodeMeta(entry.episode) }
    // Opening an item leaves the card, so its exit plays first.
    val open = onOpen?.let { overlayAction(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (open != null) Modifier.pressable(onClickLabel = "打开", onClick = open) else Modifier)
            .padding(horizontal = 14.dp, vertical = Dimens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.episode.episodeLabel,
                style = AppTypography.body.medium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            status,
            style = AppTypography.caption.strong,
            color = tint,
            maxLines = 1,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f))
                    .padding(horizontal = Dimens.space.sm, vertical = Dimens.space.xs),
        )
        if (open != null) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.sub2,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun scheduleStatus(
    status: LibraryStatus,
    palette: Palette,
): Pair<String, Color> =
    when (status) {
        LibraryStatus.Unaired -> "待播" to resolveAccentColors(DecorativeTints.amber, palette.isDark).accent
        LibraryStatus.Missing -> "未入库" to palette.error
        LibraryStatus.Available -> "可播放" to palette.success
        LibraryStatus.InProgress -> "观看中" to resolveAccentColors(DecorativeTints.teal, palette.isDark).accent
        LibraryStatus.Watched -> "已看" to resolveAccentColors(DecorativeTints.plum, palette.isDark).accent
        LibraryStatus.Unknown -> "仅供参考" to palette.sub2
    }

/** `Rex · 已入库 39 集 · 官方会员日历 · 20:00`, and the way to a different TMDB match. */
@Composable
private fun ScheduleFooter(
    text: String,
    busy: Boolean,
    onRebindIdentity: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = Dimens.space.lg, end = Dimens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            OrbProgress(size = 12.dp)
        } else {
            Icon(
                AppIcons.Server,
                contentDescription = null,
                tint = palette.sub2,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            if (busy) "正在刷新排期…" else text,
            style = AppTypography.caption.medium,
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRebindIdentity != null) {
            Text(
                "更换匹配",
                style = AppTypography.caption.strong,
                color = accent.accent,
                modifier =
                    Modifier
                        .pressable(onClickLabel = "更换剧集匹配", onClick = onRebindIdentity)
                        .touchTarget()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ScheduleState(
    message: String,
    progress: Boolean = false,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .padding(Dimens.space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            OrbProgress(size = 22.dp)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            message,
            style = AppTypography.body.regular,
            color = palette.sub,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                action,
                style = AppTypography.body.strong,
                color = LocalAccentColors.current.accent,
                modifier =
                    Modifier
                        .pressable(onClick = onAction)
                        .touchTarget()
                        .padding(horizontal = Dimens.space.md, vertical = Dimens.space.sm),
            )
        }
    }
}

/** A refresh that failed over a schedule still on screen. */
@Composable
private fun ScheduleNotice(
    message: String,
    onRetry: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.lg, vertical = Dimens.space.xs)
            .then(if (onRetry != null) Modifier.pressable(onClickLabel = "重试", onClick = onRetry) else Modifier)
            .clip(AppShapes.chip)
            .background(palette.errorContainer)
            .padding(horizontal = Dimens.space.md, vertical = Dimens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.sm),
    ) {
        Text(
            message,
            style = AppTypography.caption.regular,
            color = palette.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) Text("重试", style = AppTypography.caption.strong, color = palette.onErrorContainer)
    }
}

@Composable
private fun ScheduleUndoNotice(onUndo: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.lg)
            .padding(bottom = Dimens.space.sm)
            .flatGlass(AppShapes.card, palette.card2, palette.border)
            .padding(start = 14.dp, end = Dimens.space.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已取消追剧，更新提醒一并关闭",
            style = AppTypography.caption.medium,
            color = palette.sub,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Text(
            "撤销",
            style = AppTypography.body.strong,
            color = accent.accent,
            modifier =
                Modifier
                    .pressable(haptic = HapticSignal.Confirm, onClickLabel = "撤销取消追剧", onClick = onUndo)
                    .touchTarget()
                    .padding(horizontal = Dimens.space.md, vertical = Dimens.space.sm),
        )
    }
}

@Composable
private fun ScheduleReminderPicker(
    selected: CalendarReminderMode,
    beforeMinutes: Int,
    onSelect: (CalendarReminderMode) -> Unit,
    onMinutes: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val reminder = resolveAccentColors(DecorativeTints.coral, palette.isDark)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.lg)
            .padding(bottom = Dimens.space.sm)
            .flatGlass(AppShapes.card, palette.card2, palette.border)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "更新提醒",
            style = AppTypography.caption.strong,
            color = palette.sub,
            modifier =
                Modifier
                    .padding(horizontal = 10.dp, vertical = Dimens.space.xs)
                    .semantics { heading() },
        )
        CalendarReminderMode.entries.forEach { mode ->
            val active = mode == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(haptic = HapticSignal.Select, role = Role.RadioButton) { onSelect(mode) }
                    .semantics { this.selected = active }
                    .clip(AppShapes.chip)
                    .background(if (active) reminder.container else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = Dimens.space.sm),
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
                    contentPadding = PaddingValues(horizontal = Dimens.space.sm, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    motionItems(ScheduleReminderMinutes) { minutes ->
                        val minuteActive = beforeMinutes == minutes
                        Text(
                            if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时",
                            style = AppTypography.caption.strong,
                            color = if (minuteActive) reminder.accent else palette.sub,
                            modifier =
                                Modifier
                                    .pressable(haptic = HapticSignal.Select, role = Role.RadioButton) {
                                        onMinutes(minutes)
                                    }.semantics { this.selected = minuteActive }
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

/**
 * 分享, 更新提醒, 刷新 and 追剧 in one glass pill, 完成 on its own. Followed, 追剧 is the bin that
 * takes the title off the list; otherwise it says what it does.
 */
@Composable
private fun ScheduleActionBar(
    followed: Boolean,
    reminding: Boolean,
    reminderOpen: Boolean,
    refreshing: Boolean,
    onShare: (() -> Unit)?,
    onReminder: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onFollow: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reminder = resolveAccentColors(DecorativeTints.coral, palette.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(Dimens.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onShare != null || onReminder != null || onRefresh != null || onFollow != null) {
            val visuals = resolveGlassButtonVisuals(GlassButtonEmphasis.Neutral, palette, accent)
            Row(
                Modifier
                    .liquidGlass(
                        shape = AppShapes.pill,
                        fill = visuals.fill,
                        border = visuals.border,
                        over = palette.background,
                        sheen = visuals.sheen,
                    ).padding(horizontal = Dimens.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onShare != null) ScheduleTool(AppIcons.Share, "分享播出日历", palette.text, onShare)
                if (onReminder != null) {
                    ScheduleTool(
                        icon = AppIcons.Bell,
                        description = if (reminding) "更新提醒，已开启" else "更新提醒",
                        tint = if (reminding || reminderOpen) reminder.accent else palette.text,
                        onClick = onReminder,
                        haptic = HapticSignal.Select,
                    )
                }
                if (onRefresh != null) {
                    ScheduleTool(
                        icon = AppIcons.Refresh,
                        description = if (refreshing) "正在刷新排期" else "刷新排期",
                        tint = palette.text,
                        onClick = onRefresh,
                        enabled = !refreshing,
                    )
                }
                if (onFollow != null) {
                    if (followed) {
                        ScheduleTool(AppIcons.Remove, "取消追剧", palette.error, onFollow, haptic = HapticSignal.Tap)
                    } else {
                        Row(
                            Modifier
                                .pressable(haptic = HapticSignal.Confirm, onClick = onFollow)
                                .heightIn(min = 48.dp)
                                .padding(start = Dimens.space.sm, end = Dimens.space.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space.xs),
                        ) {
                            Icon(
                                AppIcons.Add,
                                contentDescription = null,
                                tint = accent.accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("加入追剧", style = AppTypography.body.strong, color = accent.accent, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OverlayButton("完成", onClick = overlayDismiss(onDone))
    }
}

@Composable
private fun ScheduleTool(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: HapticSignal? = null,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = if (enabled) tint else LocalPalette.current.hint,
        modifier =
            Modifier
                .pressable(enabled = enabled, haptic = haptic, onClick = onClick)
                .touchTarget()
                .size(40.dp)
                .padding(10.dp),
    )
}
