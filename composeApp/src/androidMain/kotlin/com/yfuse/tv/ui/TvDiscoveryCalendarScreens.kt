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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.yfuse.core.data.TgtoResourceItem
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.calendar.CalendarComponent
import com.yfuse.feature.calendar.CalendarFilter
import com.yfuse.feature.calendar.CalendarIntent
import com.yfuse.feature.home.MediaItemDetailComponent
import com.yfuse.feature.home.TmdbInfoComponent

@Composable
internal fun TvTmdbInfoScreen(
    component: TmdbInfoComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.state.collectAsState()
    val following by component.following.collectAsState()
    val item = state.detail.item
    val primaryRequester = remember { FocusRequester() }
    TvRestoreRouteFocusEffect(
        route = "tmdb-info",
        focusMemory = focusMemory,
        fallback = primaryRequester,
        contentGeneration = listOf(item.id, state.loading, state.playable, state.sources.size),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TvSafeVertical + 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "tmdb-info:hero:${item.mediaType}:${item.id}") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(455.dp)
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(TvSurface),
            ) {
                AsyncImage(
                    model = TmdbImages.backdrop(item.backdropPath, "w1280"),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.94f),
                                0.62f to Color.Black.copy(alpha = 0.35f),
                                1f to Color.Transparent,
                            ),
                        ).background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.75f to Color.Transparent,
                                1f to TvBackground,
                            ),
                        ),
                )
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(640.dp)
                        .padding(start = TvSafeHorizontal),
                ) {
                    TvActionButton(
                        label = "返回",
                        stableId = "tmdb-info:${item.id}:back",
                        focusScope = "tmdb-info:${item.id}:hero",
                        focusMemory = focusMemory,
                        onClick = component.onBack,
                        modifier = Modifier.width(118.dp),
                        icon = AppIcons.ChevronLeft,
                        focusRequester = primaryRequester,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.detail.tagline?.takeIf(String::isNotBlank)?.let { tagline ->
                        Spacer(Modifier.height(7.dp))
                        Text(tagline, color = TvAccent, fontSize = 17.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOfNotNull(
                            item.year,
                            state.detail.runtimeMinutes?.let { "$it 分钟" },
                            item.rating?.let { "%.1f 分".format(it) },
                            state.detail.numberOfSeasons?.let { "$it 季" },
                            state.detail.genres.take(3).joinToString(" / ").takeIf(String::isNotBlank),
                        ).joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        item.overview.orEmpty(),
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        TvActionButton(
                            label = if (state.resolvingPlay) "正在准备" else "播放",
                            stableId = "tmdb-info:${item.id}:play",
                            focusScope = "tmdb-info:${item.id}:hero",
                            focusMemory = focusMemory,
                            onClick = component::play,
                            modifier = Modifier.width(170.dp),
                            icon = AppIcons.Play,
                            primary = state.playable,
                        )
                        if (item.mediaType == "tv") {
                            TvActionButton(
                                label = if (following) "已追剧" else "追剧",
                                stableId = "tmdb-info:${item.id}:follow",
                                focusScope = "tmdb-info:${item.id}:hero",
                                focusMemory = focusMemory,
                                onClick = component::toggleFollow,
                                modifier = Modifier.width(150.dp),
                                icon = if (following) AppIcons.HeartFilled else AppIcons.Heart,
                                selected = following,
                            )
                        }
                    }
                    state.error?.let { error ->
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = Color(0xFFFFB4AB), fontSize = 14.sp)
                    }
                }
            }
        }
        if (state.sources.isNotEmpty()) {
            item(key = "tmdb-info:sources:${item.id}") {
                TvMediaRow(
                    title = "可用服务器",
                    sectionKey = "tmdb-info:${item.id}:sources",
                    items =
                        state.sources.map { source ->
                            TvMediaCardModel(
                                stableId = "server:${source.serverId}:${source.itemId ?: "missing"}",
                                title = source.serverName,
                                subtitle =
                                    when {
                                        !source.reachable -> "服务器不可达"
                                        source.itemId == null -> "媒体库中没有此内容"
                                        source.isCurrent -> "当前服务器 · 按确定播放"
                                        else -> "按确定从此服务器播放"
                                    },
                                serverId = source.serverId,
                                badge = if (source.isCurrent) "当前" else null,
                                artworkShape = TvArtworkShape.Landscape,
                                onClick = {
                                    source.itemId?.let { component.playSource(source.serverId, it) }
                                },
                            )
                        },
                    focusMemory = focusMemory,
                    navigationRequester = primaryRequester,
                    modifier = Modifier.padding(horizontal = TvSafeHorizontal),
                )
            }
        }
        if (state.detail.cast.isNotEmpty()) {
            item(key = "tmdb-info:cast:${item.id}") {
                Column(
                    Modifier.padding(horizontal = TvSafeHorizontal),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("演职人员", color = TvOnSurface, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(
                        state.detail.cast.take(10).joinToString("   ") { person ->
                            listOfNotNull(person.name, person.role).joinToString(" · ")
                        },
                        color = TvOnSurfaceMuted,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TvMediaDiscoveryDetailScreen(
    component: MediaItemDetailComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.state.collectAsState()
    val item = state.item
    val primaryRequester = remember { FocusRequester() }
    TvRestoreRouteFocusEffect(
        route = "media-discovery",
        focusMemory = focusMemory,
        fallback = primaryRequester,
        contentGeneration = listOf(item.entityKey, state.metadataLoading, state.resources.size),
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TvSafeVertical + 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "media-discovery:hero:${item.entityKey}") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(440.dp)
                    .background(TvSurface),
            ) {
                AsyncImage(
                    model = item.backdropUrl.ifBlank { item.posterUrl },
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.94f),
                                0.6f to Color.Black.copy(alpha = 0.38f),
                                1f to Color.Transparent,
                            ),
                        ).background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to TvBackground)),
                )
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(650.dp)
                        .padding(start = TvSafeHorizontal),
                ) {
                    TvActionButton(
                        label = "返回",
                        stableId = "media-discovery:${item.entityKey}:back",
                        focusScope = "media-discovery:${item.entityKey}:hero",
                        focusMemory = focusMemory,
                        onClick = component.onBack,
                        modifier = Modifier.width(118.dp),
                        icon = AppIcons.ChevronLeft,
                        focusRequester = primaryRequester,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        listOfNotNull(
                            item.year.takeIf(String::isNotBlank),
                            item.runtime?.let { "$it 分钟" },
                            item.score?.let { "%.1f 分".format(it) },
                            item.genres.take(3).joinToString(" / ").takeIf(String::isNotBlank),
                            item.providerLabel.takeIf(String::isNotBlank),
                        ).joinToString("  ·  "),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        item.overview,
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        if (state.localItemId != null) {
                            TvActionButton(
                                label = "播放",
                                stableId = "media-discovery:${item.entityKey}:play",
                                focusScope = "media-discovery:${item.entityKey}:hero",
                                focusMemory = focusMemory,
                                onClick = component::playInYfuse,
                                modifier = Modifier.width(160.dp),
                                icon = AppIcons.Play,
                                primary = true,
                            )
                        }
                        TvActionButton(
                            label = if (state.localItemId == null) "在 Yfuse 中查找" else "媒体详情",
                            stableId = "media-discovery:${item.entityKey}:open",
                            focusScope = "media-discovery:${item.entityKey}:hero",
                            focusMemory = focusMemory,
                            onClick = component::openLibraryDetail,
                            modifier = Modifier.width(205.dp),
                            icon = AppIcons.Info,
                        )
                    }
                    (state.transferMessage ?: state.navigationError)?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = Color(0xFFFFDDB3), fontSize = 14.sp, maxLines = 2)
                    }
                }
            }
        }
        item(key = "media-discovery:resources:${item.entityKey}") {
            TvResourceRow(
                resources = state.resources,
                loading = state.resourcesLoading,
                error = state.resourcesError,
                transferringKey = state.transferringKey,
                focusMemory = focusMemory,
                navigationRequester = primaryRequester,
                onTransfer = component::transfer,
            )
        }
    }
}

