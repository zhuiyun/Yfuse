@file:OptIn(ExperimentalLayoutApi::class)

package com.yfuse.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DisclosureContent
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftAnchor
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OrbProgressDefaults
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.SKELETON_PHASE_STEP_MS
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liftAnchor
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.mediaLazyItemKey
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.searchFieldArrival
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.skeletonFill
import com.yfuse.core.designsystem.skeletonSweep
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.withArtwork
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.library.favoriteLiftAction
import com.yfuse.feature.library.liftRemainingLabel
import com.yfuse.feature.library.mediaItemLiftMenu
import com.yfuse.feature.library.playedLiftAction
import com.yfuse.feature.player.PlayerScreen
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun SearchScreen(component: SearchComponent) {
    val focusRequest by component.focusRequest.subscribeAsState()
    val stack by component.stack.subscribeAsState()
    OfficialNavDisplay(
        backStack = stack.items,
        onBack = component::navigateBack,
        contentKey = { routeKey(it.configuration) },
        modifier = Modifier.fillMaxSize(),
        isLauncher = { it.instance is SearchComponent.Child.Player },
    ) { entry ->
        val instance = entry.instance
        when (instance) {
            is SearchComponent.Child.Home ->
                SearchHomeScreen(
                    component = instance.component,
                    focusRequest = focusRequest,
                    consumeFocusRequest = component::consumeFocusRequest,
                )
            is SearchComponent.Child.Detail -> DetailScreen(instance.component)
            is SearchComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}

/** Includes the complete immutable Decompose configuration in the saved-content identity. */
private fun routeKey(configuration: SearchComponent.Config): String = "search:$configuration"

/**
 * The chips shown before anything has been typed.
 *
 * These used to sit under a 热门搜索 heading, which this app has no way to know: there is
 * no trending endpoint behind it and the list was five hard-coded words. They are offered
 * as suggestions now, which is what they always were.
 */
private val suggestedTerms = listOf("沙丘", "科幻", "悬疑", "动画", "纪录片")

/** 搜索 — `padding:52px 18px 100px; gap:20px`. */
@Composable
private fun SearchHomeScreen(
    component: SearchHomeComponent,
    focusRequest: Int,
    consumeFocusRequest: (Int) -> Boolean,
) {
    var showPeople by remember { mutableStateOf(false) }
    var compactResults by remember { mutableStateOf(true) }
    var filtersOpen by remember { mutableStateOf(false) }
    val state by component.store.states.collectAsState(component.store.state)
    val actionMessage by component.actionMessage.collectAsState()
    val palette = LocalPalette.current
    val store = component.store
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val routeVisible = LocalRouteVisible.current
    val awaitingFirstResults = state.loading && state.groups.isEmpty()
    // Every letter typed is a new state. The filtered and ranked lists are worked out once per
    // change to what they come from, not on each of the several reads a recomposition makes, and
    // the rows handed the same lists stay skippable.
    val visibleAggregated =
        remember(state.aggregated, state.type, state.sort, state.searchedQuery) { state.visibleAggregated }
    val visibleGroups = remember(state.groups, state.type) { state.visibleGroups }
    val availableTypes = remember(state.groups, state.type) { state.availableTypes }
    val visibleResultCount =
        if (state.aggregated.isNotEmpty()) visibleAggregated.size else visibleGroups.sumOf { it.items.size }
    val resultHandoff =
        rememberSearchResultsHandoff(
            state.resultsPhase(visibleResultCount),
            loading = state.loading,
            presentationKey = state.presentationKey(),
            skeleton = awaitingFirstResults,
        )
    var coverageExpanded by remember(state.searchedQuery) { mutableStateOf(false) }
    // Clears the floating dock as it is actually laid out, not a fixed 122dp that left the last
    // result under the glass with three-button navigation or large text.
    val bottomContentInset = floatingNavigationContentInset()
    StatusBarIconStyle(darkIcons = !palette.isDark)
    ScrollToTopOnReselect(component.listState)

    LaunchedEffect(focusRequest, routeVisible) {
        if (!routeVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        } else if (consumeFocusRequest(focusRequest)) {
            fieldFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Page-level light, in order: the skeleton sweep while the first results load, then the
    // handoff's own pulse and arrival sweep. Each draws only while its clock is running.
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .imeNestedScroll()
            .skeletonSweep()
            .then(resultHandoff.page),
    ) {
        LazyColumn(
            state = component.listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = Dimens.contentTop, bottom = bottomContentInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItem(key = "search-field") {
                Column {
                    SearchField(
                        query = state.query,
                        onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
                        onSubmit = { store.accept(SearchIntent.Submit) },
                        onClear = { store.accept(SearchIntent.Clear) },
                        focusRequester = fieldFocusRequester,
                        motion = resultHandoff.field,
                        iconMotion = resultHandoff.icon,
                        loading = state.loading,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            state.person?.let { person ->
                motionItem(key = "search-person-banner") {
                    PersonBanner(
                        person = person,
                        onClear = { store.accept(SearchIntent.SelectPerson(null)) },
                    )
                }
            }

            if (awaitingFirstResults) {
                motionItem(key = "search-skeleton") { SearchSkeleton() }
            }

            // Nothing typed yet: the chip row alone, no empty results heading.
            if ((state.hasSearched || state.error != null) && !awaitingFirstResults) {
                motionItem(key = "search-results-heading") {
                    Column(
                        Modifier.padding(horizontal = Dimens.pageHorizontal).then(resultHandoff.item(key = "heading")),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.yfuse.feature.library.LibraryAction(
                                if (compactResults) "显示简介" else "紧凑结果",
                            ) { compactResults = !compactResults }
                            // The store has filtered by server, library, year, genre, watched state
                            // and sort all along; only 类型 ever reached the screen.
                            val filters = state.filterCount - if (state.type != SearchType.All) 1 else 0
                            com.yfuse.feature.library.LibraryAction(
                                if (filters > 0) "筛选 · $filters" else "筛选",
                                selected = filters > 0,
                            ) { filtersOpen = true }
                        }
                        ResultsHeading(
                            count = visibleResultCount,
                            types = availableTypes,
                            selected = state.type,
                            onSelectType = { store.accept(SearchIntent.SetType(it)) },
                        )
                        if (state.unavailableGroups.isNotEmpty() || state.emptyServerCount > 0) {
                            SearchCoverageNotice(
                                unavailable = state.unavailableGroups,
                                emptyServerCount = state.emptyServerCount,
                                expanded = coverageExpanded,
                                onToggle = { coverageExpanded = !coverageExpanded },
                                onOpenServerSettings = component.onOpenServerSettings,
                            )
                        }
                    }
                }
                when {
                    state.error != null ->
                        motionItem(key = "search-results-error") {
                            ErrorState(
                                message = state.error!!,
                                onRetry = { store.accept(SearchIntent.Retry) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Dimens.pageHorizontal)
                                        .then(resultHandoff.item(key = "error")),
                                // The handoff already brings it in; its own rise on top moved it twice.
                                animateEntrance = false,
                            )
                        }

                    // 没有找到相关内容 — `400 12px Manrope`, `--pg-hint`, `padding:20px 0`.
                    visibleGroups.all { it.items.isEmpty() } && !state.loading ->
                        motionItem(key = "search-results-empty") {
                            Box(
                                Modifier
                                    .padding(horizontal = Dimens.pageHorizontal)
                                    .then(resultHandoff.item(key = "empty")),
                            ) {
                                EmptyResults(
                                    filtered = state.type != SearchType.All,
                                    onShowAllTypes = { store.accept(SearchIntent.SetType(SearchType.All)) },
                                )
                            }
                        }

                    state.aggregated.isNotEmpty() ->
                        motionItemsIndexed(
                            items = visibleAggregated,
                            key = { _, group -> group.identity },
                            contentType = { _, _ -> "aggregated-search-result" },
                        ) { index, group ->
                            val recommended = group.recommended
                            ResultRow(
                                baseUrl = component.serverBaseUrl(recommended.serverId),
                                accessToken = component.serverAccessToken(recommended.serverId),
                                serverId = recommended.serverId,
                                item = recommended.item,
                                compact = compactResults,
                                sourceSummary =
                                    if (group.copies.size > 1) {
                                        "${group.copies.size} 个片源 · 推荐 ${recommended.serverName}"
                                    } else {
                                        null
                                    },
                                onClick = {
                                    component.onOpenItem(recommended.serverId, recommended.item.id)
                                },
                                liftMenu = {
                                    searchLiftMenu(component, recommended.serverId, recommended.item, group.copies)
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Dimens.pageHorizontal)
                                        .then(resultHandoff.item(index + 1, key = group.identity)),
                            )
                        }

                    else ->
                        motionItemsIndexed(
                            items = visibleGroups,
                            key = { _, group -> "server-results-${group.serverId}" },
                            contentType = { _, _ -> "server-search-group" },
                        ) { index, group ->
                            ServerGroup(
                                group = group,
                                baseUrl = component.serverBaseUrl(group.serverId),
                                accessToken = component.serverAccessToken(group.serverId),
                                onOpenItem = {
                                    component.onOpenItem(group.serverId, it)
                                },
                                liftMenu = { item -> searchLiftMenu(component, group.serverId, item) },
                                onLoadMore = {
                                    store.accept(SearchIntent.LoadMore(group.serverId))
                                },
                                modifier =
                                    Modifier.padding(horizontal = Dimens.pageHorizontal).then(
                                        resultHandoff.item(
                                            index + 1,
                                            key = "server-results-${group.serverId}",
                                        ),
                                    ),
                            )
                        }
                }
            }

            if (state.people.isNotEmpty() && state.person == null) {
                motionItem(key = "search-people-toggle") {
                    com.yfuse.feature.library.LibraryAction(
                        if (showPeople) "收起相关人物" else "查看相关人物（${state.people.size}）",
                    ) { showPeople = !showPeople }
                }
                if (showPeople) {
                    motionItem(key = "search-people") {
                        PeopleRow(
                            people = state.people,
                            baseUrl = component::serverBaseUrl,
                            accessToken = component::serverAccessToken,
                            onSelect = { store.accept(SearchIntent.SelectPerson(it)) },
                        )
                    }
                }
            }

            if (!state.hasSearched && state.query.isBlank()) {
                motionItem(key = "search-recent") {
                    RecentSearches(
                        title = if (state.recent.isEmpty()) "试试搜索" else "搜索记录",
                        terms = state.recent.ifEmpty { suggestedTerms },
                        canEdit = state.recent.isNotEmpty(),
                        onSelect = { store.accept(SearchIntent.QueryChanged(it)) },
                        onForget = { store.accept(SearchIntent.ForgetRecent(it)) },
                        onClearAll = { store.accept(SearchIntent.ClearRecent) },
                    )
                }
            }
        }

        ActionToast(
            message = actionMessage,
            onDismiss = component::dismissMessage,
        )
    }

    if (filtersOpen) {
        SearchFilterSheet(
            state = state,
            onIntent = store::accept,
            onDismiss = { filtersOpen = false },
        )
    }
}

/**
 * 浮起菜单 on a search result. Search results are snapshots, so the flags come through the
 * component, which remembers what was changed here since the search ran. A title found on more
 * than one server lists each copy, so holding and sliding opens the one wanted.
 */
private fun searchLiftMenu(
    component: SearchHomeComponent,
    serverId: String,
    listed: MediaItem,
    copies: List<CrossServerMediaHit> = emptyList(),
): LiftMenu {
    val item = component.current(serverId, listed)
    val resumeTicks = item.resumePositionTicks?.takeIf { it > 0L && !item.played }
    // A series resolves its episode in 详情, which the card opens; it gets no play row here.
    val playable = item.type != "Series"
    return mediaItemLiftMenu(
        item = item,
        backdropUrl =
            EmbyImages.backdrop(
                component.serverBaseUrl(serverId),
                item,
                accessToken = component.serverAccessToken(serverId),
            ),
        onOpen = { component.onOpenItem(serverId, item.id) },
        actions =
            listOf(
                if (playable) {
                    listOfNotNull(
                        ItemAction(
                            label = if (resumeTicks != null) "继续播放" else "播放",
                            icon = AppIcons.Play,
                            detail = item.liftRemainingLabel(),
                            leavesPage = true,
                            onSelect = { component.onPlayItem(serverId, item.id, resumeTicks ?: 0L) },
                        ),
                        resumeTicks?.let {
                            ItemAction(
                                label = "从头播放",
                                icon = AppIcons.Refresh,
                                leavesPage = true,
                                onSelect = { component.onPlayItem(serverId, item.id, 0L) },
                            )
                        },
                    )
                } else {
                    emptyList()
                },
                listOf(
                    playedLiftAction(item.played) { component.setPlayed(serverId, listed, it) },
                    favoriteLiftAction(item.isFavorite) { component.setFavorite(serverId, listed, it) },
                ),
                copies.takeIf { it.size > 1 }.orEmpty().map { copy ->
                    ItemAction(
                        label = "在 ${copy.serverName} 打开",
                        icon = AppIcons.Server,
                        leavesPage = true,
                        onSelect = { component.onOpenItem(copy.serverId, copy.item.id) },
                    )
                },
            ),
    )
}

/**
 * 筛选 — every filter the store already understands. Choices apply at once and the sheet stays
 * up, so several can be set in one visit; 清除筛选 resets them all.
 */
@Composable
private fun SearchFilterSheet(
    state: SearchState,
    onIntent: (SearchIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "筛选", onClose = onDismiss)
        if (state.serverOptions.size > 1) {
            SearchFilterLabel("服务器")
            Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                OverlayOptionRow(
                    label = "全部服务器",
                    selected = state.serverId == null,
                    onClick = { onIntent(SearchIntent.SetServer(null)) },
                )
                state.serverOptions.forEach { option ->
                    OverlayOptionRow(
                        label = option.label,
                        selected = state.serverId == option.id,
                        onClick = { onIntent(SearchIntent.SetServer(option.id)) },
                    )
                }
            }
        }
        if (state.libraryOptions.isNotEmpty()) {
            SearchFilterLabel("媒体库")
            SearchFilterChips {
                SearchFilterChoice(
                    label = "全部",
                    selected = state.libraryId == null,
                    onClick = { onIntent(SearchIntent.SetLibrary(null)) },
                )
                state.libraryOptions.forEach { option ->
                    SearchFilterChoice(
                        label = option.label,
                        selected = state.libraryId == option.id,
                        onClick = { onIntent(SearchIntent.SetLibrary(option.id)) },
                    )
                }
            }
        }
        SearchFilterLabel("观看状态")
        SearchFilterChips {
            SearchWatchStatus.entries.forEach { status ->
                SearchFilterChoice(
                    label = status.label,
                    selected = state.watchStatus == status,
                    onClick = { onIntent(SearchIntent.SetWatchStatus(status)) },
                )
            }
        }
        SearchFilterLabel("排序")
        SearchFilterChips {
            SearchSort.entries.forEach { sort ->
                SearchFilterChoice(
                    label = sort.label,
                    selected = state.sort == sort,
                    onClick = { onIntent(SearchIntent.SetSort(sort)) },
                )
            }
        }
        SearchFilterLabel("年份")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SearchFilterChoice(
                    label = "不限",
                    selected = state.year == null,
                    onClick = { onIntent(SearchIntent.SetYear(null)) },
                )
            }
            items(state.yearOptions) { year ->
                SearchFilterChoice(
                    label = year.toString(),
                    selected = state.year == year,
                    onClick = { onIntent(SearchIntent.SetYear(year)) },
                )
            }
        }
        if (state.genreOptions.isNotEmpty()) {
            SearchFilterLabel("风格")
            SearchFilterChips {
                SearchFilterChoice(
                    label = "全部",
                    selected = state.genre == null,
                    onClick = { onIntent(SearchIntent.SetGenre(null)) },
                )
                state.genreOptions.forEach { genre ->
                    SearchFilterChoice(
                        label = genre,
                        selected = state.genre == genre,
                        onClick = { onIntent(SearchIntent.SetGenre(genre)) },
                    )
                }
            }
        }
        if (state.filterCount > 0) {
            Spacer(Modifier.height(12.dp))
            OverlayActionRow(label = "清除筛选", onClick = { onIntent(SearchIntent.ClearFilters) })
        }
    }
}

@Composable
private fun SearchFilterLabel(text: String) {
    Text(
        text,
        style = AppTypography.caption.strong,
        color = LocalPalette.current.sub2,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
}

/** Each group picks one value, so a chip is announced as a choice among its neighbours. */
@Composable
private fun SearchFilterChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    YfChip(label = label, selected = selected, onClick = onClick, role = Role.RadioButton)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterChips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * `--pg-card3` fill, `1px solid rgba(61,100,201,.4)`, `radius:20px`,
 * `padding:11px 16px`, `gap:8px`, `0 6px 18px rgba(90,120,180,.15)`.
 */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    motion: Modifier = Modifier,
    iconMotion: Modifier = Modifier,
    loading: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val shape = AppShapes.pill
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .heightIn(min = 50.dp)
            .searchFieldArrival()
            // Keep the shadow stable while loading feedback animates inside the intact field.
            .shadow(Shadows.searchBarFocused, shape)
            .then(motion)
            .glass(shape, palette.card3, accent.border)
            // The conditional clear action already owns a 48dp touch target. Vertical padding
            // here would add to that real layout height and make the field jump when text appears.
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = accent.accent, modifier = Modifier.size(15.dp).then(iconMotion))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("搜索电影、剧集、演员", style = AppTypography.body.regular, color = palette.sub2)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = AppTypography.body.regular.copy(color = palette.text),
                cursorBrush = SolidColor(accent.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = "搜索电影、剧集、演员" },
            )
        }
        // The field itself says the search is running: the same orb as every other loader,
        // at the end of the row where the answer will land.
        Box(Modifier.size(SearchFieldOrbSize), contentAlignment = Alignment.Center) {
            val reduce = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
            this@Row.AnimatedVisibility(
                visible = loading,
                enter = fadeIn(Motion.tween(if (reduce) 0 else Motion.QUICK)),
                exit = fadeOut(Motion.tween(if (reduce) 0 else Motion.QUICK)),
            ) {
                OrbProgress(size = SearchFieldOrbSize, contentDescription = "正在搜索")
            }
        }
        if (query.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "清空搜索",
                tint = palette.sub2,
                // 13dp was the smallest control in the app, and it sits at the end of a
                // text field the user is actively typing in.
                modifier =
                    Modifier
                        .pressable(onClick = onClear)
                        .touchTarget()
                        .size(13.dp),
            )
        }
    }
}

