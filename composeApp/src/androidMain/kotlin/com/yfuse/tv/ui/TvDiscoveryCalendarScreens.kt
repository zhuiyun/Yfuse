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
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.calendar.CalendarComponent
import com.yfuse.feature.calendar.CalendarFilter
import com.yfuse.feature.calendar.CalendarIntent
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
                            state.detail.genres
                                .take(3)
                                .joinToString(" / ")
                                .takeIf(String::isNotBlank),
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
