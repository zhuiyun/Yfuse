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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HeroPageFade
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.fadeIntoPage
import com.yfuse.core.designsystem.heroTopScrim
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberArtworkPageColor
import com.yfuse.core.designsystem.rememberRetainedArtworkPageColor
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
    val following by component.following.collectAsState()
    val detail = state.detail
    val item = detail.item
    val inheritedPalette = LocalPalette.current
    // image.tmdb.org is the primary CDN and media.themoviedb.org the official mirror for
    // when it is unreachable; colour sampling follows whichever candidate is actually shown.
    val heroUrls =
        listOf(
            TmdbImages.backdrop(item.backdropPath),
            TmdbImages.media(item.backdropPath, "w1280"),
            TmdbImages.poster(item.posterPath, "w780"),
            TmdbImages.media(item.posterPath, "w780"),
        )
    val heroUrl = heroUrls.firstOrNull { it != null }
    var resolvedHeroUrl by remember(item.id) { mutableStateOf<String?>(null) }
    val accent =
        rememberAnimatedArtworkAccent(
            url = resolvedHeroUrl ?: heroUrl,
            fallback = Brand.Primary, // design-system: brand-identity
            darkTheme = inheritedPalette.isDark,
            identity = item.id,
        )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.34f
        val artworkAspectRatio = maxWidth.value / heroHeight.value.coerceAtLeast(1f)
        val artworkFadeFraction =
            (HeroPageFade.value / heroHeight.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f)
        val sampledPageColor =
            rememberArtworkPageColor(
                url = resolvedHeroUrl,
                targetAspectRatio = artworkAspectRatio,
                fadeFraction = artworkFadeFraction,
            )
        val retainedPageColor = rememberRetainedArtworkPageColor("tmdb-detail:${item.id}")
        LaunchedEffect(sampledPageColor) {
            sampledPageColor?.let(retainedPageColor::update)
        }
        // RetainedArtworkPageColor already applies the appearance-aware safety envelope.
        // Do not protect it a second time or different posters collapse towards the same grey.
        val pageColor = retainedPageColor.value

        ArtworkPageTheme(
            background = pageColor,
            artworkAccent = accent,
        ) {
            val palette = LocalPalette.current
            val themeAccent = LocalAccentColors.current.accent
            val listState = rememberLazyListState()
            val lightPageReached by rememberScrolledPastHero(listState, heroHeight)
            StatusBarIconStyle(darkIcons = lightPageReached && !palette.isDark)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(pageColor ?: palette.background),
            )

            LazyColumn(
                Modifier.fillMaxSize().background(pageColor ?: palette.background),
                state = listState,
                contentPadding = PaddingValues(bottom = Dimens.contentBottom),
            ) {
                item {
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        FallbackImage(
                            urls = heroUrls,
                            contentDescription = item.title,
                            onResolvedUrl = { resolvedHeroUrl = it },
                            modifier = Modifier.fillMaxSize().fadeIntoPage(),
                        )
                        Box(Modifier.fillMaxSize().background(heroTopScrim()))
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
                                    fill = palette.card2,
                                    border = palette.border,
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
                                            .border(2.dp, palette.border, GlassShapes.poster),
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

                        if (item.mediaType == "tv") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable(
                                        onClickLabel = if (following) "取消追剧" else "加入追剧",
                                        onClick = component::toggleFollow,
                                    ).touchTarget()
                                    .solidGlass(
                                        shape = GlassShapes.card,
                                        fill = palette.card2,
                                        border = palette.border,
                                    ).padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (following) "已加入追剧" else "加入追剧",
                                        style = AppTypography.body.strong,
                                        color = palette.text,
                                    )
                                    Text(
                                        if (following) {
                                            "会出现在追剧中心，可继续设置提醒"
                                        } else {
                                            "无需先加入媒体库，也能跟踪播出排期"
                                        },
                                        style = AppTypography.caption.regular,
                                        color = palette.sub2,
                                    )
                                }
                                Icon(
                                    if (following) AppIcons.Check else AppIcons.Add,
                                    contentDescription = null,
                                    tint = themeAccent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

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
                                                            palette.border,
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
                        fill = palette.card2,
                        border = palette.border,
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
                fill = palette.card2,
                border = palette.border,
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
