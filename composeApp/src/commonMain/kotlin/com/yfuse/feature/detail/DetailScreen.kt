package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssShadow
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaElement
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages

/** How far the information sheet is pulled up over the lower edge of the artwork. */
private val HeroOverlap = 46.dp

/** Height of the collapsing top bar's content row, above the status bar inset. */
private val TopBarHeight = 52.dp

/** 详情 — artwork fills at least 56% of the viewport before the information sheet. */
@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val detail = state.detail
    val baseUrl = state.server?.baseUrl.orEmpty()

    val heroUrl = detail?.let { EmbyImages.backdrop(baseUrl, it) ?: EmbyImages.poster(baseUrl, it) }
    val accent = rememberDominantColor(heroUrl, Brand.Primary)

    var seasonPickerOpen by remember { mutableStateOf(false) }
    var overviewExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heroHeight = maxHeight * 0.56f
        val heroHeightPx = with(density) { heroHeight.toPx() }

        // The page carries the artwork's colour under both themes; the light theme used to
        // fall back to flat white, which dropped the ambient tint the design asks for.
        val detailSurface = remember(accent, palette.isDark) {
            if (palette.isDark) {
                accent.copy(alpha = 0.10f).compositeOver(Color(0xFF0B111C))
            } else {
                accent.copy(alpha = 0.05f).compositeOver(Color.White)
            }
        }
        // Blend band between the artwork and the page, drawn behind the floating sheet.
        val panelBrush = remember(detailSurface, density) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.30f to detailSurface.copy(alpha = 0.42f),
                    0.66f to detailSurface.copy(alpha = 0.90f),
                    1f to detailSurface,
                ),
                startY = 0f,
                endY = with(density) { 170.dp.toPx() },
            )
        }

        val listState = rememberLazyListState()
        val heroScroll = rememberHeroScroll(listState, heroHeightPx)
        val topBarProgress = rememberTopBarProgress(listState, heroHeightPx, density)
        val barSolid by remember(topBarProgress) { derivedStateOf { topBarProgress.value > 0.5f } }

        StatusBarIconStyle(darkIcons = !palette.isDark && (detail == null || barSolid))

        Box(Modifier.fillMaxSize().background(detailSurface))

        when {
            state.loading && detail == null -> DetailSkeleton(heroHeight)

            detail == null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error ?: "加载失败",
                    style = sc(13f, 400),
                    color = palette.sub,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { component.store.accept(DetailIntent.Retry) },
                    modifier = Modifier.glass(
                        shape = GlassShapes.chip,
                        fill = palette.card2,
                        border = palette.border,
                    ),
                ) {
                    Text("重试", style = sc(13f, 700), color = Brand.Primary)
                }
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = Dimens.contentBottom),
            ) {
                item(key = "hero") {
                    Hero(
                        url = heroUrl,
                        title = detail.title,
                        height = heroHeight,
                        surfaceColor = detailSurface,
                        sharedKey = "media-backdrop-${detail.id}",
                        scroll = heroScroll,
                    )
                }

                item(key = "sheet") {
                    val overlapPx = with(density) { HeroOverlap.roundToPx() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // `offset` would leave the item's measured height behind as dead
                            // space at the end of the list; shrink the slot instead.
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(
                                    placeable.width,
                                    (placeable.height - overlapPx).coerceAtLeast(0),
                                ) {
                                    placeable.place(0, -overlapPx)
                                }
                            }
                            .background(panelBrush)
                            .padding(horizontal = Dimens.pageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .shadow(Shadows.sheet, GlassShapes.sheet)
                                .glass(
                                    shape = GlassShapes.sheet,
                                    fill = if (palette.isDark) {
                                        palette.glassStrong
                                    } else {
                                        Color.White.copy(alpha = 0.68f)
                                    },
                                    border = if (palette.isDark) {
                                        palette.border
                                    } else {
                                        Color.White.copy(alpha = 0.94f)
                                    },
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TitleBlock(baseUrl, detail)
                            ActionRow(
                                accent = accent,
                                label = if (detail.type == "Series") "继续观看" else "立即播放",
                                resolving = state.resolvingPlay,
                                favorite = detail.isFavorite,
                                onPlay = { component.store.accept(DetailIntent.Play) },
                                onFavorite = {
                                    component.store.accept(DetailIntent.ToggleFavorite)
                                },
                            )
                            DetailQuickActions(
                                accent = accent,
                                played = detail.played,
                                onWatchLater = {
                                    component.store.accept(DetailIntent.AddToWatchLater)
                                },
                                onTogglePlayed = {
                                    component.store.accept(DetailIntent.TogglePlayed)
                                },
                                onDownload = component::download,
                            )
                        }
                        state.actionMessage?.let { message ->
                            Text(
                                message,
                                style = sc(11.5f, 600),
                                color = accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glass(
                                        shape = GlassShapes.chip,
                                        fill = accent.copy(alpha = 0.08f),
                                        border = accent.copy(alpha = 0.20f),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                val overview = detail.overview
                if (!overview.isNullOrBlank()) {
                    item(key = "overview") {
                        OverviewSection(
                            text = overview,
                            expanded = overviewExpanded,
                            onToggle = { overviewExpanded = !overviewExpanded },
                            accent = accent,
                            modifier = Modifier.sectionPadding(),
                        )
                    }
                }

                if (state.sources.isNotEmpty()) {
                    item(key = "sources") {
                        SourceSection(
                            sources = state.sources,
                            accent = accent,
                            onSelect = { serverId, itemId ->
                                component.store.accept(DetailIntent.PlaySource(serverId, itemId))
                            },
                            modifier = Modifier.sectionPadding(),
                        )
                    }
                }

                if (state.episodes.isNotEmpty()) {
                    item(key = "episode-header") {
                        EpisodeHeader(
                            accent = accent,
                            seasonLabel = state.seasons
                                .firstOrNull { it.id == state.selectedSeasonId }
                                ?.name
                                ?: "剧集",
                            episodeCount = state.episodes.size,
                            seasons = state.seasons.map { it.id to it.name },
                            selectedSeasonId = state.selectedSeasonId,
                            pickerOpen = seasonPickerOpen,
                            onTogglePicker = { seasonPickerOpen = !seasonPickerOpen },
                            onSelectSeason = {
                                seasonPickerOpen = false
                                component.store.accept(DetailIntent.SelectSeason(it))
                            },
                            modifier = Modifier.sectionPadding(),
                        )
                    }
                    items(state.episodes, key = { "ep-${it.id}" }) { episode ->
                        EpisodeRow(
                            baseUrl = baseUrl,
                            episode = episode,
                            accent = accent,
                            onPlay = {
                                component.store.accept(
                                    DetailIntent.PlayEpisode(
                                        episode.id,
                                        episode.resumePositionTicks ?: 0L,
                                    ),
                                )
                            },
                            modifier = Modifier
                                .padding(horizontal = Dimens.pageHorizontal)
                                .padding(bottom = 8.dp),
                        )
                    }
                }

                if (detail.people.isNotEmpty()) {
                    item(key = "cast") {
                        CastRow(baseUrl, detail.people, Modifier.padding(top = Dimens.sectionGap))
                    }
                }
            }
        }

        DetailTopBar(
            title = detail?.title.orEmpty(),
            progress = topBarProgress,
            surfaceColor = detailSurface,
            accent = accent,
            showPlay = detail != null,
            solid = barSolid,
            onBack = component.onBack,
            onPlay = { component.store.accept(DetailIntent.Play) },
        )

        if (state.resolvingPlay) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

// ---------------------------------------------------------------- scroll plumbing

/** Section blocks share one horizontal inset and one vertical rhythm (§8.4 大区块间距). */
private fun Modifier.sectionPadding(): Modifier =
    this.padding(horizontal = Dimens.pageHorizontal).padding(top = Dimens.sectionGap)

/** Pixels of the hero that have scrolled past the top edge. */
@Composable
private fun rememberHeroScroll(listState: LazyListState, heroHeightPx: Float): State<Float> =
    remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                heroHeightPx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }

/** 0 while the artwork owns the top edge, 1 once the glass bar has taken over. */
@Composable
private fun rememberTopBarProgress(
    listState: LazyListState,
    heroHeightPx: Float,
    density: Density,
): State<Float> = remember(listState, heroHeightPx, density) {
    val start = (heroHeightPx - with(density) { 136.dp.toPx() }).coerceAtLeast(1f)
    val span = with(density) { 64.dp.toPx() }
    derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) {
            1f
        } else {
            ((listState.firstVisibleItemScrollOffset - start) / span).coerceIn(0f, 1f)
        }
    }
}

// ---------------------------------------------------------------- chrome

/**
 * Backdrop under the annotated wash `0deg {page} 5%, rgba(20,15,25,.1) 60%,
 * rgba(20,15,25,.35)`. It lags the list on scroll and grows on over-drag; the back
 * affordance lives in [DetailTopBar] so it survives past the artwork.
 */
@Composable
private fun Hero(
    url: String?,
    title: String,
    height: Dp,
    surfaceColor: Color,
    sharedKey: String,
    scroll: State<Float>,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                // Keep a restrained upward parallax, but never move or stretch
                // the artwork downward when the list is over-scrolled.
                translationY = scroll.value * 0.35f
            },
    ) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().sharedMediaElement(sharedKey),
        )
        Box(
            Modifier.fillMaxSize().background(
                scrim(
                    0.05f to surfaceColor,
                    0.60f to Color(0xFF140F19).copy(alpha = 0.10f),
                    1f to Color(0xFF140F19).copy(alpha = 0.35f),
                ),
            ),
        )
    }
}

