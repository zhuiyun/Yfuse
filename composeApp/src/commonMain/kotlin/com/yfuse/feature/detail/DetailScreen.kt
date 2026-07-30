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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.cssRadialGradient
import com.yfuse.core.designsystem.cssShadow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaElement
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.watch.WatchInviteShareSheet
import org.koin.core.context.GlobalContext

/** How far the information sheet is pulled up over the lower edge of the artwork. */
private val HeroOverlap = 46.dp

/** Height of the collapsing top bar's content row, above the status bar inset. */
private val TopBarHeight = 52.dp

/** The selected visual target puts the glass summary over the lower third of the hero. */
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

    val watchTogether = remember { GlobalContext.get().get<WatchTogetherClient>() }
    val watchPreferences = remember { GlobalContext.get().get<WatchTogetherPreferences>() }
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchPreferences.endpoint.collectAsState()
    val share = rememberShareHandler()
    var shareSheetOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heroHeight = maxHeight * 0.34f
        val heroHeightPx = with(density) { heroHeight.toPx() }

        val detailSurface = remember(accent, palette.isDark) {
            heroSurface(accent, palette.isDark)
        }
        // Blend band between the artwork and the page, drawn behind the floating sheet.
        val panelBrush = remember(detailSurface, density) {
            heroPanelBrush(detailSurface, density)
        }

        val listState = rememberLazyListState()
        val heroScroll = rememberHeroScroll(listState, heroHeightPx)
        val topBarProgress = rememberTopBarProgress(listState, heroHeightPx, density)
        val barSolid by remember(topBarProgress) { derivedStateOf { topBarProgress.value > 0.5f } }

        StatusBarIconStyle(darkIcons = !palette.isDark && (detail == null || barSolid))

        Box(Modifier.fillMaxSize().background(detailSurface))

        when {
            state.loading && detail == null -> DetailSkeleton(heroHeight)

            detail == null -> ErrorState(
                message = state.error ?: "加载失败",
                onRetry = { component.store.accept(DetailIntent.Retry) },
                modifier = Modifier.align(Alignment.Center),
            )

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
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .liftOverHero(HeroOverlap)
                            .background(panelBrush)
                            .padding(horizontal = Dimens.pageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InfoCard(baseUrl, detail)
                        DetailActionDock(
                            accent = Brand.Primary,
                            label = if (detail.type == "Series") "继续观看" else "立即播放",
                            resolving = state.resolvingPlay,
                            favorite = detail.isFavorite,
                            played = detail.played,
                            onPlay = { component.store.accept(DetailIntent.Play) },
                            onFavorite = {
                                component.store.accept(DetailIntent.ToggleFavorite)
                            },
                            onDownload = component::download,
                            onWatchLater = {
                                component.store.accept(DetailIntent.AddToWatchLater)
                            },
                            onTogglePlayed = {
                                component.store.accept(DetailIntent.TogglePlayed)
                            },
                            onWatchTogether = {
                                // Create the room from here and immediately start playing:
                                // one tap from "watch this with someone" to "invite ready".
                                watchTogether.createRoom(
                                    endpoint = watchEndpoint,
                                    mediaKey = detail.providerIds.watchKey(detail.id),
                                )
                                shareSheetOpen = true
                                component.store.accept(DetailIntent.Play)
                            },
                        )
                        state.actionMessage?.let { message ->
                            Text(
                                message,
                                style = sc(11.5f, 600),
                                color = accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .solidGlass(
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

                if (state.sources.any { it.reachable && it.source != null && it.itemId != null }) {
                    item(key = "sources") {
                        SourceSection(
                            sources = state.sources,
                            accent = Brand.Primary,
                            onSelect = { serverId, itemId ->
                                component.store.accept(DetailIntent.PlaySource(serverId, itemId))
                            },
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

                if (state.related.isNotEmpty()) {
                    item(key = "related") {
                        RelatedSection(
                            baseUrl = baseUrl,
                            items = state.related,
                            onOpen = { itemId ->
                                state.server?.id?.let { component.onOpenRelated(it, itemId) }
                            },
                        )
                    }
                }

                if (state.episodes.isNotEmpty()) {
                    item(key = "episodes") {
                        EpisodeSection(
                            baseUrl = baseUrl,
                            episodes = state.episodes,
                            accent = Brand.Primary,
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
                            onPlayEpisode = { episode ->
                                component.store.accept(
                                    DetailIntent.PlayEpisode(
                                        episode.id,
                                        episode.resumePositionTicks ?: 0L,
                                    ),
                                )
                            },
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

        if (shareSheetOpen && watchState.roomCode != null) {
            val invite = WatchInvite(
                roomCode = watchState.roomCode.orEmpty(),
                mediaKey = detail?.let { it.providerIds.watchKey(it.id) },
                title = detail?.title,
                // Only travel the endpoint when it isn't the built-in default, so the common
                // case produces a short link and no "unfamiliar relay" warning on the far end.
                endpoint = watchEndpoint.takeIf {
                    it.trimEnd('/') != WatchTogetherPreferences.DEFAULT_ENDPOINT.trimEnd('/')
                },
            )
            WatchInviteShareSheet(
                roomCode = invite.roomCode,
                title = detail?.title,
                participantCount = watchState.participantCount,
                shareText = invite.shareText(),
                onShare = share::shareText,
                onCopy = share::copyText,
                onDismiss = { shareSheetOpen = false },
            )
        }
    }
}

@Composable
private fun RelatedSection(
    baseUrl: String,
    items: List<MediaItem>,
    onOpen: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = Dimens.sectionGap)) {
        SectionHeader(
            title = "相关推荐",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(
                items,
                key = { index, item -> "related-${item.id}-$index" },
            ) { _, item ->
                Column(
                    Modifier
                        .width(96.dp)
                        .pressable { onOpen(item.id) },
                ) {
                    Poster(
                        url = EmbyImages.primary(
                            baseUrl = baseUrl,
                            itemId = item.posterItemId,
                            tag = item.posterTag,
                            maxHeight = 480,
                        ),
                        shape = GlassShapes.poster,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        style = sc(12f, 700),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.communityRating?.let { ((it * 10).toInt() / 10.0).toString() }
                            ?: item.year?.toString().orEmpty(),
                        style = mr(12f, 700),
                        color = Brand.Primary,
                        maxLines = 1,
                    )
                }
            }
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
 * Backdrop under [heroScrim]. It lags the list on scroll and grows on over-drag; the
 * back affordance lives in [DetailTopBar] so it survives past the artwork.
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
        Box(Modifier.fillMaxSize().background(heroScrim(surfaceColor)))
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
                    .solidGlass(
                        shape = CircleShape,
                        fill = lerp(
                            Color(0xFF11151F).copy(alpha = 0.28f),
                            palette.card2,
                            p,
                        ),
                        border = lerp(
                            Color.White.copy(alpha = 0.34f),
                            palette.border,
                            p,
                        ),
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
                        .solidGlass(
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
 * 片名卡 — a near-white glass plate lifted over the lower edge of the artwork, with a
 * specular highlight sweeping the upper-right corner (the reference's glass sheen).
 */
@Composable
private fun InfoCard(baseUrl: String, detail: MediaDetail) {
    val palette = LocalPalette.current
    val sheen = remember(palette.isDark) {
        cssRadialGradient(
            centerX = 0.80f,
            centerY = 0.04f,
            endStop = 0.52f,
            inner = Color.White.copy(alpha = if (palette.isDark) 0.14f else 0.88f),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(Shadows.sheet, GlassShapes.card)
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    palette.glassStrong
                } else {
                    Color.White.copy(alpha = 0.92f)
                },
                border = if (palette.isDark) {
                    palette.border
                } else {
                    Color.White.copy(alpha = 0.94f)
                },
            ),
    ) {
        Box(Modifier.matchParentSize().background(sheen))
        TitleBlock(baseUrl, detail, Modifier.padding(16.dp))
    }
}

/**
 * Poster + title cluster — `gap:16px`, vertically centred; poster 96×142 under
 * `0 10px 24px -12px rgba(28,36,58,.5)`. The community score reads as a labelled
 * figure (`TMDB 8.7`) rather than a badge, per the reference.
 *
 * No white keyline on the poster: the card it sits on is itself 92% white, so a 2px
 * white border only ate 4dp of artwork and blurred the poster's own edge.
 */
@Composable
private fun TitleBlock(baseUrl: String, detail: MediaDetail, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(
            url = EmbyImages.poster(baseUrl, detail),
            sharedKey = "media-poster-${detail.id}",
            modifier = Modifier
                .width(96.dp)
                .height(142.dp)
                .shadow(Shadows.detailPoster, GlassShapes.poster),
        )
        Column(Modifier.weight(1f)) {
            Text(
                detail.title,
                style = sc(22f, 800),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            // 分级 moved to its own badge below, so it is not stated twice.
            Text(
                listOfNotNull(
                    detail.genres.firstOrNull(),
                    detail.year?.toString(),
                    detail.runtimeMinutes?.let { "${it}分钟" },
                ).joinToString(" · "),
                style = sc(12.5f, 400),
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.communityRating != null || detail.officialRating != null) {
                Spacer(Modifier.height(9.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    detail.communityRating?.let { rating ->
                        RatingFigure((rating * 10).toInt() / 10.0)
                    }
                    detail.officialRating?.let { CertificationBadge(it) }
                }
            }
        }
    }
}

/** `TMDB` in the secondary ink, the figure itself large and in the accent. */
@Composable
private fun RatingFigure(rating: Double) {
    val palette = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TMDB", style = sc(12f, 600), color = palette.sub)
        Text(rating.toString(), style = mr(19f, 800), color = Brand.Primary)
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
            .solidGlass(
                shape = RoundedCornerShape(6.dp),
                fill = Color.Transparent,
                border = palette.sub.copy(alpha = 0.38f),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailActionDock(
    accent: Color,
    label: String,
    resolving: Boolean,
    favorite: Boolean,
    played: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onWatchLater: () -> Unit,
    onTogglePlayed: () -> Unit,
    onWatchTogether: () -> Unit,
) {
    val palette = LocalPalette.current
    val ink = remember(accent) {
        if (accent.luminance() > 0.55f) Color(0xFF141A26) else Color.White
    }
    // The reference's play key is a pill with a light-to-accent ramp, not a flat fill.
    val playFill = remember(accent) {
        cssLinearGradient(120f, 0f to lerp(accent, Color.White, 0.20f), 1f to accent)
    }
    var moreOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.07f)
                } else {
                    Color.White.copy(alpha = 0.92f)
                },
                border = if (palette.isDark) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    Color(0xFFE4E9F2)
                },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .pressable(enabled = !resolving, onClick = onPlay)
                    .cssShadow(
                        offsetY = 8.dp,
                        blur = 20.dp,
                        color = accent.copy(alpha = 0.32f),
                        shape = RoundedCornerShape(15.dp),
                    )
                    .clip(RoundedCornerShape(15.dp))
                    .background(playFill),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (resolving) {
                    CircularProgressIndicator(Modifier.size(15.dp), color = ink, strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Play, null, tint = ink, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = sc(14f, 750), color = ink)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockAction(
                icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = "收藏",
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = onFavorite,
            )
            DockAction(
                icon = AppIcons.Download,
                label = "下载",
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = onDownload,
            )
            DockAction(
                icon = AppIcons.More,
                label = "更多",
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { moreOpen = !moreOpen },
            )
        }
        if (moreOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(GlassShapes.card)
                    .background(
                        if (palette.isDark) {
                            Color.White.copy(alpha = 0.05f)
                        } else {
                            accent.copy(alpha = 0.045f)
                        },
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QuickAction(
                    AppIcons.Bookmark,
                    "稍后观看",
                    accent,
                    Modifier.weight(1f),
                    onWatchLater,
                )
                QuickAction(
                    if (played) AppIcons.Check else AppIcons.Info,
                    if (played) "标记未看" else "标记已看",
                    accent,
                    Modifier.weight(1f),
                    onTogglePlayed,
                )
            }
        }
        // 一起看 sits with the play affordances rather than in the settings of a player you
        // must already have open: deciding to watch something *with* someone happens here,
        // at the point you decide to watch it at all.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DockAction(
                icon = AppIcons.User,
                label = "一起看",
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = onWatchTogether,
            )
        }
    }
}

@Composable
private fun DockAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(48.dp)
            .solidGlass(
                shape = shape,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.075f)
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                border = if (palette.isDark) {
                    Color.White.copy(alpha = 0.19f)
                } else {
                    accent.copy(alpha = 0.16f)
                },
            )
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = palette.body, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = sc(11.5f, 600), color = palette.body, maxLines = 1)
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
        Text(title, style = sc(15f, 700), color = LocalPalette.current.text)
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
        SectionHeader("剧情简介")
        Text(
            text,
            style = sc(13f, 400, lineHeight = 13f * 1.65f),
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

/**
 * 资源对比 — one card per server that holds this title; unreachable servers stay hidden.
 *
 * The cards used to carry a `rgba(255,255,255,.82)` keyline, which is invisible on a
 * white page: the row read as loose text floating between two titled sections, with no
 * card boundary at all. 「影视详情页 优化」 draws the boundary in ink instead — idle
 * `rgba(20,26,38,.06)` over a barely-there fill, selected a 1.5px accent ring — so that
 * is what these use, and the block gets the section header every other block has.
 */
@Composable
private fun SourceSection(
    sources: List<ServerSource>,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val availableSources = remember(sources) {
        sources.filter { it.reachable && it.source != null && it.itemId != null }
    }
    Column(modifier) {
        SectionHeader(
            title = "资源对比",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        ) {
            Text(
                "${availableSources.size} 个媒体库 · 横向滑动",
                style = mr(10.5f, 500),
                color = palette.sub2,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                availableSources,
                key = { index, entry -> "source-${entry.serverId}-${entry.itemId}-$index" },
            ) { _, entry ->
                SourceCard(
                    entry = entry,
                    accent = accent,
                    onSelect = { entry.itemId?.let { onSelect(entry.serverId, it) } },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(entry: ServerSource, accent: Color, onSelect: () -> Unit) {
    val palette = LocalPalette.current
    val selected = entry.isCurrent
    // 1.5dp on the selected ring, so switching sources moves the edge as well as the
    // colour — `solidGlass` is fixed at `Dimens.hairline`, hence the explicit border.
    val edge = when {
        selected -> accent
        palette.isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color(0xFF141A26).copy(alpha = 0.12f)
    }
    val fill = when {
        selected -> accent.copy(alpha = if (palette.isDark) 0.16f else 0.09f)
        palette.isDark -> Color.White.copy(alpha = 0.06f)
        else -> Color(0xFF141A26).copy(alpha = 0.035f)
    }
    Column(
        Modifier
            .width(140.dp)
            .heightIn(min = 60.dp)
            .clip(GlassShapes.thumb)
            .background(fill)
            .border(if (selected) 1.5.dp else Dimens.hairline, edge, GlassShapes.thumb)
            .pressable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 可达性圆点 —— the list is already filtered to reachable servers, so this
            // is a positive confirmation rather than a warning.
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5ECB84)),
            )
            Text(
                entry.serverName,
                style = sc(11.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "当前片源",
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            entry.source?.quality.orEmpty().ifBlank { "未知画质" },
            style = mr(10.5f, 600),
            color = if (selected) accent else palette.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(entry.source?.bitrate, entry.source?.size)
                .joinToString(" · ")
                .ifBlank { "读取中" },
            style = mr(9.5f, 500),
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                            .solidGlass(
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
                itemsIndexed(
                    seasons,
                    key = { index, season -> "season-${season.first}-$index" },
                ) { _, (id, name) ->
                    val selected = id == selectedSeasonId
                    Text(
                        name,
                        style = sc(11.5f, if (selected) 700 else 500),
                        color = if (selected) accent else palette.body,
                        maxLines = 1,
                        modifier = Modifier
                            .pressable { onSelectSeason(id) }
                            .solidGlass(
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

@Composable
private fun EpisodeSection(
    baseUrl: String,
    episodes: List<Episode>,
    accent: Color,
    seasonLabel: String,
    episodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
) {
    Column(Modifier.padding(top = Dimens.sectionGap)) {
        EpisodeHeader(
            accent = accent,
            seasonLabel = seasonLabel,
            episodeCount = episodeCount,
            seasons = seasons,
            selectedSeasonId = selectedSeasonId,
            pickerOpen = pickerOpen,
            onTogglePicker = onTogglePicker,
            onSelectSeason = onSelectSeason,
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(
                episodes,
                key = { index, episode -> "ep-${episode.id}-$index" },
            ) { _, episode ->
                EpisodeCard(baseUrl, episode, accent) { onPlayEpisode(episode) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    baseUrl: String,
    episode: Episode,
    accent: Color,
    onPlay: () -> Unit,
) {
    val palette = LocalPalette.current
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    Column(
        Modifier
            .width(172.dp)
            .pressable(onClick = onPlay)
            .solidGlass(
                shape = GlassShapes.card,
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
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Poster(
            url = EmbyImages.primary(baseUrl, episode.id, episode.primaryTag, maxHeight = 240),
            shape = GlassShapes.thumb,
            progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(86.dp),
        )
        Column {
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
                    maxLines = 1,
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
            itemsIndexed(
                people.take(20),
                key = { index, person -> "person-${person.id}-$index" },
            ) { _, person ->
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(96.dp).height(142.dp).clip(GlassShapes.poster).background(fill))
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
