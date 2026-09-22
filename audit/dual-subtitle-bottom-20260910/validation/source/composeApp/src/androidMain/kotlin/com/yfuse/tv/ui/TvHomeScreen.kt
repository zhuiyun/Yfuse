package com.yfuse.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.feature.home.HomeComponent
import com.yfuse.feature.home.HomeIntent
import com.yfuse.feature.home.HomeLabel
import com.yfuse.feature.home.HomeResumeEntry
import com.yfuse.feature.home.HomeState
import kotlinx.coroutines.delay

@Composable
internal fun TvHomeScreen(
    component: HomeComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val heroItems = state.featuredSlides.take(8)
    var heroIndex by remember(heroItems.map(TmdbItem::id)) { mutableIntStateOf(0) }
    val hero = heroItems.getOrNull(heroIndex)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var heroFocused by remember { mutableStateOf(false) }
    TvRestoreRouteFocusEffect(
        route = "home",
        focusMemory = focusMemory,
        fallback = contentRequester,
        contentGeneration = listOf(state.loading, hero?.id, state.resume.size, state.nextUp.size),
    )

    // The carousel holds still while any of its own controls has focus: the reader is
    // deciding about *this* title, and advancing under the play key would start another one.
    // Under 减弱动态效果 it never advances on its own.
    LaunchedEffect(heroItems.map(TmdbItem::id), heroIndex, heroFocused, reduceMotion) {
        if (heroItems.size > 1 && !heroFocused && !reduceMotion) {
            delay(8_000L)
            heroIndex = (heroIndex + 1) % heroItems.size
        }
    }

    if (state.loading && heroItems.isEmpty() && state.resume.isEmpty()) {
        TvLoadingState("正在准备首页")
        return
    }

    LazyColumn(
        state = component.listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvSafeVertical, bottom = TvSafeVertical + 32.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp),
    ) {
        item(key = "home-hero") {
            TvHomeHero(
                item = hero,
                index = heroIndex,
                count = heroItems.size,
                onOpen = { hero?.let { store.accept(HomeIntent.Open(it)) } },
                onPlay = { hero?.let { store.accept(HomeIntent.Play(it)) } },
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                contentRequester = contentRequester,
                reduceMotion = reduceMotion,
                modifier = Modifier.onFocusChanged { heroFocused = it.hasFocus },
            )
        }

        if (state.resume.isNotEmpty()) {
            item(key = "home-resume") {
                TvMediaRow(
                    title = "继续观看",
                    sectionKey = "home:resume",
                    items = state.resume.map { it.toTvCard(store, "resume") },
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        if (state.nextUp.isNotEmpty()) {
            item(key = "home-next-up") {
                TvMediaRow(
                    title = "接下来",
                    sectionKey = "home:next-up",
                    items = state.nextUp.map { it.toTvCard(store, "next") },
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        state.content.rows.forEachIndexed { rowIndex, row ->
            if (row.items.isNotEmpty()) {
                item(key = "home-tmdb:${row.title}:$rowIndex") {
                    TvMediaRow(
                        title = row.title,
                        sectionKey = "home:tmdb:${row.title}:$rowIndex",
                        items =
                            row.items.map { item ->
                                item.toTvCard {
                                    store.accept(HomeIntent.Open(item))
                                }
                            },
                        focusMemory = focusMemory,
                        navigationRequester = navigationRequester,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        state.libraryContent.forEach { source ->
            source.content.rows.take(5).forEach { row ->
                if (row.items.isNotEmpty()) {
                    item(key = "home-server:${source.server.id}:${row.libraryId}:${row.title}") {
                        TvMediaRow(
                            title = "${source.server.serverName} · ${row.title}",
                            sectionKey = "home:server:${source.server.id}:${row.libraryId}",
                            items =
                                row.items.map { media ->
                                    TvMediaCardModel(
                                        stableId =
                                            "${source.server.kind.name.lowercase()}:${source.server.id}:${media.id}",
                                        title = media.title,
                                        subtitle = media.subtitle ?: media.year?.toString(),
                                        imageUrl =
                                            EmbyImages.poster(
                                                source.server.baseUrl,
                                                media,
                                                accessToken = source.server.accessToken,
                                            ),
                                        serverId = source.server.id,
                                        profileId = source.server.userId,
                                        progress = media.playedPercentage?.div(100.0)?.toFloat(),
                                        badge = media.communityRating?.let { "%.1f".format(it) },
                                        onClick = {
                                            store.accept(
                                                HomeIntent.OpenResume(
                                                    HomeResumeEntry(media, source.server),
                                                ),
                                            )
                                        },
                                    )
                                },
                            focusMemory = focusMemory,
                            navigationRequester = navigationRequester,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }

        if (state.error != null && heroItems.isEmpty() && state.resume.isEmpty()) {
            item(key = "home-error") {
                TvEmptyState(
                    title = "首页加载失败",
                    description = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { store.accept(HomeIntent.Retry) },
                    focusScope = "home:error",
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                )
            }
        }
    }
}

@Composable
private fun TvHomeHero(
    item: TmdbItem?,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(390.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF151B25)),
    ) {
        Crossfade(
            targetState = item,
            animationSpec = if (reduceMotion) snap() else tween(),
            label = "tv-living-poster",
        ) { current ->
            AsyncImage(
                model = TmdbImages.backdrop(current?.backdropPath, "w1280"),
                contentDescription = current?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.9f),
                        0.54f to Color.Black.copy(alpha = 0.26f),
                        1f to Color.Transparent,
                    ),
                ).background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to TvBackground,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(540.dp)
                .padding(start = 34.dp),
        ) {
            Text(
                text = "今日精选",
                color = TvAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = item?.title ?: "欢迎使用 Yfuse",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text =
                        listOfNotNull(
                            item.year,
                            item.rating?.let { "%.1f 分".format(it) },
                            item.runtimeMinutes?.let { "$it 分钟" },
                        ).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = item.overview.orEmpty(),
                    color = Color.White.copy(alpha = 0.74f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(21.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        label = "播放",
                        stableId = "home:hero:play:${item.id}",
                        focusScope = "home:hero",
                        focusMemory = focusMemory,
                        onClick = onPlay,
                        modifier = Modifier.width(150.dp),
                        icon = AppIcons.Play,
                        primary = true,
                        focusRequester = contentRequester,
                        navigationRequester = navigationRequester,
                        returnToNavigationOnLeft = true,
                    )
                    TvActionButton(
                        label = "详情",
                        stableId = "home:hero:detail:${item.id}",
                        focusScope = "home:hero",
                        focusMemory = focusMemory,
                        onClick = onOpen,
                        modifier = Modifier.width(150.dp),
                        icon = AppIcons.Info,
                    )
                }
            }
        }
        if (count > 1) {
            Text(
                text = "${index + 1} / $count",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(22.dp),
            )
        }
    }
}

private fun HomeResumeEntry.toTvCard(
    store: com.arkivanov.mvikotlin.core.store.Store<HomeIntent, HomeState, HomeLabel>,
    prefix: String,
): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "$prefix:${server.kind.name.lowercase()}:${server.id}:${item.id}",
        title = item.title,
        subtitle = item.subtitle ?: item.runtimeMinutes?.let { "$it 分钟" },
        imageUrl =
            EmbyImages.backdrop(server.baseUrl, item, accessToken = server.accessToken)
                ?: EmbyImages.poster(server.baseUrl, item, accessToken = server.accessToken),
        serverId = server.id,
        profileId = server.userId,
        progress = item.playedPercentage?.div(100.0)?.toFloat(),
        badge = item.communityRating?.let { "%.1f".format(it) },
        artworkShape = TvArtworkShape.Landscape,
        onClick = { store.accept(HomeIntent.OpenResume(this)) },
    )

private fun TmdbItem.toTvCard(onClick: () -> Unit): TvMediaCardModel =
    TvMediaCardModel(
        stableId = "tmdb:$mediaType:$id",
        title = title,
        subtitle = year,
        imageUrl = TmdbImages.poster(posterPath),
        badge = rating?.let { "%.1f".format(it) },
        onClick = onClick,
    )