/**
 * Collapsing top bar. The back chip is always present — the artwork scrolls away but
 * the affordance does not — and the glass plate, title and play shortcut fade in as
 * the page takes over the top edge (§4.2 顶栏材质切换).
 */
@Composable
private fun DetailTopBar(
    title: String,
    progress: State<Float>,
    surfaceColor: Color,
    accent: Color,
    showPlay: Boolean,
    solid: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val palette = LocalPalette.current
    val plateFill = surfaceColor.copy(alpha = 0.94f)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value }
                .background(plateFill),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = progress.value }
                .background(palette.border),
        )
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(TopBarHeight)
                .padding(horizontal = Dimens.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val p = progress.value
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = lerp(Color.White, palette.text, p),
                modifier = Modifier
                    .size(38.dp)
                    .pressable(onClick = onBack)
                    .glass(
                        shape = CircleShape,
                        fill = Color(0xFF11151F).copy(alpha = 0.28f * (1f - p)),
                        border = Color.White.copy(alpha = 0.34f * (1f - p)),
                    )
                    .padding(11.dp),
            )
            Text(
                title,
                style = sc(14.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = progress.value },
            )
            if (showPlay) {
                Row(
                    Modifier
                        .graphicsLayer { alpha = progress.value }
                        .pressable(enabled = solid, onClick = onPlay)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = accent.copy(alpha = 0.14f),
                            border = accent.copy(alpha = 0.30f),
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(10.dp))
                    Text("播放", style = sc(11.5f, 700), color = accent)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- information sheet

/**
 * Poster + title cluster — `gap:14px`, bottom aligned; poster 84×118 with a 2px
 * white border and `0 10px 24px rgba(0,0,0,.25)`.
 */
@Composable
private fun TitleBlock(baseUrl: String, detail: MediaDetail) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Poster(
            url = EmbyImages.poster(baseUrl, detail),
            sharedKey = "media-poster-${detail.id}",
            modifier = Modifier
                .width(84.dp)
                .height(118.dp)
                .shadow(Shadows.detailPoster, GlassShapes.poster)
                .border(2.dp, Color.White, GlassShapes.poster),
        )
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(
                detail.title,
                style = sc(19f, 800),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 分级 moved to its own badge below, so it is not stated twice.
            Text(
                listOfNotNull(
                    detail.genres.firstOrNull(),
                    detail.year?.toString(),
                    detail.runtimeMinutes?.let { "$it 分钟" },
                ).joinToString(" · "),
                style = mr(11f, 400),
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.communityRating != null || detail.officialRating != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail.communityRating?.let { rating ->
                        RatingChip((rating * 10).toInt() / 10.0)
                    }
                    detail.officialRating?.let { CertificationBadge(it) }
                }
            }
        }
    }
}

