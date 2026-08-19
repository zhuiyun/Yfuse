package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedDominantColor
import com.yfuse.core.designsystem.rememberScrolledPastHero
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.TmdbImages

/** Full TMDB detail page; Emby availability only controls the play action. */
@Composable
fun TmdbInfoScreen(component: TmdbInfoComponent) {
    val state by component.state.collectAsState()
    val detail = state.detail
    val item = detail.item
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current.accent
    // image.tmdb.org is the primary CDN and media.themoviedb.org the official mirror for
    // when it is unreachable; the dominant-colour probe follows whichever one is showing.
    val heroUrls =
        listOf(
            TmdbImages.backdrop(item.backdropPath),
            TmdbImages.media(item.backdropPath, "w1280"),
            TmdbImages.poster(item.posterPath, "w780"),
            TmdbImages.media(item.posterPath, "w780"),
        )
    val heroUrl = heroUrls.firstOrNull { it != null }
    val accent =
        rememberAnimatedDominantColor(
            heroUrl,
            Brand.Primary, // design-system: brand-identity
        )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.34f
        val density = LocalDensity.current
        val detailSurface =
            remember(accent, palette.isDark) {
                heroSurface(accent, palette.isDark)
            }
        val panelBrush =
            remember(detailSurface, density) {
                heroPanelBrush(detailSurface, density, height = 220.dp)
            }
        val listState = rememberLazyListState()
        val lightPageReached by rememberScrolledPastHero(listState, heroHeight)
        StatusBarIconStyle(darkIcons = lightPageReached)
        Box(
            Modifier
                .fillMaxSize()
                .background(if (palette.isDark) palette.background else Color.White),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = Dimens.contentBottom),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    FallbackImage(
                        urls = heroUrls,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(heroScrim(detailSurface)))
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 18.dp, top = 12.dp)
                                .pressable(onClickLabel = "返回上一页", onClick = component.onBack)
                                .touchTarget()
                                .size(38.dp)
                                .solidGlass(
                                    shape = CircleShape,
                                    fill = Color(0xFF11151F).copy(alpha = 0.28f),
                                    border = Color.White.copy(alpha = 0.34f),
                                ).padding(10.dp),
                    )
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .liftOverHero(46.dp)
                        .background(panelBrush)
                        .padding(
                            start = Dimens.pageHorizontal,
                            top = 0.dp,
                            end = Dimens.pageHorizontal,
                            bottom = 12.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .shadow(Shadows.sheet, GlassShapes.sheet)
                            .solidGlass(
                                shape = GlassShapes.card,
                                fill =
                                    if (palette.isDark) {
                                        palette.glassStrong
                                    } else {
                                        Color(0xFFEAF0FA).copy(alpha = 0.82f)
                                    },
                                border =
                                    if (palette.isDark) {
                                        palette.border
                                    } else {
                                        Color.White.copy(alpha = 0.94f)
                                    },
                            ).padding(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Poster(
                                url = TmdbImages.poster(item.posterPath),
                                fallbackUrls =
                                    listOfNotNull(
                                        TmdbImages.media(item.posterPath),
                                        TmdbImages.backdrop(item.backdropPath, "w780"),
                                        TmdbImages.media(item.backdropPath, "w780"),
                                    ),
                                modifier =
                                    Modifier
                                        .width(78.dp)
                                        .height(110.dp)
                                        .shadow(Shadows.detailPoster, GlassShapes.poster)
                                        .border(2.dp, Color.White, GlassShapes.poster),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = AppTypography.section.strong,
                                    color = palette.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    listOfNotNull(
                                        detail.genres.firstOrNull(),
                                        item.year,
                                        detail.runtimeMinutes?.let { "$it 分钟" },
                                        detail.numberOfSeasons?.let { "$it 季" },
                                    ).joinToString(" · "),
                                    style = AppTypography.caption.regular,
                                    color = palette.sub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.rating?.let { rating ->
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        "TMDB ${(rating * 10).toInt() / 10.0}",
                                        style = AppTypography.caption.strong,
                                        color = Brand.Imdb,
                                        modifier =
                                            Modifier
                                                .solidGlass(
                                                    shape = GlassShapes.thumb,
                                                    fill = Brand.Imdb.copy(alpha = 0.14f),
                                                    border = Brand.Imdb.copy(alpha = 0.24f),
                                                ).padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }

                    TmdbPlayDock(
                        playable = state.playable,
                        resolving = state.resolvingPlay,
                        accent = themeAccent,
                        onPlay = component::play,
                    )

                    if (state.sources.any {
                            it.reachable && it.source != null && it.itemId != null
                        }
                    ) {
                        TmdbSourceStrip(
                            sources = state.sources,
                            accent = themeAccent,
                            onSelect = component::playSource,
                        )
                    }

                    state.error?.let { error ->
                        Text(
                            error,
                            style = AppTypography.body.strong,
                            color = palette.error,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(onClick = component::dismissError)
                                    .solidGlass(GlassShapes.card)
                                    .padding(12.dp),
                        )
                    }

                    if (!detail.tagline.isNullOrBlank()) {
                        Text(
                            "“${detail.tagline}”",
                            style = AppTypography.body.medium.copy(lineHeight = 20.sp),
                            fontStyle = FontStyle.Italic,
                            color = palette.sub,
                        )
                    }

                    if (!item.overview.isNullOrBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("剧情简介", style = AppTypography.section.strong, color = palette.text)
                            Text(
                                item.overview,
                                style = AppTypography.body.regular.copy(lineHeight = 20.sp),
                                color = palette.body,
                            )
                        }
                    }

                    if (detail.genres.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(detail.genres, key = { it }) { genre ->
                                Text(
                                    genre,
                                    style = AppTypography.body.strong,
                                    color = palette.sub,
                                    modifier =
                                        Modifier
                                            .solidGlass(GlassShapes.thumb)
                                            .padding(horizontal = 11.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }

                    if (detail.cast.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("演职人员", style = AppTypography.section.strong, color = palette.text)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(detail.cast, key = { it.id }) { person ->
                                    Column(
                                        Modifier.width(70.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        FallbackImage(
                                            urls =
                                                listOf(
                                                    TmdbImages.poster(person.profilePath, "w185"),
                                                    TmdbImages.media(person.profilePath, "w185"),
                                                ),
                                            contentDescription = person.name,
                                            modifier =
                                                Modifier
                                                    .size(58.dp)
                                                    .clip(CircleShape)
                                                    .background(palette.card)
                                                    .border(
                                                        1.dp,
                                                        Color.White.copy(alpha = 0.88f),
                                                        CircleShape,
                                                    ),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            person.name,
                                            style = AppTypography.body.strong,
                                            color = palette.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        person.role?.let {
                                            Text(
                                                it,
                                                style = AppTypography.caption.regular,
                                                color = palette.sub2,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.loading) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                color = themeAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TmdbSourceStrip(
    sources: List<ServerSource>,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
) {
    val palette = LocalPalette.current
    val availableSources =
        remember(sources) {
            sources.filter { it.reachable && it.source != null && it.itemId != null }
        }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(
            availableSources,
            key = { index, entry -> "tmdb-source-${entry.serverId}-${entry.itemId}-$index" },
        ) { _, entry ->
            Row(
                Modifier
                    .width(168.dp)
                    .heightIn(min = 52.dp)
                    .pressable {
                        entry.itemId?.let { onSelect(entry.serverId, it) }
                    }.solidGlass(
                        shape = GlassShapes.thumb,
                        fill =
                            if (palette.isDark) {
                                Color.White.copy(alpha = 0.07f)
                            } else {
                                Color(0xFFEFF3FA).copy(alpha = 0.72f)
                            },
                        border =
                            if (palette.isDark) {
                                Color.White.copy(alpha = 0.20f)
                            } else {
                                Color(0xFFD7DDE9)
                            },
                    ).padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        entry.serverName,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(entry.source?.bitrate, entry.source?.size)
                            .joinToString(" · ")
                            .ifBlank { "资源信息读取中" },
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.isCurrent) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = "当前片源",
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Spacer(Modifier.width(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TmdbPlayDock(
    playable: Boolean,
    resolving: Boolean,
    accent: Color,
    onPlay: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .solidGlass(
                shape = GlassShapes.card,
                fill =
                    if (palette.isDark) {
                        Color.White.copy(alpha = 0.08f)
                    } else {
                        Color.White.copy(alpha = 0.72f)
                    },
                border = if (palette.isDark) palette.border else Color.White.copy(alpha = 0.96f),
            ).padding(7.dp),
    ) {
        if (playable) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .pressable(enabled = !resolving, onClick = onPlay)
                    .clip(GlassShapes.card)
                    .background(accent)
                    .border(1.dp, Color.White.copy(alpha = 0.24f), GlassShapes.card),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("立即播放", style = AppTypography.body.strong, color = Color.White)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                Text(
                    "未加入媒体库 · 以下资料来自 TMDB",
                    style = AppTypography.body.medium,
                    color = palette.sub2,
                )
            }
        }
    }
}