/** 共 N 条结果, with the 影片 / 剧集 narrowing beside it. */
@Composable
private fun ResultsHeading(
    count: Int,
    types: List<SearchType>,
    selected: SearchType,
    onSelectType: (SearchType) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0) "搜索结果" else "共 $count 条结果",
            style = AppTypography.caption.medium,
            color = palette.sub2,
        )
        // One type is no choice at all — the row only appears when both kinds matched.
        if (types.size > 1) {
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                types.forEach { type ->
                    YfChip(
                        label = type.label,
                        selected = type == selected,
                        onClick = { onSelectType(type) },
                        onClickLabel = "筛选 ${type.label}",
                    )
                }
            }
        }
    }
}

/** Cast matches, newest kind of result first. Tapping one opens their titles. */
@Composable
private fun PeopleRow(
    people: List<PersonHit>,
    baseUrl: (String) -> String,
    accessToken: (String) -> String,
    onSelect: (PersonHit) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Text(
            "演员",
            style = AppTypography.body.strong,
            color = palette.text,
            modifier =
                Modifier
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItems(people, key = { "${it.serverId}-${it.personId}" }) { person ->
                Column(
                    Modifier.width(64.dp).pressable { onSelect(person) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The image draws nothing when the server has no headshot, so the
                    // tinted disc underneath is what keeps the row's rhythm either way.
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(skeletonFill()),
                    ) {
                        FallbackImage(
                            urls =
                                listOf(
                                    EmbyImages.primary(
                                        baseUrl(person.serverId),
                                        person.personId,
                                        person.imageTag,
                                        maxHeight = 200,
                                        accessToken = accessToken(person.serverId),
                                    ),
                                ),
                            contentDescription = person.name,
                            // 56dp of face has nothing to resolve into.
                            progressive = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.name,
                        style = AppTypography.caption.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** What the results are, once they stopped being a title search. */
@Composable
private fun PersonBanner(
    person: PersonHit,
    onClear: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .glass(AppShapes.chip, palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${person.name} · ${person.serverName}",
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "返回搜索结果",
            style = AppTypography.caption.strong,
            color = accent.accent,
            modifier = Modifier.pressable(onClick = onClear).touchTarget(),
        )
    }
}

/** One server's block of results — its name, its state, then its titles. */
@Composable
private fun ServerGroup(
    group: ServerSearchGroup,
    baseUrl: String,
    accessToken: String,
    onOpenItem: (String) -> Unit,
    liftMenu: (MediaItem) -> LiftMenu,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (group.error == null) Brand.Online else Brand.Offline),
                )
                Text(group.serverName, style = AppTypography.body.strong, color = palette.text)
            }
            Text(
                if (group.error == null) {
                    if (group.totalCount > group.items.size) {
                        "${group.items.size} / ${group.totalCount} 部"
                    } else {
                        "${group.items.size} 部"
                    }
                } else {
                    "连接失败"
                },
                style = AppTypography.caption.strong,
                color = palette.sub2,
            )
        }
        if (group.error != null) {
            Text(
                group.error,
                style = AppTypography.caption.regular,
                color = palette.hint,
                modifier = Modifier.fillMaxWidth().glass(AppShapes.card).padding(12.dp),
            )
        } else if (group.items.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                motionItemsIndexed(
                    items = group.items,
                    key = { index, item ->
                        mediaLazyItemKey("search:${group.serverId}", index, item.id)
                    },
                ) { _, item ->
                    ResultRow(
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        serverId = group.serverId,
                        item = item,
                        onClick = { onOpenItem(item.id) },
                        liftMenu = { liftMenu(item) },
                        modifier = Modifier.width(SearchResultCardWidth),
                    )
                }
            }
        } else {
            group.items.forEach { item ->
                ResultRow(
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    serverId = group.serverId,
                    item = item,
                    onClick = { onOpenItem(item.id) },
                    liftMenu = { liftMenu(item) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (group.canLoadMore || group.loadingMore || group.loadMoreError != null) {
            val accent = LocalAccentColors.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(
                        enabled = !group.loadingMore,
                        onClickLabel = if (group.loadMoreError == null) "加载更多结果" else "重试加载更多",
                        onClick = onLoadMore,
                    ).touchTarget()
                    .glass(AppShapes.chip, palette.card2, palette.border)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.loadingMore) {
                    OrbProgress(size = OrbProgressDefaults.Inline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        group.loadingMore -> "正在加载更多…"
                        group.loadMoreError != null -> "加载失败，点击重试"
                        else -> "加载更多（还剩 ${group.totalCount - group.items.size} 部）"
                    },
                    style = AppTypography.caption.strong,
                    color = if (group.loadMoreError == null) accent.accent else palette.sub,
                )
            }
        }
    }
}

/** Keeps empty/offline servers out of the main result stream without hiding coverage. */
@Composable
private fun SearchCoverageNotice(
    unavailable: List<ServerSearchGroup>,
    emptyServerCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenServerSettings: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val hiddenCount = unavailable.size + emptyServerCount
    val disclosure = rememberDisclosureProgress(expanded)
    Column(
        Modifier
            .fillMaxWidth()
            .glass(AppShapes.card, palette.card2, palette.border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClickLabel = "查看服务器搜索状态", onClick = onToggle)
                .touchTarget()
                .padding(horizontal = 13.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Info,
                contentDescription = null,
                tint = palette.sub,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "已收起 $hiddenCount 台无结果或不可用服务器",
                style = AppTypography.caption.medium,
                color = palette.sub,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "查看",
                style = AppTypography.caption.strong,
                color = accent.accent,
            )
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = accent.accent,
                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = disclosure.value * 90f },
            )
        }
        DisclosureContent(expanded, disclosure) {
            Column(
                Modifier.padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (emptyServerCount > 0) {
                    Text(
                        "$emptyServerCount 台服务器没有匹配内容",
                        style = AppTypography.caption.regular,
                        color = palette.hint,
                    )
                }
                unavailable.forEach { group ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glass(AppShapes.chip, palette.card3, palette.border)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                    ) {
                        Text(
                            group.serverName,
                            style = AppTypography.caption.strong,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            group.error.orEmpty(),
                            style = AppTypography.caption.regular,
                            color = palette.hint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (unavailable.isNotEmpty()) {
                    Text(
                        "前往「我的」检查登录",
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .pressable(
                                    onClickLabel = "前往服务器设置重新登录",
                                    onClick = onOpenServerSettings,
                                ).touchTarget(),
                    )
                }
            }
        }
    }
}