@Composable
private fun TvResourceRow(
    resources: List<TgtoResourceItem>,
    loading: Boolean,
    error: String?,
    transferringKey: String?,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    onTransfer: (TgtoResourceItem) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = TvSafeHorizontal),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("可用资源", color = TvOnSurface, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        when {
            loading && resources.isEmpty() -> Text("正在搜索 123 资源…", color = TvOnSurfaceMuted)
            resources.isEmpty() -> Text(error ?: "暂时没有匹配的资源", color = TvOnSurfaceMuted)
            else ->
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(resources, key = { _, resource -> "resource:${resource.provider}:${resource.itemKey}" }) {
                            index,
                            resource,
                        ->
                        TvFocusableSurface(
                            stableId = "resource:${resource.provider}:${resource.itemKey}",
                            focusScope = "media-discovery:resources",
                            focusMemory = focusMemory,
                            onClick = { onTransfer(resource) },
                            modifier = Modifier.width(300.dp).height(145.dp),
                            navigationRequester = navigationRequester,
                            returnToNavigationOnLeft = index == 0,
                            fallbackIndex = index,
                        ) { focused ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(17.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    resource.title.ifBlank { "123 资源" },
                                    color = if (focused) Color.White else TvOnSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        resource.resolution.takeIf(String::isNotBlank),
                                        resource.quality.takeIf(String::isNotBlank),
                                        resource.size.takeIf(String::isNotBlank),
                                    ).joinToString(" · ").ifBlank { resource.providerLabel },
                                    color = TvOnSurfaceMuted,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    if (transferringKey == resource.itemKey) "正在转存…" else "按确定转存到 123",
                                    color = TvAccent,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun TvCalendarScreen(
    component: CalendarComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val primaryRequester = remember { FocusRequester() }
    TvRestoreRouteFocusEffect(
        route = "calendar",
        focusMemory = focusMemory,
        fallback = primaryRequester,
        contentGeneration = listOf(state.loading, state.filter, state.visibleDays.size),
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = TvSafeHorizontal, vertical = TvSafeVertical),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "calendar:header") {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton(
                            label = "返回",
                            stableId = "calendar:back",
                            focusScope = "calendar:header",
                            focusMemory = focusMemory,
                            onClick = component.onBack,
                            modifier = Modifier.width(118.dp),
                            icon = AppIcons.ChevronLeft,
                            focusRequester = primaryRequester,
                        )
                        Text("追剧日历", color = TvOnSurface, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    TvActionButton(
                        label = "刷新",
                        stableId = "calendar:refresh",
                        focusScope = "calendar:header",
                        focusMemory = focusMemory,
                        onClick = { store.accept(CalendarIntent.Refresh) },
                        modifier = Modifier.width(125.dp),
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    itemsIndexed(CalendarFilter.entries, key = { _, filter -> "calendar:filter:${filter.name}" }) {
                            index,
                            filter,
                        ->
                        TvActionButton(
                            label = filter.label,
                            stableId = "calendar:filter:${filter.name}",
                            focusScope = "calendar:filters",
                            focusMemory = focusMemory,
                            onClick = { store.accept(CalendarIntent.SelectFilter(filter)) },
                            modifier = Modifier.width(130.dp),
                            selected = filter == state.filter,
                            navigationRequester = primaryRequester,
                            returnToNavigationOnLeft = index == 0,
                        )
                    }
                }
            }
        }
        when {
            state.loading && state.days.isEmpty() -> item(key = "calendar:loading") { TvLoadingState("正在更新追剧日历") }
            state.error != null && state.days.isEmpty() ->
                item(key = "calendar:error") {
                    TvEmptyState(
                        title = "日历加载失败",
                        description = state.error.orEmpty(),
                        actionLabel = "重试",
                        onAction = { store.accept(CalendarIntent.Refresh) },
                        focusScope = "calendar:error",
                        focusMemory = focusMemory,
                    )
                }
            state.visibleDays.isEmpty() ->
                item(key = "calendar:empty") {
                    TvEmptyState(
                        title = "这个筛选下没有节目",
                        description = "换一个日期或追剧筛选试试。",
                        focusScope = "calendar:empty",
                        focusMemory = focusMemory,
                    )
                }
            else ->
                state.visibleDays.forEach { day ->
                    item(key = "calendar:day:${day.date}") {
                        TvMediaRow(
                            title = if (day.date == state.today) "今天 · ${day.date}" else day.date,
                            sectionKey = "calendar:${day.date}",
                            items = day.entries.map { it.toCalendarTvCard(component) },
                            focusMemory = focusMemory,
                            navigationRequester = primaryRequester,
                        )
                    }
                }
        }
    }
}

private fun CalendarEntry.toCalendarTvCard(component: CalendarComponent): TvMediaCardModel {
    val target = openItemId
    val stableProviderId =
        if (target != null && serverId != null) {
            "server:$serverId:$target"
        } else {
            "tmdb:${episode.showTmdbId}:s${episode.seasonNumber}e${episode.episodeNumber}"
        }
    return TvMediaCardModel(
        stableId = stableProviderId,
        title = episode.showTitle,
        subtitle = episode.episodeLabel,
        imageUrl = posterUrls.firstOrNull() ?: TmdbImages.poster(episode.posterPath),
        serverId = serverId,
        progress = playedPercentage?.div(100.0)?.toFloat(),
        badge =
            when (status) {
                LibraryStatus.Unaired -> "未播"
                LibraryStatus.Missing -> "待入库"
                LibraryStatus.Available -> "可观看"
                LibraryStatus.InProgress -> "继续观看"
                LibraryStatus.Watched -> "已看"
                LibraryStatus.Unknown -> if (followed) "已追剧" else "发现"
            },
        onClick = {
            if (target != null) {
                component.onOpenItem(serverId, target)
            } else {
                component.toggleFollow(this)
            }
        },
    )
}
