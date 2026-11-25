package com.yfuse.feature.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.InlineLoadingContent
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SkeletonHandoff
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.util.rememberShareHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 追剧日历 — broadcasts by day, each marked with what this library has.
 *
 * Date navigation selects a day's featured poster and compact updates, using the app theme.
 */
@Composable
fun CalendarScreen(component: CalendarComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val followedSeries by component.followStore.followed.collectAsState()
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val days = state.visibleDays
    val dayDates = remember(days) { days.map(CalendarDay::date) }
    var selectedDate by remember(state.today) { mutableStateOf(state.today) }
    LaunchedEffect(state.filter, state.platform, state.contentFilter, dayDates) {
        selectedDate = calendarDateAfterFiltering(days, state.today, selectedDate, state.filter)
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

    LaunchedEffect(dialogEntry?.episode?.titleKey) {
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
                    .padding(top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    AppIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = palette.text,
                    modifier =
                        Modifier
                            .pressableTarget(CircleShape, onClickLabel = "返回", onClick = component.onBack)
                            .size(34.dp)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(9.dp),
                )
                Text(
                    "追剧日历",
                    style = AppTypography.section.strong,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                if (state.section == CalendarSection.Schedule) {
                    Row(
                        Modifier
                            .clip(AppShapes.pill)
                            .background(
                                if (filtersExpanded || state.filter != CalendarFilter.All) {
                                    accent.accent.copy(alpha = if (palette.isDark) 0.22f else 0.12f)
                                } else {
                                    palette.text.copy(alpha = if (palette.isDark) 0.09f else 0.06f)
                                },
                            ).pressable(
                                role = Role.Button,
                                onClickLabel = if (filtersExpanded) "收起日历筛选" else "展开日历筛选",
                                focusShape = AppShapes.pill,
                                onClick = { filtersExpanded = !filtersExpanded },
                            ).heightIn(min = 34.dp)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            state.filter.label,
                            style = AppTypography.caption.strong,
                            color = if (filtersExpanded || state.filter != CalendarFilter.All) accent.accent else palette.text,
                        )
                        Icon(
                            AppIcons.ChevronDown,
                            contentDescription = null,
                            tint = if (filtersExpanded || state.filter != CalendarFilter.All) accent.accent else palette.sub,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .pressableTarget(
                            CircleShape,
                            enabled = !state.loading,
                            onClickLabel = "刷新追剧日历",
                        ) { component.store.accept(CalendarIntent.Refresh) }
                        .size(34.dp)
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
            Spacer(Modifier.height(6.dp))

            Column(Modifier.fillMaxSize()) {
                if (state.section == CalendarSection.Schedule) {
                    if (state.error != null && days.isNotEmpty()) {
                        Text(
                            if (state.confirmedDays.isNotEmpty()) {
                                "状态更新失败，正在显示上次结果 · 点击重试"
                            } else {
                                "媒体库状态待确认 · 点击重试"
                            },
                            style = AppTypography.caption.medium,
                            color = palette.error,
                            modifier =
                                Modifier
                                    .padding(horizontal = Dimens.pageHorizontal, vertical = 4.dp)
                                    .pressable(onClickLabel = "重新加载追剧日历", onClick = {
                                        component.store.accept(CalendarIntent.Refresh)
                                    }).touchTarget(),
                        )
                    }
                    if (scheduleChanges.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.pageHorizontal, vertical = 2.dp)
                                .pressable(
                                    role = Role.Button,
                                    onClickLabel = if (changesExpanded) "收起排期调整" else "查看 ${scheduleChanges.size} 条排期调整",
                                    onClick = { changesExpanded = !changesExpanded },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "${scheduleChanges.size} 条排期调整",
                                style = AppTypography.caption.medium,
                                color = accent.accent,
                            )
                            Icon(
                                if (changesExpanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = accent.accent,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    DisclosureContent(
                        expanded = changesExpanded && scheduleChanges.isNotEmpty(),
                        progress = rememberDisclosureProgress(changesExpanded && scheduleChanges.isNotEmpty()),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.pageHorizontal)
                                .padding(bottom = 8.dp),
                        ) {
                            Column(
                                Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                scheduleChanges.forEach { change ->
                                    Text(
                                        "${change.title} · ${change.displayMessage}",
                                        style = AppTypography.caption.reading,
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
                    val filterProgress = rememberDisclosureProgress(filtersExpanded)
                    DisclosureContent(filtersExpanded, filterProgress) {
                        LazyRow(
                            modifier = Modifier.selectableGroup(),
                            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(CalendarFilter.entries, key = { it.name }) { filter ->
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
                        Spacer(Modifier.height(6.dp))
                    }

                    SkeletonHandoff(
                        loading = state.loading && days.isEmpty(),
                        modifier = Modifier.fillMaxSize(),
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
                                        component.store.accept(CalendarIntent.ResetFilters)
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
                                    selectedDate = selectedDate,
                                    onSelectDate = { date ->
                                        selectedDate = date
                                        if (state.filter == CalendarFilter.Today && date != state.today) {
                                            component.store.accept(CalendarIntent.SelectFilter(CalendarFilter.All))
                                        }
                                    },
                                    bottomContentInset = bottomContentInset,
                                    onOpen = { entry -> dialogEntry = entry },
                                    onPlay = { entry ->
                                        if (entry.canOpenCalendarPlayback()) {
                                            entry.itemId?.let { component.onPlayItem(entry.serverId, it) }
                                        }
                                    },
                                    onFollow = component::toggleFollow,
                                    backdrop = component::featuredBackdrop,
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
                                                if (entry.followed || !entry.episode.canFollow) {
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
            val followed =
                followedSeries.firstOrNull {
                    entry.episode.canFollow && it.tmdbId == entry.episode.showTmdbId
                }
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
                    if (entry.episode.canFollow) component.setReminder(entry.episode.showTmdbId, mode, minutes)
                },
                onRefresh = {
                    dialogRefresh?.cancel()
                    dialogRefresh =
                        dialogScope.launch {
                            dialogRefreshing = true
                            val refreshed = component.seriesCalendar(entry, forceRefresh = true)
                            // Cancelled with its sheet, and checked as well: only that show's sheet
                            // may take the answer, or put its spinner away.
                            if (dialogEntry?.episode?.titleKey != entry.episode.titleKey) return@launch
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
    val trackFill = palette.text.copy(alpha = if (palette.isDark) 0.10f else 0.06f)
    val trackBorder = palette.text.copy(alpha = if (palette.isDark) 0.14f else 0.08f)
    val activeFill = palette.card3.compositeOver(palette.background)
    val activeBorder = palette.text.copy(alpha = if (palette.isDark) 0.16f else 0.10f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .clip(AppShapes.pill)
            .background(trackFill)
            .border(0.75.dp, trackBorder, AppShapes.pill)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CalendarSection.entries.forEach { section ->
            val active = selected == section
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(AppShapes.pill)
                    .then(
                        if (active) {
                            Modifier
                                .background(activeFill)
                                .border(0.75.dp, activeBorder, AppShapes.pill)
                        } else {
                            Modifier
                        },
                    ).pressable(
                        haptic = HapticSignal.Select,
                        role = Role.RadioButton,
                        focusShape = AppShapes.pill,
                        onClick = { onSelect(section) },
                    ).semantics { this.selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    section.label,
                    style = if (active) AppTypography.caption.strong else AppTypography.caption.medium,
                    color = if (active) accent.accent else palette.sub,
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
