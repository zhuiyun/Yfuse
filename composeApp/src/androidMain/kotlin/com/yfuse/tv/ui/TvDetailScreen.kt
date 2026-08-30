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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.detail.DetailIntent
import com.yfuse.tv.focus.FocusContext
import com.yfuse.tv.focus.FocusCandidate

@Composable
internal fun TvDetailScreen(
    component: DetailComponent,
    focusMemory: TvUiFocusMemory,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val detail = state.detail
    val server = state.server
    val playRequester = remember { FocusRequester() }
    val secondaryNavigationRequester = remember { FocusRequester() }
    if (detail != null && server != null) {
        TvRestoreRouteFocusEffect(
            route = "detail",
            focusMemory = focusMemory,
            fallback = playRequester,
            contentGeneration = listOf(detail.id, state.selectedSeasonId, state.episodes.size, state.related.size),
            context = FocusContext("detail", server.id, server.userId),
        )
    }

    when {
        state.loading && detail == null -> TvLoadingState("正在读取详情")
        state.error != null && detail == null ->
            TvEmptyState(
                title = "无法打开详情",
                description = state.error.orEmpty(),
                actionLabel = "重试",
                onAction = { store.accept(DetailIntent.Retry) },
                focusScope = "detail:${component.itemId}:error",
                focusMemory = focusMemory,
                focusRequester = playRequester,
            )
        detail != null && server != null -> {
            val heroUrl =
                EmbyImages.backdrop(server.baseUrl, detail, accessToken = server.accessToken)
                    ?: EmbyImages.poster(server.baseUrl, detail, accessToken = server.accessToken)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TvSafeVertical + 38.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "detail-hero:${detail.id}") {
                    TvDetailHero(
                        detail = detail,
                        heroUrl = heroUrl,
                        playTitle = state.playTarget?.title,
                        resumeTicks = state.playPositionTicks,
                        busy = state.resolvingPlay || state.selectionLoading,
                        onBack = component.onBack,
                        onPlay = { store.accept(DetailIntent.Play) },
                        onPlayFromStart = { store.accept(DetailIntent.PlayFromStart) },
                        onToggleFavorite = { store.accept(DetailIntent.ToggleFavorite) },
                        focusMemory = focusMemory,
                        playRequester = playRequester,
                        serverId = server.id,
                        profileId = server.userId,
                    )
                }

                if (state.seasons.isNotEmpty()) {
                    item(key = "detail-seasons:${detail.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("季", color = TvOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(
                                    state.seasons,
                                    key = { _, season -> "season:${server.id}:${detail.id}:${season.id}" },
                                ) { _, season ->
                                    TvActionButton(
                                        label = season.name,
                                        stableId = "detail:season:${season.id}",
                                        focusScope = "detail:${detail.id}:seasons",
                                        focusMemory = focusMemory,
                                        onClick = { store.accept(DetailIntent.SelectSeason(season.id)) },
                                        modifier = Modifier.width(132.dp),
                                        selected = season.id == state.selectedSeasonId,
                                        serverId = server.id,
                                        profileId = server.userId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.episodesLoading && state.episodes.isEmpty()) {
                    item(key = "detail-episodes-loading") {
                        Box(Modifier.fillMaxWidth().height(150.dp)) { TvLoadingState("正在读取剧集") }
                    }
                } else if (state.episodes.isNotEmpty()) {
                    item(key = "detail-episodes:${state.selectedSeasonId}") {
                        TvEpisodeRow(
                            detail = detail,
                            episodes = state.episodes,
                            selectedEpisodeId = state.selectedEpisodeId,
                            serverId = (state.playServer ?: server).id,
                            profileId = (state.playServer ?: server).userId,
                            baseUrl = (state.playServer ?: server).baseUrl,
                            accessToken = (state.playServer ?: server).accessToken,
                            focusMemory = focusMemory,
                            onEpisode = { episode ->
                                store.accept(
                                    DetailIntent.SelectEpisode(
                                        episodeId = episode.id,
                                        startPositionTicks = episode.resumePositionTicks ?: 0L,
                                    ),
                                )
                            },
                        )
                    }
                }

                val versions = state.playTarget?.versions.orEmpty()
                if (versions.size > 1) {
                    item(key = "detail-versions:${state.playTarget?.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("播放版本", color = TvOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(
                                    versions,
                                    key = { _, version ->
                                        "version:${(state.playServer ?: server).id}:${state.playTarget?.id}:${version.id}"
                                    },
                                ) { _, version ->
                                    TvActionButton(
                                        label = version.summary.take(38),
                                        stableId = "detail:version:${version.id}",
                                        focusScope = "detail:${detail.id}:versions",
                                        focusMemory = focusMemory,
                                        onClick = { store.accept(DetailIntent.SelectVersion(version.id)) },
                                        modifier = Modifier.width(260.dp),
                                        selected = version.id == state.selectedVersionId,
                                        serverId = (state.playServer ?: server).id,
                                        profileId = (state.playServer ?: server).userId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.sources.size > 1) {
                    item(key = "detail-sources:${detail.id}") {
                        Column(
                            Modifier.padding(horizontal = TvSafeHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("服务器片源", color = TvOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(
                                    state.sources.filter { it.reachable && it.itemId != null },
                                    key = { _, source -> "source:${source.serverId}:${source.itemId}" },
                                ) { _, source ->
                                    TvActionButton(
                                        label =
                                            listOfNotNull(source.serverName, source.source?.quality)
                                                .joinToString(" · "),
                                        stableId = "detail:source:${source.serverId}:${source.itemId}",
                                        focusScope = "detail:${detail.id}:sources",
                                        focusMemory = focusMemory,
                                        onClick = {
                                            source.itemId?.let { id ->
                                                store.accept(DetailIntent.SelectSource(source.serverId, id))
                                            }
                                        },
                                        modifier = Modifier.width(220.dp),
                                        selected =
                                            source.serverId == state.selectedSourceServerId &&
                                                source.itemId == state.selectedSourceItemId,
                                        serverId = source.serverId,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.related.isNotEmpty()) {
                    item(key = "detail-related:${detail.id}") {
                        TvMediaRow(
                            title = "更多相关内容",
                            sectionKey = "detail:${detail.id}:related",
                            items =
                                state.related.map { related ->
                                    related.toRelatedCard(server) {
                                        component.onOpenRelated(server.id, related.id)
                                    }
                                },
                            focusMemory = focusMemory,
                            navigationRequester = secondaryNavigationRequester,
                            modifier = Modifier.padding(horizontal = TvSafeHorizontal - 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvDetailHero(
    detail: MediaDetail,
    heroUrl: String?,
    playTitle: String?,
    resumeTicks: Long,
    busy: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
    focusMemory: TvUiFocusMemory,
    playRequester: FocusRequester,
    serverId: String,
    profileId: String,
) {
    Box(Modifier.fillMaxWidth().height(475.dp).background(Color(0xFF121720))) {
        AsyncImage(
            model = heroUrl,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.93f),
                        0.56f to Color.Black.copy(alpha = 0.38f),
                        1f to Color.Transparent,
                    ),
                ).background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.68f to Color.Transparent,
                        1f to TvBackground,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(650.dp)
                .padding(start = TvSafeHorizontal),
        ) {
            TvActionButton(
                label = "返回",
                stableId = "detail:${detail.id}:back",
                focusScope = "detail:${detail.id}:hero",
                focusMemory = focusMemory,
                onClick = onBack,
                modifier = Modifier.width(118.dp),
                icon = AppIcons.ChevronLeft,
                serverId = serverId,
                profileId = profileId,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                detail.title,
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                listOfNotNull(
                    detail.year?.toString(),
                    detail.runtimeMinutes?.let { "$it 分钟" },
                    detail.communityRating?.let { "%.1f 分".format(it) },
                    detail.officialRating,
                    detail.genres.take(3).joinToString(" / ").takeIf(String::isNotBlank),
                ).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                detail.overview.orEmpty(),
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 16.sp,
                lineHeight = 23.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                TvActionButton(
                    label =
                        when {
                            busy -> "正在准备"
                            playTitle != null && playTitle != detail.title -> "播放 · $playTitle"
                            resumeTicks > 0L -> "继续播放"
                            else -> "播放"
                        },
                    stableId = "detail:${detail.id}:play",
                    focusScope = "detail:${detail.id}:hero",
                    focusMemory = focusMemory,
                    onClick = onPlay,
                    modifier = Modifier.width(230.dp),
                    icon = AppIcons.Play,
                    primary = true,
                    focusRequester = playRequester,
                    serverId = serverId,
                    profileId = profileId,
                )
                if (resumeTicks > 0L) {
                    TvActionButton(
                        label = "从头播放",
                        stableId = "detail:${detail.id}:restart",
                        focusScope = "detail:${detail.id}:hero",
                        focusMemory = focusMemory,
                        onClick = onPlayFromStart,
                        modifier = Modifier.width(160.dp),
                        serverId = serverId,
                        profileId = profileId,
                    )
                }
                TvActionButton(
                    label = if (detail.isFavorite) "已收藏" else "收藏",
                    stableId = "detail:${detail.id}:favorite",
                    focusScope = "detail:${detail.id}:hero",
                    focusMemory = focusMemory,
                    onClick = onToggleFavorite,
                    modifier = Modifier.width(142.dp),
                    icon = if (detail.isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                    selected = detail.isFavorite,
                    serverId = serverId,
                    profileId = profileId,
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeRow(
    detail: MediaDetail,
    episodes: List<Episode>,
    selectedEpisodeId: String?,
    serverId: String,
    profileId: String,
    baseUrl: String,
    accessToken: String,
    focusMemory: TvUiFocusMemory,
    onEpisode: (Episode) -> Unit,
) {
    val episodeScope = "detail:${detail.id}:episodes"
    val rowState = focusMemory.rowState(episodeScope)
    val candidates =
        episodes.mapIndexed { index, episode ->
            val stableId = "server:$serverId:episode:${episode.id}"
            FocusCandidate(
                targetId = focusMemory.targetId(episodeScope, stableId),
                sectionId = episodeScope,
                itemStableId = stableId,
                index = index,
            )
        }
    val context = FocusContext("detail", serverId, profileId)
    if (focusMemory.lastForRoute("detail", context)?.sectionId == episodeScope) {
        TvRestoreRouteFocusEffect(
            route = "detail",
            focusMemory = focusMemory,
            contentGeneration = episodes.map(Episode::id),
            context = context,
            candidates = candidates,
            scrollToAnchor = { anchor ->
                if (candidates.isNotEmpty()) {
                    rowState.scrollToItem(anchor.fallbackIndex.coerceIn(0, candidates.lastIndex))
                }
            },
        )
    }
    Column(
        Modifier.padding(horizontal = TvSafeHorizontal),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("剧集", color = TvOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(episodes, key = { _, episode -> "episode:$serverId:${detail.id}:${episode.id}" }) {
                    index,
                    episode,
                ->
                TvMediaCard(
                    model =
                        TvMediaCardModel(
                            stableId = "server:$serverId:episode:${episode.id}",
                            title = episode.indexNumber?.let { "第 $it 集 · ${episode.name}" } ?: episode.name,
                            subtitle =
                                listOfNotNull(
                                    episode.runtimeMinutes?.let { "$it 分钟" },
                                    when {
                                        episode.played -> "已看"
                                        (episode.playedPercentage ?: 0.0) > 0.0 -> "继续观看"
                                        else -> null
                                    },
                                ).joinToString(" · "),
                            imageUrl =
                                EmbyImages.primary(
                                    baseUrl,
                                    episode.id,
                                    episode.primaryTag,
                                    maxHeight = 300,
                                    accessToken = accessToken,
                                ),
                            serverId = serverId,
                            profileId = profileId,
                            progress = episode.playedPercentage?.div(100.0)?.toFloat(),
                            artworkShape = TvArtworkShape.Landscape,
                            selected = episode.id == selectedEpisodeId,
                            onClick = { onEpisode(episode) },
                        ),
                    focusScope = episodeScope,
                    focusMemory = focusMemory,
                    fallbackIndex = index,
                )
            }
        }
        Text(
            "按一次选择剧集，再按一次直接播放",
            color = TvOnSurfaceMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun MediaItem.toRelatedCard(
    server: com.yfuse.core.model.SavedServer,
    onClick: () -> Unit,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "${server.kind.name.lowercase()}:${server.id}:related:$id",
        title = title,
        subtitle = year?.toString(),
        imageUrl = EmbyImages.poster(server.baseUrl, this, accessToken = server.accessToken),
        serverId = server.id,
        profileId = server.userId,
        badge = communityRating?.let { "%.1f".format(it) },
        onClick = onClick,
    )
