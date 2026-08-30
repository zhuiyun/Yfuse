package com.yfuse.tv.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.library.GridIntent
import com.yfuse.feature.library.LibraryGridComponent
import com.yfuse.feature.library.LibraryHomeComponent
import com.yfuse.feature.library.LibraryIntent
import com.yfuse.tv.focus.FocusContext
import com.yfuse.tv.focus.FocusCandidate

@Composable
internal fun TvLibraryHomeScreen(
    component: LibraryHomeComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val server = state.currentServer
    val store = component.store
    TvRestoreRouteFocusEffect(
        route = "library",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.loading, server?.id, state.content.featured.firstOrNull()?.id),
        context = server?.let { FocusContext("library", it.id, it.userId) },
    )

    if (server == null) {
        TvEmptyState(
            title = "还没有媒体服务器",
            description = "从左侧进入服务器，连接 Emby、Jellyfin 或 Plex。",
            actionLabel = "返回导航",
            onAction = { navigationRequester.requestFocus() },
            focusScope = "library:empty",
            focusMemory = focusMemory,
            focusRequester = contentRequester,
            navigationRequester = navigationRequester,
        )
        return
    }
    if (state.loading && state.content.isEmpty) {
        TvLoadingState("正在读取 ${server.serverName}")
        return
    }

    val featured = state.content.featured.firstOrNull()
    LazyColumn(
        state = component.listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvSafeVertical, bottom = TvSafeVertical + 32.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp),
    ) {
        item(key = "library-server-selector") {
            TvLibraryServerSelector(
                servers = state.servers,
                selectedId = server.id,
                onSelect = { store.accept(LibraryIntent.SelectServer(it)) },
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
            )
        }
        if (featured != null) {
            item(key = "library-hero:${server.kind.name}:${server.id}:${featured.id}") {
                TvLibraryHero(
                    item = featured,
                    server = server,
                    onOpen = { component.onOpenItem(featured.id) },
                    onPlay = { component.onPlayItem(featured.id) },
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    contentRequester = contentRequester,
                )
            }
        }
        if (state.content.resume.isNotEmpty()) {
            item(key = "library-resume") {
                TvMediaRow(
                    title = "继续观看",
                    sectionKey = "library:${server.id}:resume",
                    items =
                        state.content.resume.map { media ->
                            media.toLibraryTvCard(server, landscape = true) {
                                component.onOpenItem(media.id)
                            }
                        },
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        state.content.rows.forEach { row ->
            if (row.items.isNotEmpty()) {
                item(key = "library-row:${server.id}:${row.libraryId}:${row.title}") {
                    TvMediaRow(
                        title = row.title,
                        sectionKey = "library:${server.id}:${row.libraryId}",
                        items =
                            row.items.map { media ->
                                media.toLibraryTvCard(server) { component.onOpenItem(media.id) }
                            },
                        focusMemory = focusMemory,
                        navigationRequester = navigationRequester,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onSeeAll = { component.onSeeAll(row.libraryId, row.title) },
                    )
                }
            }
        }
        if (state.error != null && state.content.isEmpty) {
            item(key = "library-error") {
                TvEmptyState(
                    title = "媒体库加载失败",
                    description = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { store.accept(LibraryIntent.Retry) },
                    focusScope = "library:error",
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                )
            }
        }
    }
}

@Composable
private fun TvLibraryServerSelector(
    servers: List<SavedServer>,
    selectedId: String,
    onSelect: (String) -> Unit,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("媒体库", color = TvOnSurface, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(12.dp))
        servers.take(5).forEachIndexed { index, server ->
            TvActionButton(
                label = server.serverName,
                stableId = "library:server:${server.kind.name.lowercase()}:${server.id}",
                focusScope = "library:servers",
                focusMemory = focusMemory,
                onClick = { onSelect(server.id) },
                modifier = Modifier.width(150.dp),
                selected = server.id == selectedId,
                navigationRequester = navigationRequester,
                returnToNavigationOnLeft = index == 0,
                serverId = server.id,
                profileId = server.userId,
            )
        }
    }
}

@Composable
private fun TvLibraryHero(
    item: MediaItem,
    server: SavedServer,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(350.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151B25)),
    ) {
        AsyncImage(
            model =
                EmbyImages.backdrop(server.baseUrl, item, accessToken = server.accessToken)
                    ?: EmbyImages.poster(server.baseUrl, item, accessToken = server.accessToken),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.58f to Color.Black.copy(alpha = 0.26f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(520.dp)
                .padding(start = 34.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 35.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text =
                    listOfNotNull(
                        item.year?.toString(),
                        item.runtimeMinutes?.let { "$it 分钟" },
                        item.communityRating?.let { "%.1f 分".format(it) },
                    ).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = item.overview.orEmpty(),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                lineHeight = 23.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = if ((item.playedPercentage ?: 0.0) > 0.0) "继续播放" else "播放",
                    stableId = "library:hero:play:${item.id}",
                    focusScope = "library:hero",
                    focusMemory = focusMemory,
                    onClick = onPlay,
                    modifier = Modifier.width(170.dp),
                    icon = AppIcons.Play,
                    primary = true,
                    focusRequester = contentRequester,
                    navigationRequester = navigationRequester,
                    returnToNavigationOnLeft = true,
                    serverId = server.id,
                    profileId = server.userId,
                )
                TvActionButton(
                    label = "详情",
                    stableId = "library:hero:detail:${item.id}",
                    focusScope = "library:hero",
                    focusMemory = focusMemory,
                    onClick = onOpen,
                    modifier = Modifier.width(150.dp),
                    icon = AppIcons.Info,
                    serverId = server.id,
                    profileId = server.userId,
                )
            }
        }
    }
}

