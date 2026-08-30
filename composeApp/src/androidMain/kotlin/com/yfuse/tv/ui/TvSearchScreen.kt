package com.yfuse.tv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.search.SearchHomeComponent
import com.yfuse.feature.search.SearchIntent
import com.yfuse.feature.search.SearchType
import com.yfuse.tv.focus.FocusCandidate

private val tvSearchSuggestions = listOf("科幻", "悬疑", "动画", "纪录片", "奥斯卡")

private data class TvSearchResult(
    val serverId: String,
    val serverName: String,
    val item: MediaItem,
)

@Composable
internal fun TvSearchHomeScreen(
    component: SearchHomeComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val results =
        state.visibleGroups.flatMap { group ->
            group.items.map { TvSearchResult(group.serverId, group.serverName, it) }
        }.distinctBy { it.serverId to it.item.id }
    val resultScope = "search:results:${state.searchedQuery}"
    val resultCandidates =
        results.mapIndexed { index, hit ->
            val stableId = "server:${hit.serverId}:${hit.item.id}"
            FocusCandidate(
                targetId = focusMemory.targetId(resultScope, stableId),
                sectionId = resultScope,
                itemStableId = stableId,
                index = index,
            )
        }
    val filterCandidates =
        state.availableTypes.mapIndexed { index, type ->
            val stableId = "search:type:${type.name}"
            FocusCandidate(
                targetId = focusMemory.targetId("search:filters", stableId),
                sectionId = "search:filters",
                itemStableId = stableId,
                index = index,
            )
        } +
            listOfNotNull(
                "search:clear".takeIf { state.query.isNotBlank() }?.let { stableId ->
                    FocusCandidate(
                        targetId = focusMemory.targetId("search:filters", stableId),
                        sectionId = "search:filters",
                        itemStableId = stableId,
                        index = state.availableTypes.size,
                    )
                },
            )
    val searchCandidates = filterCandidates + resultCandidates
    val resultGridState = focusMemory.gridState("search:${state.searchedQuery}")
    TvRestoreRouteFocusEffect(
        route = "search",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.loading, state.searchedQuery, results.size),
        candidates = searchCandidates,
        scrollToAnchor = { anchor ->
            if (anchor.sectionId == resultScope && resultCandidates.isNotEmpty()) {
                resultGridState.scrollToItem(anchor.fallbackIndex.coerceIn(0, resultCandidates.lastIndex))
            }
        },
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = TvSafeVertical, bottom = TvSafeVertical),
    ) {
        Text("搜索", color = TvOnSurface, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
        TvSearchField(
            query = state.query,
            onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
            onSubmit = { store.accept(SearchIntent.Submit) },
            focusRequester = contentRequester,
            navigationRequester = navigationRequester,
        )
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.availableTypes.forEach { type ->
                TvActionButton(
                    label = type.label,
                    stableId = "search:type:${type.name}",
                    focusScope = "search:filters",
                    focusMemory = focusMemory,
                    onClick = { store.accept(SearchIntent.SetType(type)) },
                    modifier = Modifier.width(116.dp),
                    selected = type == state.type,
                    navigationRequester = navigationRequester,
                    returnToNavigationOnLeft = type == SearchType.All,
                )
            }
            if (state.query.isNotBlank()) {
                TvActionButton(
                    label = "清空",
                    stableId = "search:clear",
                    focusScope = "search:filters",
                    focusMemory = focusMemory,
                    onClick = { store.accept(SearchIntent.Clear) },
                    modifier = Modifier.width(116.dp),
                    icon = AppIcons.Close,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        when {
            state.loading && results.isEmpty() -> TvLoadingState("正在搜索所有服务器")
            state.error != null && results.isEmpty() ->
                TvEmptyState(
                    title = "搜索失败",
                    description = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { store.accept(SearchIntent.Retry) },
                    focusScope = "search:error",
                    focusMemory = focusMemory,
                )
            !state.hasSearched -> {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    val terms = (state.recent + tvSearchSuggestions).distinct().take(10)
                    Text(
                        if (state.recent.isEmpty()) "搜索建议" else "最近搜索",
                        color = TvOnSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        itemsIndexed(terms, key = { _, term -> "search-term:$term" }) { index, term ->
                            TvActionButton(
                                label = term,
                                stableId = "search:term:$term",
                                focusScope = "search:terms",
                                focusMemory = focusMemory,
                                onClick = {
                                    store.accept(SearchIntent.QueryChanged(term))
                                    store.accept(SearchIntent.Submit)
                                },
                                modifier = Modifier.width(150.dp),
                                navigationRequester = navigationRequester,
                                returnToNavigationOnLeft = index == 0,
                            )
                        }
                    }
                    Text(
                        "按确定键打开电视键盘，也可以使用遥控器语音输入。",
                        color = TvOnSurfaceMuted,
                        fontSize = 14.sp,
                    )
                }
            }
            results.isEmpty() ->
                TvEmptyState(
                    title = "没有找到内容",
                    description = "换一个片名、演员或类型试试。",
                    focusScope = "search:empty-results",
                    focusMemory = focusMemory,
                )
            else -> {
                Text(
                    "找到 ${state.visibleResultCount} 个结果",
                    color = TvOnSurfaceMuted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 142.dp),
                    state = resultGridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(
                        items = results,
                        key = { _, hit -> "search:server:${hit.serverId}:${hit.item.id}" },
                    ) { index, hit ->
                        TvMediaCard(
                            model = hit.toTvCard(component),
                            focusScope = resultScope,
                            focusMemory = focusMemory,
                            navigationRequester = navigationRequester,
                            returnToNavigationOnLeft = index % 6 == 0,
                            fallbackIndex = index,
                            onFocused = {
                                val group = state.visibleGroups.firstOrNull { it.serverId == hit.serverId }
                                if (
                                    index >= results.lastIndex - 5 &&
                                    group?.canLoadMore == true &&
                                    !group.loadingMore
                                ) {
                                    store.accept(SearchIntent.LoadMore(hit.serverId))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    navigationRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        if (focused) Color.White else Color.White.copy(alpha = 0.16f),
        label = "tv-search-border",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .onFocusChanged { focused = it.hasFocus }
            .border(3.dp, border, RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else TvSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            contentDescription = null,
            tint = if (focused) Color.Black else TvOnSurfaceMuted,
        )
        Spacer(Modifier.width(14.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionLeft &&
                            query.isEmpty()
                        ) {
                            navigationRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
            singleLine = true,
            textStyle =
                TextStyle(
                    color = if (focused) Color.Black else TvOnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
            cursorBrush = SolidColor(if (focused) Color.Black else TvAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "搜索影片、剧集或演员",
                            color = if (focused) Color.Black.copy(alpha = 0.48f) else TvOnSurfaceMuted,
                            fontSize = 20.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private fun TvSearchResult.toTvCard(component: SearchHomeComponent): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "server:$serverId:${item.id}",
        title = item.title,
        subtitle = listOfNotNull(serverName, item.year?.toString()).joinToString(" · "),
        imageUrl =
            EmbyImages.poster(
                component.serverBaseUrl(serverId),
                item,
                accessToken = component.serverAccessToken(serverId),
            ),
        serverId = serverId,
        progress = item.playedPercentage?.div(100.0)?.toFloat(),
        badge = item.communityRating?.let { "%.1f".format(it) },
        onClick = { component.onOpenItem(serverId, item.id) },
    )