/**
 * Nothing matched. Narrowed to one type, the way out is to widen it again, one tap from here
 * rather than back up at the chips. The search handoff already brings this in, so the hint's
 * own entrance stays off.
 */
@Composable
private fun EmptyResults(
    filtered: Boolean,
    onShowAllTypes: () -> Unit,
) {
    if (filtered) {
        PageHint(
            "这个类型下没有匹配的内容",
            modifier = Modifier.fillMaxWidth(),
            actionLabel = "查看全部类型",
            onAction = onShowAllTypes,
            icon = AppIcons.Search,
            animateEntrance = false,
        )
    } else {
        PageHint(
            "所有服务器中都没有找到相关内容\n试试片名的一部分",
            modifier = Modifier.fillMaxWidth(),
            icon = AppIcons.Search,
            animateEntrance = false,
        )
    }
}

/**
 * Result-shaped placeholders while the servers answer.
 *
 * The spinner this replaces was pinned 110dp below the status bar and floated over the
 * page, so it landed on top of whatever results were already there.
 */
@Composable
private fun SearchSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBlock(Modifier.width(72.dp).height(12.dp), shape = AppShapes.micro)
        // Rows breathe one after another down the page, and the lines inside a row after
        // its poster, so the placeholder reads as a wave rather than a blink.
        repeat(3) { row ->
            val phase = SKELETON_PHASE_STEP_MS * row
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(AppShapes.card)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                SkeletonBlock(
                    Modifier.width(SearchPosterWidth).height(SearchPosterHeight),
                    shape = AppShapes.thumb,
                    phaseMs = phase,
                )
                Column(
                    Modifier.weight(1f).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(13.dp), shape = AppShapes.micro, phaseMs = phase + 60)
                    SkeletonBlock(Modifier.width(80.dp).height(10.dp), shape = AppShapes.micro, phaseMs = phase + 120)
                    // The synopsis the real row carries; without it the placeholder is a
                    // different shape from what replaces it and the list jumps.
                    SkeletonBlock(Modifier.fillMaxWidth().height(10.dp), shape = AppShapes.micro, phaseMs = phase + 180)
                    SkeletonBlock(
                        Modifier.fillMaxWidth(0.7f).height(10.dp),
                        shape = AppShapes.micro,
                        phaseMs = phase + 240,
                    )
                }
            }
        }
    }
}

