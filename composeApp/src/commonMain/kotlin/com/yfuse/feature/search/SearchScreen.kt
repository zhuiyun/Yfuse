package com.yfuse.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.motionAwareItem
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SkeletonBlock
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.skeletonFill
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun SearchScreen(component: SearchComponent) {
    val focusRequest by component.focusRequest.subscribeAsState()
    val stack by component.stack.subscribeAsState()
    OfficialNavDisplay(
        backStack = stack.items,
        onBack = component::navigateBack,
        contentKey = { routeKey(it.configuration) },
        modifier = Modifier.fillMaxSize(),
    ) { entry ->
        val instance = entry.instance
        when (instance) {
            is SearchComponent.Child.Home -> SearchHomeScreen(
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
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val store = component.store
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val routeVisible = LocalRouteVisible.current
    var filterSheet by remember { mutableStateOf<SearchFilterSheet?>(null) }
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = component.listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
                    onSubmit = { store.accept(SearchIntent.Submit) },
                    onClear = { store.accept(SearchIntent.Clear) },
                    focusRequester = fieldFocusRequester,
                )
            }
            item {
                SearchFilterBar(
                    state = state,
                    onOpen = { filterSheet = it },
                    onClear = { store.accept(SearchIntent.ClearFilters) },
                )
                filterSheet?.let { sheet ->
                    SearchFilterDialog(
                        state = state,
                        sheet = sheet,
                        onIntent = store::accept,
                        onDismiss = { filterSheet = null },
                    )
                }
            }

            // 演员 sits above the titles: a cast match is a different kind of answer, and
            // a title search can never surface it — `/Items` matches item names only.
            if (state.people.isNotEmpty() && state.person == null) {
                item {
                    PeopleRow(
                        people = state.people,
                        baseUrl = component::serverBaseUrl,
                        accessToken = component::serverAccessToken,
                        onSelect = { store.accept(SearchIntent.SelectPerson(it)) },
                    )
                }
            }

            state.person?.let { person ->
                item {
                    PersonBanner(
                        person = person,
                        onClear = { store.accept(SearchIntent.SelectPerson(null)) },
                    )
                }
            }

            val awaitingFirstResults = state.loading && state.groups.isEmpty()
            if (awaitingFirstResults) {
                item { SearchSkeleton() }
            }

            // Nothing typed yet: the chip row alone, no empty results heading.
            if ((state.hasSearched || state.error != null) && !awaitingFirstResults) {
                item {
                    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
                        ResultsHeading(
                            count = state.visibleCount,
                            types = state.availableTypes,
                            selected = state.type,
                            onSelectType = { store.accept(SearchIntent.SetType(it)) },
                        )
                        when {
                            state.error != null -> ErrorState(
                                message = state.error!!,
                                onRetry = { store.accept(SearchIntent.Retry) },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            // 没有找到相关内容 — `400 12px Manrope`, `--pg-hint`, `padding:20px 0`.
                            state.visibleGroups.all { it.items.isEmpty() } && !state.loading ->
                                EmptyResults(filtered = state.type != SearchType.All)

                            else -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                state.visibleGroups.forEach { group ->
                                    ServerGroup(
                                        group = group,
                                        baseUrl = component.serverBaseUrl(group.serverId),
                                        accessToken = component.serverAccessToken(group.serverId),
                                        onOpenItem = {
                                            component.onOpenItem(group.serverId, it)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!state.hasSearched && state.query.isBlank()) {
                item {
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
    }
}

/**
 * `--pg-card3` fill, `1px solid rgba(61,100,201,.4)`, `radius:20px`,
 * `padding:11px 16px`, `gap:8px`, `0 6px 18px rgba(90,120,180,.15)`.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val shape = AppShapes.pill
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .heightIn(min = 50.dp)
            .shadow(Shadows.searchBarFocused, shape)
            .glass(shape, palette.card3, accent.border)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = accent.accent, modifier = Modifier.size(15.dp))
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
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "搜索电影、剧集、演员" },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "清空搜索",
                tint = palette.sub2,
                // 13dp was the smallest control in the app, and it sits at the end of a
                // text field the user is actively typing in.
                modifier = Modifier
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
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
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
                    TypeChip(
                        label = type.label,
                        selected = type == selected,
                        onClick = { onSelectType(type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        label,
        style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
        color = if (selected) accent.accent else palette.body,
        modifier = Modifier
            .pressable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .touchTarget()
            .glass(
                shape = GlassShapes.chip,
                fill = if (selected) accent.container else palette.card2,
                border = if (selected) accent.border else palette.border,
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
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
            modifier = Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(people, key = { "${it.serverId}-${it.personId}" }) { person ->
                Column(
                    // Cast lands after the titles do, so the row grows into place.
                    Modifier.width(64.dp).then(motionAwareItem()).pressable { onSelect(person) },
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
                            urls = listOf(
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
private fun PersonBanner(person: PersonHit, onClear: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .glass(GlassShapes.chip, palette.card2, palette.border)
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
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (group.error == null) "${group.items.size} 部" else "连接失败",
                style = AppTypography.caption.strong,
                color = palette.sub2,
            )
        }
        if (group.error != null) {
            Text(
                group.error,
                style = AppTypography.caption.regular,
                color = palette.hint,
                modifier = Modifier.fillMaxWidth().glass(GlassShapes.card).padding(12.dp),
            )
        } else if (group.items.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(group.items, key = { it.id }) { item ->
                    ResultRow(
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        serverId = group.serverId,
                        item = item,
                        onClick = { onOpenItem(item.id) },
                        modifier = Modifier.width(270.dp).then(motionAwareItem()),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyResults(filtered: Boolean) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Search, null, tint = palette.hint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(9.dp))
        Text(
            if (filtered) {
                "这个类型下没有匹配的内容\n换一个类型再看看"
            } else {
                "所有服务器中都没有找到相关内容\n试试片名的一部分"
            },
            style = AppTypography.caption.regular.copy(lineHeight = 19.5.sp),
            color = palette.hint,
            textAlign = TextAlign.Center,
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
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBlock(Modifier.width(72.dp).height(12.dp), shape = AppShapes.micro)
        repeat(3) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(GlassShapes.card)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                SkeletonBlock(Modifier.width(60.dp).height(90.dp), shape = AppShapes.thumb)
                Column(
                    Modifier.weight(1f).padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(13.dp), shape = AppShapes.micro)
                    SkeletonBlock(Modifier.width(80.dp).height(10.dp), shape = AppShapes.micro)
                }
            }
        }
    }
}

/**
 * Result row — exact 56×80 poster, 11px gap, 8px inset and compact two-line identity.
 */
@Composable
private fun ResultRow(
    baseUrl: String,
    accessToken: String,
    serverId: String,
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        modifier
            .pressable(onClick = onClick)
            .glass(GlassShapes.card)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Poster(
            url = EmbyImages.poster(baseUrl, item, maxHeight = 360, accessToken = accessToken),
            // Results are grouped per server, and the same server added under two
            // accounts is two groups holding the same item ids. A shared-element key
            // repeated within one screen leaves one of its copies undrawn, so the group
            // it belongs to is part of the key.
            modifier = Modifier.width(60.dp).height(90.dp),
        )
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(
                item.title,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        }
        Text(
            if (item.type == "Series") "剧集" else "影片",
            style = AppTypography.caption.strong,
            color = accent.accent,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .clip(AppShapes.thumb)
                .background(accent.container)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

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
                        )
                        .touchTarget()
                        .glass(GlassShapes.chip, palette.card2)
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
private fun HistoryAction(label: String, accent: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accentColors = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = if (accent) accentColors.accent else palette.sub2,
        modifier = Modifier
            .pressable(onClick = onClick)
            .touchTarget()
            .glass(
                shape = GlassShapes.chip,
                fill = if (accent) accentColors.container else palette.card2,
                border = if (accent) accentColors.border else palette.border,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