/** ★ + community rating — `700 10px Manrope`, `padding:2px 7px`, `radius:6px`. */
@Composable
private fun RatingChip(rating: Double) {
    Row(
        Modifier
            .glass(
                shape = RoundedCornerShape(6.dp),
                fill = Brand.Imdb.copy(alpha = 0.16f),
                border = Brand.Imdb.copy(alpha = 0.26f),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.StarFilled, null, tint = Brand.Imdb, modifier = Modifier.size(9.dp))
        Text(rating.toString(), style = mr(10f, 700), color = Brand.Imdb)
    }
}

/** 分级 is a classification, not a score — it gets a neutral outline, not a brand colour. */
@Composable
private fun CertificationBadge(label: String) {
    val palette = LocalPalette.current
    Text(
        label,
        style = mr(10f, 600),
        color = palette.sub,
        modifier = Modifier
            .glass(
                shape = RoundedCornerShape(6.dp),
                fill = Color.Transparent,
                border = palette.sub.copy(alpha = 0.38f),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * Play + favourite. The play button is the page's one solid element: everything else
 * on the sheet is glass, so the primary action reads at a glance.
 */
@Composable
private fun ActionRow(
    accent: Color,
    label: String,
    resolving: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val palette = LocalPalette.current
    val ink = remember(accent) {
        if (accent.luminance() > 0.55f) Color(0xFF141A26) else Color.White
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .pressable(enabled = !resolving, onClick = onPlay)
                .cssShadow(
                    offsetY = 10.dp,
                    blur = 24.dp,
                    color = accent.copy(alpha = 0.34f),
                    shape = GlassShapes.card,
                )
                // Flat fill, not a gradient: a diagonal ramp across a 48dp pill reads as
                // a blotch rather than depth.
                .clip(GlassShapes.card)
                .background(accent)
                .border(1.dp, Color.White.copy(alpha = 0.22f), GlassShapes.card),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            if (resolving) {
                CircularProgressIndicator(Modifier.size(15.dp), color = ink, strokeWidth = 2.dp)
            } else {
                Icon(AppIcons.Play, null, tint = ink, modifier = Modifier.size(14.dp))
            }
            Text(label, style = sc(14f, 750), color = ink)
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier
                .size(48.dp)
                .pressable(onClick = onFavorite)
                .clip(CircleShape)
                .background(
                    if (palette.isDark) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        Color.White.copy(alpha = 0.62f)
                    },
                )
                .border(1.dp, accent.copy(alpha = if (favorite) 0.40f else 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                contentDescription = if (favorite) "取消收藏" else "加入收藏",
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Secondary actions — one weight below [ActionRow]: tinted fill, no outline. */
@Composable
private fun DetailQuickActions(
    accent: Color,
    played: Boolean,
    onWatchLater: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction(AppIcons.Bookmark, "稍后观看", accent, Modifier.weight(1f), onWatchLater)
        QuickAction(
            if (played) AppIcons.Check else AppIcons.Info,
            if (played) "标记未看" else "标记已看",
            accent,
            Modifier.weight(1f),
            onTogglePlayed,
        )
        QuickAction(AppIcons.Download, "下载", accent, Modifier.weight(1f), onDownload)
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .pressable(onClick = onClick)
            .clip(GlassShapes.chip)
            .background(
                if (palette.isDark) {
                    Color.White.copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.42f)
                },
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = sc(11f, 600), color = palette.body)
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sc(13f, 700), color = LocalPalette.current.text)
        trailing()
    }
}

/** 简介 — capped at three lines so the episode list stays reachable. */
@Composable
private fun OverviewSection(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var overflowed by remember(text) { mutableStateOf(false) }
    Column(
        modifier
            .animateContentSize()
            .pointerInput(Unit) { detectTapGestures { onToggle() } },
    ) {
        SectionHeader("简介")
        Text(
            text,
            style = sc(12.5f, 400, lineHeight = 12.5f * 1.6f),
            color = palette.body,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (overflowed || expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) "收起" else "展开",
                style = sc(11.5f, 700),
                color = accent,
            )
        }
    }
}

/** Always-visible bitrate and file-size comparison, presented as liquid glass. */
@Composable
private fun SourceSection(
    sources: List<ServerSource>,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sources, key = { it.serverId }) { entry ->
            val available = entry.reachable && entry.source != null
            Column(
                Modifier
                    .width(148.dp)
                    .height(104.dp)
                    .pressable(enabled = available && entry.itemId != null) {
                        entry.itemId?.let { onSelect(entry.serverId, it) }
                    }
                    .flatGlass(
                        shape = GlassShapes.card,
                        fill = if (palette.isDark) {
                            Color.White.copy(alpha = 0.07f)
                        } else {
                            Color.White.copy(alpha = 0.56f)
                        },
                        border = if (entry.isCurrent) {
                            accent.copy(alpha = 0.42f)
                        } else {
                            palette.border
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    entry.serverName,
                    style = sc(11.5f, 700),
                    color = if (entry.isCurrent) accent else palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.source?.bitrate ?: if (entry.reachable) "-- Mbps" else "服务器离线",
                    style = mr(12f, 700),
                    color = if (available) palette.text else palette.hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.source?.size ?: if (entry.reachable) "-- GB" else "无法获取大小",
                    style = mr(10.5f, 500),
                    color = if (available) palette.sub2 else palette.hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Season header with the `切换季数 ▾` chip. The season list expands inline rather than
 * as an overlay: a popup drawn from inside a lazy item is painted under the rows that
 * follow it, and the old one hard-coded a white plate that broke under the dark theme.
 */
@Composable
private fun EpisodeHeader(
    accent: Color,
    seasonLabel: String,
    episodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val rotation by animateFloatAsState(if (pickerOpen) 180f else 0f, label = "seasonChevron")
    Column(modifier.animateContentSize()) {
        SectionHeader(seasonLabel) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("共 $episodeCount 集", style = mr(10.5f, 500), color = palette.sub2)
                if (seasons.size > 1) {
                    Row(
                        Modifier
                            .pressable(onClick = onTogglePicker)
                            .glass(
                                shape = GlassShapes.thumb,
                                fill = accent.copy(alpha = 0.13f),
                                border = accent.copy(alpha = 0.28f),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("切换季数", style = mr(11f, 500), color = accent)
                        Icon(
                            AppIcons.ChevronDown,
                            null,
                            tint = accent,
                            modifier = Modifier.size(9.dp).graphicsLayer { rotationZ = rotation },
                        )
                    }
                }
            }
        }
        if (pickerOpen && seasons.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                items(seasons, key = { it.first }) { (id, name) ->
                    val selected = id == selectedSeasonId
                    Text(
                        name,
                        style = sc(11.5f, if (selected) 700 else 500),
                        color = if (selected) accent else palette.body,
                        maxLines = 1,
                        modifier = Modifier
                            .pressable { onSelectSeason(id) }
                            .glass(
                                shape = GlassShapes.thumb,
                                fill = if (selected) {
                                    accent.copy(alpha = 0.14f)
                                } else if (palette.isDark) {
                                    palette.card2
                                } else {
                                    Color.White.copy(alpha = 0.52f)
                                },
                                border = if (selected) {
                                    accent.copy(alpha = 0.30f)
                                } else {
                                    palette.border
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * One episode per row. A horizontal rail made a 24-episode season a scrubbing exercise;
 * the vertical list shows the still, the synopsis and the resume state together.
 */
@Composable
private fun EpisodeRow(
    baseUrl: String,
    episode: Episode,
    accent: Color,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onPlay)
            .glass(
                shape = GlassShapes.chip,
                fill = when {
                    watching -> accent.copy(alpha = 0.10f)
                    palette.isDark -> palette.card
                    else -> Color.White.copy(alpha = 0.56f)
                },
                border = if (watching) {
                    accent.copy(alpha = 0.28f)
                } else {
                    Color.White.copy(alpha = if (palette.isDark) 0.20f else 0.86f)
                },
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(
            url = EmbyImages.primary(baseUrl, episode.id, episode.primaryTag, maxHeight = 240),
            shape = GlassShapes.thumb,
            progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
            modifier = Modifier.width(116.dp).height(65.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "第${it}集" }, episode.name)
                    .joinToString(" · "),
                style = sc(12.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!episode.overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    episode.overview,
                    style = mr(10f, 400, lineHeight = 10f * 1.5f),
                    color = palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (watching) append("正在观看")
                    val runtime = episode.runtimeMinutes?.let { "$it 分钟" }
                    if (watching && runtime != null) append(" · ")
                    if (runtime != null) append(runtime)
                },
                style = mr(10.5f, 500),
                color = if (watching) accent else palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .glass(
                    shape = CircleShape,
                    fill = accent.copy(alpha = 0.14f),
                    border = accent.copy(alpha = 0.26f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(11.dp))
        }
    }
}

/** 主演 — `gap:14px`; 52px round avatars with `500 10px Manrope` names 6px below. */
@Composable
private fun CastRow(baseUrl: String, people: List<Person>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("主演", Modifier.padding(horizontal = Dimens.pageHorizontal))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            items(people.take(20), key = { it.id }) { person ->
                Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Poster(
                        url = EmbyImages.avatar(baseUrl, person),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(52.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.88f), CircleShape),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.name,
                        style = mr(10f, 500),
                        color = palette.body,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!person.role.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            person.role,
                            style = mr(9f, 400),
                            color = palette.hint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Loading placeholder shaped like the page it becomes, so the shared-element push from
 * the grid lands on a layout instead of an empty screen.
 */
@Composable
private fun DetailSkeleton(heroHeight: Dp) {
    val palette = LocalPalette.current
    val fill = if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(heroHeight).background(fill))
        Column(
            Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(84.dp).height(118.dp).clip(GlassShapes.poster).background(fill))
                Column(
                    Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.72f).height(18.dp).clip(GlassShapes.thumb).background(fill))
                    Box(Modifier.fillMaxWidth(0.46f).height(11.dp).clip(GlassShapes.thumb).background(fill))
                    Box(Modifier.width(64.dp).height(11.dp).clip(GlassShapes.thumb).background(fill))
                }
            }
            Box(Modifier.fillMaxWidth().height(48.dp).clip(GlassShapes.card).background(fill))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(Modifier.weight(1f).height(36.dp).clip(GlassShapes.chip).background(fill))
                }
            }
            Box(Modifier.fillMaxWidth().height(12.dp).clip(GlassShapes.thumb).background(fill))
            Box(Modifier.fillMaxWidth(0.86f).height(12.dp).clip(GlassShapes.thumb).background(fill))
        }
    }
}