@Composable
internal fun TvLibraryGridScreen(
    component: LibraryGridComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val backRequester = remember { FocusRequester() }
    val gridScope = "grid:${component.title}"
    val backStableId = "grid:back:${component.title}"
    val contentCandidates =
        if (state.directoryKind != null) {
            state.containers.mapIndexed { index, container ->
                val stableId = "server:${container.serverId}:container:${container.id}"
                FocusCandidate(
                    targetId = focusMemory.targetId(gridScope, stableId),
                    sectionId = gridScope,
                    itemStableId = stableId,
                    index = index,
                )
            }
        } else {
            state.items.mapIndexed { index, item ->
                val stableId = "server:${component.serverId}:${item.id}"
                FocusCandidate(
                    targetId = focusMemory.targetId(gridScope, stableId),
                    sectionId = gridScope,
                    itemStableId = stableId,
                    index = index,
                )
            }
        }
    val filterStableIds =
        buildList {
            if (state.sortable) addAll(LibrarySort.entries.map { "grid:sort:${it.name}" })
            if (state.resolutionFilterable) {
                addAll(
                    listOf(
                        LibraryResolution.All,
                        LibraryResolution.FourK,
                        LibraryResolution.DolbyVision,
                    ).map { "grid:resolution:${it.name}" },
                )
            }
        }
    val gridCandidates =
        listOf(
            FocusCandidate(
                targetId = focusMemory.targetId("$gridScope:header", backStableId),
                sectionId = "$gridScope:header",
                itemStableId = backStableId,
                index = 0,
            ),
        ) +
            filterStableIds.mapIndexed { index, stableId ->
                FocusCandidate(
                    targetId = focusMemory.targetId("$gridScope:filters", stableId),
                    sectionId = "$gridScope:filters",
                    itemStableId = stableId,
                    index = index,
                )
            } + contentCandidates
    TvRestoreRouteFocusEffect(
        route = "grid",
        focusMemory = focusMemory,
        fallback = backRequester,
        contentGeneration = listOf(component.title, state.loadedCount, state.sort, state.resolution),
        context = component.serverId?.let { FocusContext("grid", it) },
        candidates = gridCandidates,
        scrollToAnchor = { anchor ->
            if (anchor.sectionId == gridScope && contentCandidates.isNotEmpty()) {
                component.gridState.scrollToItem(
                    anchor.fallbackIndex.coerceIn(0, contentCandidates.lastIndex),
                )
            }
        },
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvActionButton(
                label = "返回",
                stableId = backStableId,
                focusScope = "$gridScope:header",
                focusMemory = focusMemory,
                onClick = component.onBack,
                modifier = Modifier.width(120.dp),
                icon = AppIcons.ChevronLeft,
                focusRequester = backRequester,
                serverId = component.serverId,
            )
            Column {
                Text(component.title, color = TvOnSurface, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "已加载 ${state.loadedCount} / ${state.totalCount}",
                    color = TvOnSurfaceMuted,
                    fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.sortable || state.resolutionFilterable) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (state.sortable) {
                    LibrarySort.entries.forEach { sort ->
                        TvActionButton(
                            label =
                                when (sort) {
                                    LibrarySort.RecentlyAdded -> "最近添加"
                                    LibrarySort.Name -> "名称"
                                    LibrarySort.Year -> "年份"
                                    LibrarySort.Rating -> "评分"
                                },
                            stableId = "grid:sort:${sort.name}",
                            focusScope = "$gridScope:filters",
                            focusMemory = focusMemory,
                            onClick = { store.accept(GridIntent.SetSort(sort)) },
                            modifier = Modifier.width(122.dp),
                            selected = sort == state.sort,
                            serverId = component.serverId,
                        )
                    }
                }
                if (state.resolutionFilterable) {
                    listOf(LibraryResolution.All, LibraryResolution.FourK, LibraryResolution.DolbyVision).forEach {
                        resolution ->
                        TvActionButton(
                            label = resolution.label,
                            stableId = "grid:resolution:${resolution.name}",
                            focusScope = "$gridScope:filters",
                            focusMemory = focusMemory,
                            onClick = { store.accept(GridIntent.SetResolution(resolution)) },
                            modifier = Modifier.width(112.dp),
                            selected = resolution == state.resolution,
                            serverId = component.serverId,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            state.loading && state.loadedCount == 0 -> TvLoadingState()
            state.error != null && state.loadedCount == 0 ->
                TvEmptyState(
                    title = "内容加载失败",
                    description = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { store.accept(GridIntent.Retry) },
                    focusScope = "grid:${component.title}:error",
                    focusMemory = focusMemory,
                )
            else -> {
                val totalCards = if (state.directoryKind != null) state.containers.size else state.items.size
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 142.dp),
                    state = component.gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(17.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (state.directoryKind != null) {
                        itemsIndexed(
                            items = state.containers,
                            key = { _, container -> "container:server:${container.serverId}:${container.id}" },
                        ) { index, container ->
                            TvMediaCard(
                                model = container.toTvCard(component, component.onOpenContainer),
                                focusScope = gridScope,
                                focusMemory = focusMemory,
                                fallbackIndex = index,
                                onFocused = {
                                    if (index >= totalCards - 6 && state.canLoadMore && !state.loadingMore) {
                                        store.accept(GridIntent.LoadMore)
                                    }
                                },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = state.items,
                            key = { _, item -> "item:server:${component.serverId}:${item.id}" },
                        ) { index, item ->
                            TvMediaCard(
                                model = item.toGridTvCard(component) { component.onOpenItem(item.id) },
                                focusScope = gridScope,
                                focusMemory = focusMemory,
                                fallbackIndex = index,
                                onFocused = {
                                    if (index >= totalCards - 6 && state.canLoadMore && !state.loadingMore) {
                                        store.accept(GridIntent.LoadMore)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun MediaItem.toLibraryTvCard(
    server: SavedServer,
    landscape: Boolean = false,
    onClick: () -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "${server.kind.name.lowercase()}:${server.id}:$id",
        title = title,
        subtitle = subtitle ?: year?.toString(),
        imageUrl =
            if (landscape) {
                EmbyImages.backdrop(server.baseUrl, this, accessToken = server.accessToken)
                    ?: EmbyImages.poster(server.baseUrl, this, accessToken = server.accessToken)
            } else {
                EmbyImages.poster(server.baseUrl, this, accessToken = server.accessToken)
            },
        serverId = server.id,
        profileId = server.userId,
        progress = playedPercentage?.div(100.0)?.toFloat(),
        badge = communityRating?.let { "%.1f".format(it) },
        artworkShape = if (landscape) TvArtworkShape.Landscape else TvArtworkShape.Poster,
        onClick = onClick,
    )

private fun MediaItem.toGridTvCard(
    component: LibraryGridComponent,
    onClick: () -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "server:${component.serverId}:$id",
        title = title,
        subtitle = subtitle ?: year?.toString(),
        imageUrl =
            EmbyImages.poster(
                component.serverBaseUrl,
                this,
                accessToken = component.serverAccessToken,
            ),
        serverId = component.serverId,
        progress = playedPercentage?.div(100.0)?.toFloat(),
        badge = communityRating?.let { "%.1f".format(it) },
        onClick = onClick,
    )

private fun MediaContainer.toTvCard(
    component: LibraryGridComponent,
    onOpen: (MediaContainer) -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "server:${serverId}:container:$id",
        title = title,
        subtitle = itemCount?.let { "$it 项" },
        imageUrl =
            EmbyImages.primary(
                component.serverBaseUrl,
                id,
                posterTag,
                accessToken = component.serverAccessToken,
            ),
        serverId = serverId,
        onClick = { onOpen(this) },
    )
