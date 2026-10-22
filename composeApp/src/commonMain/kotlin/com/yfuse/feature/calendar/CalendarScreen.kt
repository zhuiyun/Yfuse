package com.yfuse.feature.calendar

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DisclosureContent
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SkeletonHandoff
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.designsystem.contentPhase
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.rememberSegmentIndicator
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.core.designsystem.flatGlass as glass

/**
 * 追剧日历 — broadcasts by day, each marked with what this library has.
 *
 * Keeps the past week available in chronological order, while the first viewport opens
 * today's broadcasts. The week navigator jumps between dates without discarding older entries.
 */
@Composable
fun CalendarScreen(component: CalendarComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val followedSeries by component.followStore.followed.collectAsState()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val days = state.visibleDays
    val weeklyStats =
        remember(state.confirmedDays, state.days, state.today) {
            // Refreshes retain the last confirmed statistic. Only a first-load preview has no
            // trustworthy library count yet, so it deliberately shows no aggregate number.
            state.confirmedDays
                .takeIf { it.isNotEmpty() }
                ?.let { calendarWeeklyStats(it, state.today) }
                ?: emptyMap()
        }
    val bottomContentInset = systemNavigationContentInset()
    val share = rememberShareHandler()
    var scheduleChanges by remember(state.loading, state.days) {
        mutableStateOf(component.scheduleChanges())
    }
    var changesExpanded by remember { mutableStateOf(false) }
    var dialogEntry by remember { mutableStateOf<CalendarEntry?>(null) }
    var dialogSeriesDays by remember { mutableStateOf<List<CalendarDay>?>(null) }
    var dialogRefreshing by remember { mutableStateOf(false) }
    val dialogScope = rememberCoroutineScope()
    // The open sheet's 刷新. It answers for one show, so it goes when that sheet does: left to land
    // later, it wrote its show's schedule under the next show's sheet, which found nothing of its
    // own there and vanished.
    var dialogRefresh by remember { mutableStateOf<Job?>(null) }
    var filtersExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(dialogEntry?.episode?.showTmdbId) {
        dialogRefresh?.cancel()
        val entry = dialogEntry ?: return@LaunchedEffect
        dialogSeriesDays = null
        dialogRefreshing = true
        component
            .seriesCalendar(
                entry = entry,
                onPreview = { preview ->
                    if (preview.isNotEmpty()) {
                        dialogSeriesDays = preview
                        dialogRefreshing = false
                    }
                },
            ).onSuccess { loaded ->
                if (loaded.isNotEmpty()) dialogSeriesDays = loaded
            }
        dialogRefreshing = false
    }

    // Unlike the artwork-heavy home hero this route always has a quiet page background.
    // Re-assert the icon contrast when navigating here; otherwise the light icons selected
    // for the hero remain white on this light full-screen page.
    StatusBarIconStyle(darkIcons = !palette.isDark)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "返回", onClick = component.onBack)
                            .touchTarget()
                            .size(36.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                )
                Text(
                    "追剧中心",
                    style = AppTypography.section.strong,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                // The schedule is cached for the day, so nothing else re-reads it; a new
                // download landing is exactly when someone wants 未入库 → 可播放 checked
                // again, and only they know it happened.
                Box(
                    Modifier
                        .pressable(
                            enabled = !state.loading,
                            onClickLabel = "刷新追剧日历",
                        ) { component.store.accept(CalendarIntent.Refresh) }
                        .touchTarget()
                        .size(36.dp)
                        .solidGlass(CircleShape, palette.card2, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineLoadingContent(loading = state.loading, slotSize = 15.dp, color = accent.accent) {
                        Icon(
                            AppIcons.Refresh,
                            contentDescription = "刷新",
                            tint = palette.text,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            CalendarSectionBar(
                selected = state.section,
                onSelect = { component.store.accept(CalendarIntent.SelectSection(it)) },
            )
            Spacer(Modifier.height(12.dp))

            if (scheduleChanges.isNotEmpty() && state.section == CalendarSection.Schedule) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.pageHorizontal)
                        .glass(AppShapes.card, palette.card2, palette.border)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().pressable { changesExpanded = !changesExpanded }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(AppIcons.WatchCalendar, null, tint = accent.accent, modifier = Modifier.size(17.dp))
                        Text(
                            "排期更新 · ${scheduleChanges.size} 条调整",
                            style = AppTypography.caption.strong,
                            color = palette.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            if (changesExpanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                            contentDescription = if (changesExpanded) "收起排期调整" else "展开排期调整",
                            tint = accent.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    DisclosureContent(changesExpanded, rememberDisclosureProgress(changesExpanded)) {
                        Column(
                            Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            scheduleChanges.forEach { change ->
                                Text(
                                    "${change.title} · ${change.displayMessage}",
                                    style = AppTypography.caption.regular,
                                    color = palette.sub,
                                )
                            }
                        }
                        Text(
                            "知道了",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .pressable {
                                        component.acknowledgeScheduleChanges()
                                        scheduleChanges = emptyList()
                                        changesExpanded = false
                                    }.touchTarget(),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Only what the section shows hands over, keyed by the section. The back key, 刷新 and
            // the section bar used to fade in with it: the handoff covered the whole page.
            Column(Modifier.fillMaxSize().contentHandoff(state.section)) {
                if (state.section == CalendarSection.Schedule) {
                    CalendarWeekHeader(
                        days = state.days,
                        today = state.today,
                        filter = state.filter,
                        filtersExpanded = filtersExpanded,
                        error = state.error,
                        hasConfirmedData = state.confirmedDays.isNotEmpty(),
                        onToggleFilters = { filtersExpanded = !filtersExpanded },
                        onRetry = { component.store.accept(CalendarIntent.Refresh) },
                    )
                    val filterProgress = rememberDisclosureProgress(filtersExpanded)
                    DisclosureContent(filtersExpanded, filterProgress) {
                        LazyRow(
                            modifier = Modifier.selectableGroup(),
                            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            motionItems(CalendarFilter.entries) { filter ->
                                val active = filter == state.filter
                                YfChip(
                                    label = filter.label,
                                    selected = active,
                                    onClick = {
                                        component.store.accept(CalendarIntent.SelectFilter(filter))
                                        filtersExpanded = false
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    SkeletonHandoff(
                        loading = state.loading && days.isEmpty(),
                        // Within the schedule only the results hand over as it loads, empties or
                        // fails; the week header and its filters stay where they are.
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .contentHandoff(contentPhase(state.loading, days.isNotEmpty(), state.error != null)),
                        skeleton = { CalendarLoadingContent() },
                    ) {
                        when {
                            state.error != null && days.isEmpty() ->
                                ErrorState(
                                    message = state.error!!,
                                    onRetry = { component.store.accept(CalendarIntent.Refresh) },
                                    modifier = Modifier.align(Alignment.Center),
                                )

                            state.filteredToNothing ->
                                PageHint(
                                    "这段时间「${state.filter.label}」没有更新",
                                    Modifier.align(Alignment.Center),
                                    actionLabel = "查看全部",
                                    onAction = {
                                        component.store.accept(CalendarIntent.SelectFilter(CalendarFilter.All))
                                    },
                                )

                            days.isEmpty() ->
                                PageHint(
                                    "这段时间没有查到在播剧集",
                                    Modifier.align(Alignment.Center),
                                    actionLabel = "刷新",
                                    onAction = { component.store.accept(CalendarIntent.Refresh) },
                                )

                            else ->
                                AdaptiveCalendarResults(
                                    days = days,
                                    today = state.today,
                                    filter = state.filter,
                                    weeklyStats = weeklyStats,
                                    reduceMotion = reduceMotion,
                                    bottomContentInset = bottomContentInset,
                                    onOpen = { entry -> dialogEntry = entry },
                                    liftMenu = { display ->
                                        val entry = display.entry
                                        calendarEntryLiftMenu(
                                            display = display,
                                            onOpenCalendar = { dialogEntry = entry },
                                            onOpenInLibrary =
                                                entry.openItemId?.let { itemId ->
                                                    { component.onOpenItem(entry.serverId, itemId) }
                                                },
                                            onFollow =
                                                if (entry.followed || entry.episode.showTmdbId <= 0) {
                                                    null
                                                } else {
                                                    { component.toggleFollow(entry) }
                                                },
                                        )
                                    },
                                )
                        }
                    }
                } else {
                    CalendarAuxiliaryPane(
                        state = state,
                        followedSeries = followedSeries,
                        component = component,
                        bottomContentInset = bottomContentInset,
                        onExportCalendar = { share.shareCalendar(component.exportCalendar(state.days)) },
                        onExportDiagnostics = {
                            share.shareText(component.diagnosticReport(state.days))
                        },
                    )
                }
            }
        }
        dialogEntry?.let { entry ->
            val followed = followedSeries.firstOrNull { it.tmdbId == entry.episode.showTmdbId }
            AiringShowCalendarDialog(
                initialEntry = entry,
                days = dialogSeriesDays ?: state.days,
                today = state.today,
                followedSeries = followed,
                refreshing = dialogRefreshing,
                onOpen = { target ->
                    target.openItemId?.let { itemId ->
                        component.onOpenItem(target.serverId, itemId)
                        dialogEntry = null
                    }
                },
                onToggleFollow = { component.toggleFollow(entry) },
                onReminderChanged = { mode, minutes ->
                    component.setReminder(entry.episode.showTmdbId, mode, minutes)
                },
                onRefresh = {
                    dialogRefresh?.cancel()
                    dialogRefresh =
                        dialogScope.launch {
                            dialogRefreshing = true
                            val refreshed = component.seriesCalendar(entry, forceRefresh = true)
                            // Cancelled with its sheet, and checked as well: only that show's sheet
                            // may take the answer, or put its spinner away.
                            if (dialogEntry?.episode?.showTmdbId != entry.episode.showTmdbId) return@launch
                            refreshed.onSuccess { loaded ->
                                if (loaded.isNotEmpty()) dialogSeriesDays = loaded
                            }
                            dialogRefreshing = false
                        }
                },
                onDismiss = {
                    dialogRefresh?.cancel()
                    dialogEntry = null
                    dialogSeriesDays = null
                },
            )
        }
    }
}

@Composable
private fun CalendarSectionBar(
    selected: CalendarSection,
    onSelect: (CalendarSection) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val indicator =
        rememberSegmentIndicator(
            CalendarSection.entries.indexOf(selected),
            accent.container,
            Color.White.copy(alpha = 0.8f),
        )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .glass(AppShapes.pill, palette.card2, palette.border)
            .padding(4.dp)
            .selectableGroup()
            .then(indicator.container),
    ) {
        CalendarSection.entries.forEach { section ->
            val active = selected == section
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .then(indicator.item(CalendarSection.entries.indexOf(section)))
                    .pressable(role = Role.RadioButton) { onSelect(section) }
                    .semantics { this.selected = active }
                    .touchTarget(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    section.label,
                    style = AppTypography.body.strong,
                    color = selectionColor(if (active) accent.accent else palette.sub),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CalendarAuxiliaryPane(
    state: CalendarState,
    followedSeries: List<FollowedSeries>,
    component: CalendarComponent,
    bottomContentInset: androidx.compose.ui.unit.Dp,
    onExportCalendar: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    when (state.section) {
        CalendarSection.Schedule -> Unit
        CalendarSection.Tracking ->
            CalendarTrackingPane(
                followedSeries = followedSeries,
                calendarDays = state.confirmedDays.ifEmpty { state.days },
                component = component,
                bottomContentInset = bottomContentInset,
            )
        CalendarSection.Resources ->
            CalendarResourcesPane(
                days = state.confirmedDays.ifEmpty { state.days },
                component = component,
                bottomContentInset = bottomContentInset,
            )
        CalendarSection.Settings ->
            CalendarSettingsPane(
                state = state,
                component = component,
                bottomContentInset = bottomContentInset,
                onExportCalendar = onExportCalendar,
                onExportDiagnostics = onExportDiagnostics,
            )
    }
}