/**
 * Result row — poster, identity, and as much of the synopsis as the row can hold.
 *
 * The poster is [SearchPosterWidth]×[SearchPosterHeight]. It was 60×90, which is a
 * thumbnail: at that size a poster is a coloured rectangle, and a poster is how most
 * people recognise a title they have already seen. The synopsis is the other half —
 * search across several servers returns near-identical names (a remake, a series and its
 * film, two cuts of one title), and the two lines that say what the thing actually is
 * were only available by opening each one in turn.
 *
 * Both lines are optional and the row stays the poster's height either way, so a library
 * whose items carry no synopsis is not a page of ragged rows.
 */
@Composable
private fun ResultRow(
    baseUrl: String,
    accessToken: String,
    serverId: String,
    item: MediaItem,
    sourceSummary: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
    liftMenu: (() -> LiftMenu)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val sharedKey = MediaSharedElementKey(serverId, item.id)
    // The poster below carries the shared-element key, but the row's click never registered it,
    // so the artwork was the one poster in the app that did not grow into 详情.
    val open = sharedMediaOnClick(sharedKey, onClick)
    val artwork = remember { LiftAnchor() }
    val posterUrl = EmbyImages.poster(baseUrl, item, maxHeight = 360, accessToken = accessToken)
    Row(
        modifier
            .liftable(
                menu = liftMenu?.let { build -> { build().withArtwork(listOfNotNull(posterUrl)) } },
                anchor = artwork,
                onOpen = open,
            ).pressable(onClick = open)
            .glass(AppShapes.card)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Poster(
            url = posterUrl,
            rating = item.communityRating,
            // Results are grouped per server, and the same server added under two
            // accounts is two groups holding the same item ids. A shared-element key
            // repeated within one screen leaves one of its copies undrawn, so the group
            // it belongs to is part of the key.
            modifier =
                Modifier
                    .liftAnchor(artwork)
                    .width(
                        if (compact) 52.dp else SearchPosterWidth,
                    ).height(if (compact) 78.dp else SearchPosterHeight),
            sharedTransitionKey = sharedKey,
        )
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = if (compact) 78.dp else SearchPosterHeight)
                .padding(vertical = 2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    item.title,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Beside the title rather than centred down the right edge: on a row this
                // tall a vertically centred chip floats away from the thing it labels.
                Text(
                    if (item.type == "Series") "剧集" else "影片",
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .clip(AppShapes.thumb)
                            .background(accent.container)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            sourceSummary?.let { summary ->
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier
                        .clip(AppShapes.chip)
                        .background(accent.container)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Cloud,
                        contentDescription = null,
                        tint = accent.accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        summary,
                        style = AppTypography.caption.strong,
                        color = accent.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.subtitle,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.overview?.takeIf { !compact && it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(6.dp))
                Text(
                    overview,
                    style = AppTypography.caption.reading,
                    color = palette.hint,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Search-result poster. A 2:3 crop, large enough to be recognised rather than counted. */
private val SearchPosterWidth = 76.dp

/** The loader at the end of the field: the orb's ring needs a little more room than a 15dp glyph. */
private val SearchFieldOrbSize = 18.dp
private val SearchPosterHeight = 114.dp

/**
 * A card in a server's result reel. Widened with the poster and the synopsis: at the old
 * 270dp the two lines of copy were about ten characters each, which is a column, not a
 * sentence. The next card still peeks past the edge, which is what says the row scrolls.
 */
private val SearchResultCardWidth = 300.dp

/**
 * 搜索记录 chips, plus the suggestions shown before there is any history.
 *
 * Removing one term used to be a long-press with nothing on screen to suggest it. 编辑
 * turns the row into chips that each carry a ×, so the action is visible and still costs
 * the row no space while it is not being used. The long-press is kept for anyone who
 * already knows it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(
    title: String,
    terms: List<String>,
    canEdit: Boolean,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val palette = LocalPalette.current
    var editing by remember { mutableStateOf(false) }
    // History emptied while the row was in edit mode has nothing left to edit.
    LaunchedEffect(canEdit) { if (!canEdit) editing = false }
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppTypography.body.strong, color = palette.text)
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryAction(
                        label = if (editing) "完成" else "编辑",
                        accent = editing,
                        onClick = { editing = !editing },
                    )
                    HistoryAction(label = "清空", accent = false, onClick = onClearAll)
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            terms.forEach { term ->
                Row(
                    Modifier
                        .pressable(
                            onClickLabel = if (editing) "删除搜索记录" else "搜索",
                            onLongClick = { if (canEdit) onForget(term) },
                            onLongClickLabel = if (canEdit) "删除搜索记录" else null,
                            onClick = { if (editing) onForget(term) else onSelect(term) },
                        ).touchTarget()
                        .glass(AppShapes.chip, palette.card2)
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        term,
                        style = AppTypography.caption.medium,
                        color = palette.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (editing) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "删除「$term」",
                            tint = palette.sub2,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryAction(
    label: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accentColors = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = if (accent) accentColors.accent else palette.sub2,
        modifier =
            Modifier
                .pressable(onClick = onClick)
                .touchTarget()
                .glass(
                    shape = AppShapes.chip,
                    fill = if (accent) accentColors.container else palette.card2,
                    border = if (accent) accentColors.border else palette.border,
                ).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
