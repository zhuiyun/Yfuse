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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyBadge
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.liquidGlass
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
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.watch.WatchInviteShareSheet
import org.koin.core.context.GlobalContext

/** Height of the collapsing top bar's content row, above the status bar inset. */
private val TopBarHeight = 52.dp

/** The sheet's own rhythm — above the title block, and between it and everything after. */
private val SheetGap = 18.dp

/**
 * A one-line title with year, rating and genre under it. Only the seed for the measured
 * lift, so being a little out costs one frame of settling and nothing else.
 */
private val TypicalCaptionHeight = 116.dp

/** The selected visual target puts the glass summary over the lower third of the hero. */
@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val detail = state.detail
    val baseUrl = state.server?.baseUrl.orEmpty()
    // Emby answers 401 for artwork without it when the server requires authentication, so
    // every image on this page — hero, 艺术图, 剧集, 主演, 相关推荐 — is built with it.
    val accessToken = state.server?.accessToken.orEmpty()

    // The backdrop is the hero, the poster is what stands in when the item has none.
    val heroUrls = detail?.let {
        listOf(
            EmbyImages.backdrop(baseUrl, it, accessToken = accessToken),
            EmbyImages.poster(baseUrl, it, accessToken = accessToken),
        )
    }.orEmpty()
    val accent = rememberDominantColor(heroUrls.firstOrNull { it != null }, Brand.Primary)

    var seasonPickerOpen by remember { mutableStateOf(false) }
    var overviewExpanded by remember { mutableStateOf(false) }
    // Hoisted out of the list: the hero badges what this copy is, and 媒体信息 at the foot
    // of the page spells the same file out — one answer to "which file", read twice.
    val selectedVersion = detail?.versions?.firstOrNull { it.id == state.selectedVersionId }
        ?: detail?.versions?.firstOrNull()
    // `S1 E4 · 20:01`, under the key. Rebuilt only when the target or the progress moves.
    val playDetailLine = remember(state.playTarget, state.playPositionTicks) {
        val target = state.playTarget
        val coordinate = listOfNotNull(
            target?.seasonNumber?.let { "S$it" },
            target?.episodeNumber?.let { "E$it" },
        ).joinToString(" ").takeIf { it.isNotBlank() }
        val resume = state.playPositionTicks
            .takeIf { it > 0L }
            ?.let { clockLabel(it / 10_000L) }
        listOfNotNull(coordinate, resume).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    val watchTogether = remember { GlobalContext.get().get<WatchTogetherClient>() }
    val watchPreferences = remember { GlobalContext.get().get<WatchTogetherPreferences>() }
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchPreferences.endpoint.collectAsState()
    val share = rememberShareHandler()
    var shareSheetOpen by remember { mutableStateOf(false) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var sourceListOpen by remember { mutableStateOf(false) }
    var allEpisodesOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heroHeight = maxHeight * 0.40f
        val heroHeightPx = with(density) { heroHeight.toPx() }

        // How far the sheet rides up over the artwork: its own title block, plus the gap
        // above it, so the artwork's lower edge lands exactly where 播放 begins. Measured
        // rather than fixed — a two-line title is 30dp taller than a one-line one, and a
        // guess would either clip the artwork short or push the title off it. Seeded with
        // a typical height so the first frame is already close and the correction does not
        // read as a jump.
        var captionLift by remember { mutableStateOf(TypicalCaptionHeight + SheetGap) }

        val detailSurface = remember(accent, palette.isDark) {
            heroSurface(accent, palette.isDark)
        }
        // Blend band between the artwork and the page. It starts where the artwork ends,
        // leaving the title block on clean artwork — see [heroPanelBrush].
        val panelBrush = remember(detailSurface, density, captionLift) {
            heroPanelBrush(detailSurface, density, start = captionLift)
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
                        urls = heroUrls,
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
                            .liftOverHero(captionLift)
                            .background(panelBrush)
                            .padding(horizontal = Dimens.pageHorizontal)
                            .padding(top = SheetGap),
                        verticalArrangement = Arrangement.spacedBy(SheetGap),
                    ) {
                        TitleBlock(
                            detail = detail,
                            // A series has no file of its own, so its 杜比 facts belong to
                            // the episode 继续观看 would open — which is the copy the badge
                            // would be describing anyway.
                            version = selectedVersion ?: state.playTarget?.versions?.firstOrNull(),
                            modifier = Modifier.onSizeChanged {
                                captionLift = with(density) { it.height.toDp() } + SheetGap
                            },
                        )
                        DetailActionDock(
                            accent = Brand.Primary,
                            label = if (detail.type == "Series") "继续观看" else "播放",
                            detailLine = playDetailLine,
                            resolving = state.resolvingPlay,
                            favorite = detail.isFavorite,
                            played = detail.played,
                            canPlayFromStart = state.playPositionTicks > 0L,
                            onPlay = { component.store.accept(DetailIntent.Play) },
                            onPlayFromStart = {
                                component.store.accept(DetailIntent.PlayFromStart)
                            },
                            onFavorite = {
                                component.store.accept(DetailIntent.ToggleFavorite)
                            },
                            onTogglePlayed = {
                                component.store.accept(DetailIntent.TogglePlayed)
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

                if (detail.versions.isNotEmpty()) {
                    item(key = "versions") {
                        VersionSection(
                            versions = detail.versions,
                            selectedId = state.selectedVersionId,
                            accent = Brand.Primary,
                            onSelect = {
                                component.store.accept(DetailIntent.SelectVersion(it))
                            },
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

                // The tracks of whatever file will actually open. A film's own, or, for a
                // series, the episode 继续观看 resolves to — the same copy the 杜比 badge
                // above describes.
                val playableVersion = selectedVersion ?: state.playTarget?.versions?.firstOrNull()
                if (playableVersion != null &&
                    (playableVersion.audioTracks.size > 1 ||
                        playableVersion.subtitleTracks.isNotEmpty())
                ) {
                    item(key = "tracks") {
                        TrackSection(
                            version = playableVersion,
                            audioLanguage = state.preferredAudioLanguage,
                            subtitleLanguage = state.preferredSubtitleLanguage,
                            accent = Brand.Primary,
                            onSelectAudio = {
                                component.store.accept(DetailIntent.SelectAudioLanguage(it))
                            },
                            onSelectSubtitle = {
                                component.store.accept(DetailIntent.SelectSubtitleLanguage(it))
                            },
                            modifier = Modifier.padding(top = Dimens.sectionGap),
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
                            onSeeAll = { sourceListOpen = true },
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

                if (detail.genres.isNotEmpty()) {
                    item(key = "genres") {
                        GenreSection(detail.genres, Modifier.sectionPadding())
                    }
                }

                if (detail.backdropTags.isNotEmpty()) {
                    item(key = "artwork") {
                        ArtworkSection(
                            // Whichever item owns the artwork — the episode's own, or the
                            // show's when the episode has none. The index addresses that
                            // item's backdrop list, so it has to be that item's id.
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            itemId = detail.backdropItemId,
                            tags = detail.backdropTags,
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

                if (externalLinks(detail.providerIds).isNotEmpty()) {
                    item(key = "links") {
                        ExternalLinksSection(detail.providerIds, Modifier.sectionPadding())
                    }
                }

                if (state.episodes.isNotEmpty()) {
                    item(key = "episodes") {
                        EpisodeSection(
                            baseUrl = baseUrl,
                            accessToken = accessToken,
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
                            onSeeAll = { allEpisodesOpen = true },
                        )
                    }
                }

                if (detail.people.isNotEmpty()) {
                    item(key = "cast") {
                        CastRow(
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            people = detail.people,
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

                if (state.related.isNotEmpty()) {
                    item(key = "related") {
                        RelatedSection(
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            items = state.related,
                            onOpen = { itemId ->
                                state.server?.id?.let { component.onOpenRelated(it, itemId) }
                            },
                        )
                    }
                }

                // Last on the page: 媒体信息 is the file's technical readout — codec,
                // bitrate, size — which is what someone comes back for, not what they came
                // for. Everything above it is about the title itself.
                if (selectedVersion != null) {
                    item(key = "mediaInfo") {
                        MediaInfoSection(
                            version = selectedVersion,
                            dateCreated = detail.dateCreated,
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
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
            showMore = detail != null,
            solid = barSolid,
            onBack = component.onBack,
            onPlay = { component.store.accept(DetailIntent.Play) },
            onMore = { moreSheetOpen = true },
        )

        if (state.resolvingPlay) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        if (moreSheetOpen && detail != null) {
            GlassDialog(onDismiss = { moreSheetOpen = false }) {
                OverlayHeader(
                    title = detail.title,
                    subtitle = "更多操作",
                    onClose = { moreSheetOpen = false },
                )
                OverlayOptionRow(
                    label = "下载到本地",
                    selected = false,
                    onClick = {
                        moreSheetOpen = false
                        component.download()
                    },
                )
                OverlayOptionRow(
                    label = "稍后观看",
                    selected = false,
                    onClick = {
                        moreSheetOpen = false
                        component.store.accept(DetailIntent.AddToWatchLater)
                    },
                )
                // 一起看 belongs where the decision is made — at the point of choosing what
                // to watch, not in the settings of a player you must already have open.
                //
                // Playback is *not* started here. It used to be, in the same tap, and the
                // player activity that came up covered the invite sheet this opens — the host
                // reached the film without ever being shown the link they created it for. The
                // sheet starts playback itself, once the invite has been sent.
                OverlayOptionRow(
                    label = "一起看",
                    selected = watchState.roomCode != null,
                    onClick = {
                        moreSheetOpen = false
                        watchTogether.createRoom(
                            endpoint = watchEndpoint,
                            mediaKey = detail.providerIds.watchKey(detail.id),
                        )
                        shareSheetOpen = true
                    },
                )
            }
        }

        if (sourceListOpen) {
            SourceListDialog(
                sources = state.sources,
                accent = Brand.Primary,
                onSelect = { serverId, itemId ->
                    sourceListOpen = false
                    component.store.accept(DetailIntent.PlaySource(serverId, itemId))
                },
                onDismiss = { sourceListOpen = false },
            )
        }

        // A layer rather than a route: it covers the page that owns this season and its
        // artwork, and the detail store has already loaded the episodes it lists.
        if (allEpisodesOpen && detail != null) {
            SeasonEpisodesPage(
                seasonLabel = state.seasons.firstOrNull { it.id == state.selectedSeasonId }
                    ?.name
                    ?: "剧集",
                seriesName = detail.seriesName?.ifBlank { null } ?: detail.title,
                episodes = state.episodes,
                heroUrls = heroUrls,
                baseUrl = baseUrl,
                accessToken = accessToken,
                accent = Brand.Primary,
                currentEpisodeId = detail.id.takeIf { detail.type == "Episode" },
                onPlayEpisode = { episode ->
                    allEpisodesOpen = false
                    component.store.accept(
                        DetailIntent.PlayEpisode(episode.id, episode.resumePositionTicks ?: 0L),
                    )
                },
                onDismiss = { allEpisodesOpen = false },
            )
        }

        // Opened as soon as the room is asked for, not once it exists: the relay can be slow
        // or down, and the tap used to have no visible result at all in either case.
        if (shareSheetOpen) {
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
                roomCode = watchState.roomCode,
                connecting = watchState.connecting,
                error = watchState.error,
                title = detail?.title,
                participantCount = watchState.participantCount,
                shareText = invite.shareText(),
                onShare = share::shareText,
                onCopy = share::copyText,
                onStartPlayback = {
                    shareSheetOpen = false
                    component.store.accept(DetailIntent.Play)
                },
                onDismiss = { shareSheetOpen = false },
            )
        }
    }
}

@Composable
private fun RelatedSection(
    baseUrl: String,
    accessToken: String,
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
                            accessToken = accessToken,
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
    urls: List<String?>,
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
        FallbackImage(
            urls = urls,
            contentDescription = title,
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
    showMore: Boolean,
    solid: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
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
            // What these two sit on changes as the bar fills in, and that is what decides
            // whether they are dense glass or pale glass — so it travels with them.
            val behind = lerp(HeroInk, surfaceColor, p)
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = lerp(Color.White, palette.text, p),
                modifier = Modifier
                    .size(38.dp)
                    .pressable(onClick = onBack)
                    .liquidGlass(
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
                        over = behind,
                        sheen = 0.7f,
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
                        .liquidGlass(
                            shape = GlassShapes.chip,
                            fill = accent.copy(alpha = 0.14f),
                            border = accent.copy(alpha = 0.30f),
                            // It only ever appears once the bar's own plate is opaque.
                            over = surfaceColor,
                            sheen = 0.7f,
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(10.dp))
                    Text("播放", style = sc(11.5f, 700), color = accent)
                }
            }
            if (showMore) {
                // Unlike the title and the play shortcut this does not fade in with scroll:
                // it is the only route to 下载 / 稍后观看 / 一起看, so it has to be reachable
                // from the top of the page as well as the bottom.
                Icon(
                    AppIcons.More,
                    contentDescription = "更多操作",
                    tint = lerp(Color.White, palette.text, p),
                    modifier = Modifier
                        .size(38.dp)
                        .pressable(onClick = onMore)
                        .liquidGlass(
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
                            over = behind,
                            sheen = 0.7f,
                        )
                        .padding(11.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- information sheet

/**
 * 片名 — centred on the artwork's lower edge, with no plate under it.
 *
 * What this replaces: a poster thumbnail and a glass card lifted 16dp over the backdrop.
 * Between them they covered the lower quarter of artwork that is only 40% of the screen
 * tall, to restate a poster the user had just tapped and a title the top bar already
 * carries. Dropping the plate gave the backdrop its height back; riding on the artwork
 * rather than under it means the block costs the picture nothing at all — the artwork now
 * runs the full way down to 播放.
 *
 * Which is also why the inks here are fixed rather than from the palette: this copy is on
 * a photograph in both themes. The caller keeps the artwork clean behind it — see the
 * `start` on [heroPanelBrush] — so nothing here ever sits half on artwork and half on
 * page, where no single ink would work.
 */
@Composable
private fun TitleBlock(
    detail: MediaDetail,
    version: MediaVersion?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            detail.title,
            style = sc(23f, 800),
            color = ArtworkInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(
            detail.year?.toString(),
            detail.runtimeMinutes?.let(::runtimeLabel),
        )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                facts.joinToString(" · "),
                style = sc(12f, 400),
                color = ArtworkInkSub,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        if (detail.communityRating != null || detail.officialRating != null) {
            Spacer(Modifier.height(9.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                detail.communityRating?.let { RatingFigure((it * 10).toInt() / 10.0) }
                detail.officialRating?.let { CertificationBadge(it) }
            }
        }
        detail.genres.firstOrNull()?.let { genre ->
            Spacer(Modifier.height(8.dp))
            Text(genre, style = sc(11.5f, 500), color = ArtworkInkFaint, maxLines = 1)
        }
        // Only what this copy actually carries. A page that always claims Dolby says
        // nothing; here the badge is the answer to "is this the good file", which on a
        // title the library holds twice is the question being asked.
        val dolbyVision = version?.isDolbyVision == true
        val dolbyAtmos = version?.hasDolbyAtmos == true
        if (dolbyVision || dolbyAtmos) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (dolbyVision) DolbyBadge("VISION", ArtworkInk)
                if (dolbyAtmos) DolbyBadge("ATMOS", ArtworkInk)
            }
        }
    }
}

/** `20:01` / `1:20:01` — a position on a timeline, in the shape a player prints it. */
private fun clockLabel(positionMs: Long): String {
    val totalSeconds = positionMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val tail = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:$tail" else "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** `1小时13分钟` — hours only when there are any, because "0小时13分钟" reads as a bug. */
private fun runtimeLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "${rest}分钟"
        rest == 0 -> "${hours}小时"
        else -> "${hours}小时${rest}分钟"
    }
}

/**
 * The title block's inks.
 *
 * It sits on the artwork now, not on the page, so it cannot take the palette: the page
 * ink is dark in the light theme and the artwork underneath is dark in both. This is the
 * same answer the library hero and the player's panel already reach — white copy over a
 * scrim, and the accent's light end rather than the spec's `#3D64C9`, which is a
 * light-theme ink and goes muddy on a photograph.
 */
private val ArtworkInk = Color.White
private val ArtworkInkSub = Color.White.copy(alpha = 0.80f)
private val ArtworkInkFaint = Color.White.copy(alpha = 0.66f)

/** `TMDB` in the secondary ink, the figure itself large and in the accent. */
@Composable
private fun RatingFigure(rating: Double) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TMDB", style = sc(12f, 600), color = ArtworkInkSub)
        Text(rating.toString(), style = mr(19f, 800), color = Brand.PrimaryGradTop)
    }
}

/** 分级 is a classification, not a score — it gets a neutral outline, not a brand colour. */
@Composable
private fun CertificationBadge(label: String) {
    Text(
        label,
        style = mr(10f, 600),
        color = ArtworkInkSub,
        modifier = Modifier
            .solidGlass(
                shape = RoundedCornerShape(6.dp),
                fill = Color.Transparent,
                border = ArtworkInkSub.copy(alpha = 0.42f),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailActionDock(
    accent: Color,
    label: String,
    /**
     * `S1 E4 · 20:01` — which entry the key opens and where it picks up.
     *
     * The button used to say 继续观看 and nothing else, which on a show is the one word that
     * leaves the actual question unanswered: continue *what*. Null for a film that has
     * never been started, where there is nothing to add.
     */
    detailLine: String?,
    resolving: Boolean,
    favorite: Boolean,
    played: Boolean,
    /** Shown only when there is progress to discard. */
    canPlayFromStart: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onFavorite: () -> Unit,
    onTogglePlayed: () -> Unit,
) {
    val palette = LocalPalette.current
    val ink = remember(accent) {
        if (accent.luminance() > 0.55f) Color(0xFF141A26) else Color.White
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A white key over the artwork's own colour, as the reference has it. The accent is
        // not spent here: it is the page's one highlight colour and is worth more on 收藏 /
        // 已观看 and the selected version, where it distinguishes a *state*, than on the
        // button whose position and size already make it unmistakable.
        //
        // White glass on the light theme's white page has no edge of its own, so the key
        // leans on the two cues [liquidGlass] adds — the cool shade along its lower half and
        // the luminous rim — plus the lift beneath it.
        val playInk = Color(0xFF141A26)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .pressable(enabled = !resolving, onClick = onPlay)
                .shadow(GlassLift.key, CircleShape)
                .liquidGlass(
                    shape = CircleShape,
                    fill = Color.White.copy(alpha = 0.90f),
                    border = Color(0xFFC9D6E8),
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (resolving) {
                CircularProgressIndicator(
                    Modifier.size(15.dp),
                    color = playInk,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(AppIcons.Play, null, tint = playInk, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(9.dp))
            if (detailLine == null) {
                Text(label, style = sc(14.5f, 750), color = playInk)
            } else {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(label, style = sc(13.5f, 750), color = playInk, maxLines = 1)
                    Text(
                        detailLine,
                        style = mr(10f, 500),
                        color = playInk.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (canPlayFromStart) {
            Spacer(Modifier.height(10.dp))
            // A quiet second option, not a second key. Restarting is the rarer of the two
            // and giving it equal weight would make the page ask a question every time.
            Text(
                "从头播放",
                style = sc(12f, 600),
                color = palette.sub,
                modifier = Modifier
                    .pressable(onClick = onPlayFromStart)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        // Only the two states worth reading at a glance stay on the page. 下载, 稍后观看 and
        // 一起看 are each a one-off decision rather than a status, so they moved behind the
        // top bar's ⋯ — six equal-weight buttons under the title made none of them primary.
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RoundAction(
                icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = "收藏",
                active = favorite,
                accent = accent,
                onClick = onFavorite,
            )
            RoundAction(
                icon = AppIcons.Check,
                label = if (played) "已观看" else "标记已看",
                active = played,
                accent = accent,
                onClick = onTogglePlayed,
            )
        }
    }
}

/** A circular toggle with its name beneath — the reference's 收藏 / 已观看 pair. */
@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accent else palette.body,
            modifier = Modifier
                .size(46.dp)
                .pressable(onClick = onClick)
                .shadow(GlassLift.control, CircleShape)
                .liquidGlass(
                    shape = CircleShape,
                    fill = if (active) {
                        accent.copy(alpha = if (palette.isDark) 0.20f else 0.12f)
                    } else if (palette.isDark) {
                        Color.White.copy(alpha = 0.075f)
                    } else {
                        Color.White.copy(alpha = 0.72f)
                    },
                    border = if (active) {
                        accent.copy(alpha = 0.32f)
                    } else if (palette.isDark) {
                        Color.White.copy(alpha = 0.19f)
                    } else {
                        Color(0xFFE4E9F2)
                    },
                    // 46dp of glass: a full-strength specular covers a third of the circle
                    // and reads as a blown highlight rather than a curved surface.
                    sheen = 0.7f,
                )
                .padding(14.dp),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            style = sc(11f, 500),
            color = if (active) accent else palette.sub,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- sections

/** 分类 — the genres as chips, which is the only place they are listed in full. */
@Composable
private fun GenreSection(genres: List<String>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("分类")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genres.take(6).forEach { genre ->
                Text(
                    genre,
                    style = sc(11.5f, 600),
                    color = palette.body,
                    modifier = Modifier
                        .solidGlass(
                            shape = GlassShapes.chip,
                            fill = if (palette.isDark) {
                                Color.White.copy(alpha = 0.075f)
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            },
                            border = palette.border,
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 艺术图 — the item's other backdrops.
 *
 * Only the first is ever used as the hero, so the rest are artwork the library holds and
 * nothing in the app has shown until now.
 */
@Composable
private fun ArtworkSection(
    baseUrl: String,
    accessToken: String,
    itemId: String,
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("艺术图", Modifier.padding(horizontal = Dimens.pageHorizontal))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            itemsIndexed(tags, key = { _, tag -> tag }) { index, tag ->
                FallbackImage(
                    urls = listOf(
                        EmbyImages.backdropAt(
                            baseUrl,
                            itemId,
                            index,
                            tag,
                            maxWidth = 720,
                            accessToken = accessToken,
                        ),
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .width(232.dp)
                        .height(130.dp)
                        .clip(GlassShapes.card),
                )
            }
        }
    }
}

/** 外部链接 — where this title lives outside the library. */
@Composable
private fun ExternalLinksSection(
    providerIds: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val links = remember(providerIds) { externalLinks(providerIds) }
    if (links.isEmpty()) return
    Column(modifier) {
        SectionHeader("外部链接")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (label, url) ->
                Row(
                    Modifier
                        .pressable { runCatching { uriHandler.openUri(url) } }
                        .shadow(GlassLift.control, GlassShapes.chip)
                        .liquidGlass(
                            shape = GlassShapes.chip,
                            fill = if (palette.isDark) {
                                Color.White.copy(alpha = 0.075f)
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            },
                            border = palette.border,
                            sheen = 0.7f,
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Cloud,
                        contentDescription = null,
                        tint = palette.sub,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(label, style = sc(11.5f, 600), color = palette.body)
                }
            }
        }
    }
}

/**
 * The provider ids Emby carries that have a public page worth opening. Anything else it
 * returns (a scraper's internal key, say) has nowhere to link to and is left out.
 */
private fun externalLinks(providerIds: Map<String, String>): List<Pair<String, String>> {
    fun id(name: String) = providerIds.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
    return buildList {
        id("Tmdb")?.let { add("TMDB" to "https://www.themoviedb.org/movie/$it") }
        id("Imdb")?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
        id("Tvdb")?.let { add("TheTVDB" to "https://thetvdb.com/dereferrer/series/$it") }
    }
}

/**
 * 媒体信息 — everything the server knows about the file that is actually playing.
 *
 * One card per stream rather than one table for the file: a release with a 国语 and a 原声
 * track differs only in the audio, and interleaving both into a single list would make the
 * difference impossible to read. Absent fields are dropped rather than shown as 未知 — the
 * list is already long, and "the server didn't say" is not worth a row of its own.
 */
@Composable
private fun MediaInfoSection(
    version: MediaVersion,
    dateCreated: String?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("媒体信息", Modifier.padding(horizontal = Dimens.pageHorizontal))
        // Two cards fill the width, as in the reference; a third and beyond (a release with
        // several audio tracks) scroll in from the right rather than shrinking the pair.
        BoxWithConstraints {
        val cardWidth = (maxWidth - Dimens.pageHorizontal * 2 - 10.dp) / 2
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            version.video?.let { video ->
                item(key = "video") {
                    SpecCard(
                        icon = AppIcons.Play,
                        title = "视频",
                        width = cardWidth,
                        rows = listOfNotNull(
                            video.displayTitle?.let { "显示标题" to it },
                            video.language?.let { "语言" to it },
                            video.codec?.let { "编码" to it },
                            video.resolutionLabel?.let { "分辨率" to it },
                            video.frameRateLabel?.let { "帧率" to it },
                            video.bitrateBps?.takeIf { it > 0 }
                                ?.let { "比特率" to "${it / 1_000_000} Mbps" },
                            video.videoRange?.let { "动态范围" to it },
                            video.interlaced?.let { "隔行扫描" to if (it) "是" else "否" },
                            video.colorPrimaries?.let { "色彩原色" to it },
                            video.colorSpace?.let { "色彩空间" to it },
                            video.profile?.let { "配置" to it },
                            video.level?.takeIf { it > 0 }?.let { "等级" to it.toInt().toString() },
                            video.aspectRatio?.let { "长宽比" to it },
                            video.bitDepth?.takeIf { it > 0 }?.let { "位深" to it.toString() },
                        ),
                    )
                }
            }
            itemsIndexed(version.audioTracks) { index, audio ->
                SpecCard(
                    icon = AppIcons.Volume,
                    title = if (version.audioTracks.size > 1) "音频 ${index + 1}" else "音频",
                    width = cardWidth,
                    rows = listOfNotNull(
                        audio.displayTitle?.let { "标题" to it },
                        audio.language?.let { "语言" to it },
                        audio.codec?.uppercase()?.let { "编码" to it },
                        audio.profile?.let { "配置" to it },
                        audio.bitrateLabel?.let { "比特率" to it },
                        audio.channels?.let { "布局" to it },
                        audio.channelCount?.takeIf { it > 0 }?.let { "声道" to it.toString() },
                        audio.sampleRateLabel?.let { "采样率" to it },
                        audio.external?.let { "外部" to if (it) "是" else "否" },
                        audio.default?.let { "默认" to if (it) "是" else "否" },
                        audio.displayLanguage?.let { "显示语言" to it },
                    ),
                )
            }
        }
        }
        val footer = listOfNotNull(
            version.container?.uppercase(),
            version.sizeLabel,
            dateCreated,
        )
        if (version.path != null || footer.isNotEmpty()) {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .padding(horizontal = Dimens.pageHorizontal)
                    .fillMaxWidth()
                    .solidGlass(
                        shape = GlassShapes.card,
                        fill = if (palette.isDark) {
                            Color.White.copy(alpha = 0.05f)
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                        border = palette.border,
                    )
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                version.path?.let { path ->
                    Text(path, style = mr(10f, 400), color = palette.sub2)
                }
                if (footer.isNotEmpty()) {
                    Text(
                        footer.joinToString(" · "),
                        style = mr(10f, 500),
                        color = palette.sub2,
                    )
                }
            }
        }
    }
}

/** One stream's specification, as a fixed-width card of label/value rows. */
@Composable
private fun SpecCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    width: Dp,
    rows: List<Pair<String, String>>,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .width(width)
            .solidGlass(
                shape = GlassShapes.card,
                fill = if (palette.isDark) {
                    Color.White.copy(alpha = 0.06f)
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                border = palette.border,
            )
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = palette.sub, modifier = Modifier.size(13.dp))
            Text(title, style = sc(12.5f, 700), color = palette.text)
        }
        Spacer(Modifier.height(10.dp))
        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(label, style = mr(10.5f, 400), color = palette.sub2)
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    style = mr(10.5f, 500),
                    color = palette.body,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

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
 * 版本 — which of the server's several files for this title plays.
 *
 * Only a picker now. It used to carry a 规格 summary as well, which 媒体信息 states in far
 * more detail a couple of sections further down; two accounts of the same file, one of them
 * partial, is worse than one.
 */
@Composable
private fun VersionSection(
    versions: List<MediaVersion>,
    selectedId: String?,
    accent: Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    // Nothing to choose between, nothing to show: 媒体信息 now spells the file out in full,
    // so a 规格 summary here would state the same facts twice, less completely.
    if (versions.size <= 1) return
    val selected = versions.firstOrNull { it.id == selectedId } ?: versions.first()
    Column(modifier) {
        SectionHeader(
            title = "版本",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        ) {
            Text("${versions.size} 个版本", style = mr(10.5f, 500), color = palette.sub2)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(versions, key = { it.id }) { version ->
                VersionCard(
                    version = version,
                    selected = version.id == selected.id,
                    accent = accent,
                    onSelect = { onSelect(version.id) },
                )
            }
        }
    }
}

/**
 * 音轨 / 字幕 — which track the player should open with.
 *
 * The player has had these pickers all along; what it has not had is a way to answer the
 * question *before* the film starts. A release with a 国语 and an 原声 track opens on
 * whichever the file marks default, and finding out it was the wrong one means hearing it,
 * pausing, and going two panels deep while the room waits.
 *
 * Selection travels as a language rather than a stream number — see [PlaybackTrackRequest].
 * 默认 is a real choice and always present: it is the only one that says "I have no opinion",
 * and without it a picker that has been touched can never be untouched.
 */
@Composable
private fun TrackSection(
    version: MediaVersion,
    audioLanguage: String?,
    subtitleLanguage: String?,
    accent: Color,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (version.audioTracks.size > 1) {
            Column {
                SectionHeader("音轨", Modifier.padding(horizontal = Dimens.pageHorizontal))
                TrackChipRow(
                    options = buildList {
                        add(TrackChoice(null, "默认"))
                        // A track the server tagged with no language is unreachable —
                        // language is the only handle the player has on it — so it is not
                        // offered rather than offered and silently ignored.
                        version.audioTracks.forEach { track ->
                            track.language?.let { add(TrackChoice(it, track.label)) }
                        }
                    },
                    selected = audioLanguage,
                    accent = accent,
                    onSelect = onSelectAudio,
                )
            }
        }
        if (version.subtitleTracks.isNotEmpty()) {
            Column {
                SectionHeader("字幕", Modifier.padding(horizontal = Dimens.pageHorizontal))
                TrackChipRow(
                    options = buildList {
                        add(TrackChoice(null, "默认"))
                        add(TrackChoice(PlaybackTrackRequest.SUBTITLES_OFF, "关闭"))
                        version.subtitleTracks.forEach { track ->
                            track.language?.let { add(TrackChoice(it, track.label)) }
                        }
                    },
                    selected = subtitleLanguage,
                    accent = accent,
                    onSelect = onSelectSubtitle,
                )
            }
        }
    }
}

/** One selectable track, as the value that travels and the words on the chip. */
private data class TrackChoice(val value: String?, val label: String)

@Composable
private fun TrackChipRow(
    options: List<TrackChoice>,
    selected: String?,
    accent: Color,
    onSelect: (String?) -> Unit,
) {
    val palette = LocalPalette.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Positional keys on purpose: a file can carry two tracks the server tags with the
        // same language, so the value is not unique and cannot be one.
        items(options) { option ->
            val active = option.value == selected
            Text(
                option.label,
                style = sc(11.5f, if (active) 700 else 500),
                color = if (active) accent else palette.body,
                maxLines = 1,
                modifier = Modifier
                    .glass(
                        shape = GlassShapes.chip,
                        fill = if (active) accent.copy(alpha = 0.10f) else palette.card2,
                        border = if (active) accent.copy(alpha = 0.42f) else palette.border,
                    )
                    .pressable(onClick = { onSelect(option.value) })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun VersionCard(
    version: MediaVersion,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .width(150.dp)
            .glass(
                shape = GlassShapes.card,
                fill = if (selected) accent.copy(alpha = 0.10f) else palette.card2,
                border = if (selected) accent.copy(alpha = 0.42f) else palette.border,
            )
            .pressable(enabled = !selected, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                version.name,
                style = sc(12f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "当前版本",
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            version.qualityLabel,
            style = mr(10.5f, 500),
            color = accent,
            maxLines = 1,
        )
        Text(
            listOfNotNull(version.sizeLabel, version.bitrateLabel).joinToString(" · "),
            style = mr(10f, 400),
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceSection(
    sources: List<ServerSource>,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val availableSources = remember(sources) {
        sources.filter { it.reachable && it.source != null && it.itemId != null }
    }
    Column(modifier) {
        SectionHeader(
            title = "资源",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        ) {
            Row(
                Modifier.pressable(onClick = onSeeAll).padding(start = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${availableSources.size} 个媒体库",
                    style = mr(10.5f, 500),
                    color = palette.sub2,
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = "查看全部资源",
                    tint = palette.hint,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        // The biggest file is called out, because that is the question the row exists to
        // answer: given the same title on two servers, which copy is the better one.
        val bestServerId = remember(availableSources) {
            availableSources
                .filter { it.source?.sizeBytes != null }
                .maxByOrNull { it.source?.sizeBytes ?: 0L }
                ?.takeIf { availableSources.size > 1 }
                ?.serverId
        }
        BoxWithConstraints {
            val cardWidth = (maxWidth - Dimens.pageHorizontal * 2 - 10.dp) / 2
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    availableSources,
                    key = { index, entry -> "source-${entry.serverId}-${entry.itemId}-$index" },
                ) { _, entry ->
                    SourceCard(
                        entry = entry,
                        accent = accent,
                        best = entry.serverId == bestServerId,
                        width = cardWidth,
                        onSelect = { entry.itemId?.let { onSelect(entry.serverId, it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    entry: ServerSource,
    accent: Color,
    best: Boolean,
    width: Dp,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    val selected = entry.isCurrent
    val source = entry.source
    // 1.5dp on the selected ring, so switching sources moves the edge as well as the
    // colour — `solidGlass` is fixed at `Dimens.hairline`, hence the explicit border.
    val edge = when {
        selected -> accent
        palette.isDark -> Color.White.copy(alpha = 0.16f)
        else -> Color(0xFF141A26).copy(alpha = 0.10f)
    }
    Column(
        Modifier
            .width(width)
            .clip(GlassShapes.card)
            .background(
                if (palette.isDark) Color.White.copy(alpha = 0.06f) else Color.White,
            )
            .border(if (selected) 1.5.dp else Dimens.hairline, edge, GlassShapes.card)
            .pressable(onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(serverTint(entry.serverId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.serverName.take(1).uppercase(),
                    style = mr(10f, 700),
                    color = Color.White,
                )
            }
            Text(
                entry.serverName,
                style = sc(11.5f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (best) {
                Text(
                    "Best",
                    style = mr(9f, 700),
                    color = Color(0xFF9A6B12),
                    modifier = Modifier
                        .clip(GlassShapes.chip)
                        .background(Color(0xFFF5C86A).copy(alpha = 0.30f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountChip(AppIcons.Volume, source?.audioTrackCount ?: 0)
            CountChip(AppIcons.Subtitle, source?.subtitleTrackCount ?: 0)
            Spacer(Modifier.weight(1f))
            // The mark rather than the words: at this size "Dolby Vision" would take the
            // width of the rest of the row, and the mark is what the eye is scanning for.
            if (source?.dolbyVision == true) {
                DolbyChip("VISION", if (selected) accent else palette.sub)
            }
            source?.quality?.takeIf { it.isNotBlank() && source.dolbyVision != true }?.let { quality ->
                Text(
                    quality,
                    style = mr(9f, 700),
                    color = if (selected) accent else palette.sub,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(GlassShapes.chip)
                        .background(
                            if (selected) {
                                accent.copy(alpha = 0.12f)
                            } else {
                                Color(0xFF141A26).copy(alpha = 0.05f)
                            },
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                source?.size ?: "—",
                style = mr(10f, 600),
                color = palette.body,
                maxLines = 1,
            )
            Text(
                source?.bitrate ?: "—",
                style = mr(10f, 600),
                color = palette.body,
                maxLines = 1,
            )
        }
    }
}

/** `♪ 2` — a stream count small enough to sit three-to-a-row on a half-width card. */
@Composable
private fun CountChip(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    val palette = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.sub2, modifier = Modifier.size(11.dp))
        Text(count.toString(), style = mr(9.5f, 600), color = palette.sub2)
    }
}

/**
 * A stable colour per server, so the same library keeps the same tile wherever it appears.
 * Derived from the id rather than stored: servers are added and removed, and a palette
 * index would drift every time the list changed.
 */
internal fun serverTint(serverId: String): Color {
    val palette = listOf(
        Color(0xFF4C7DF0), Color(0xFF41A98A), Color(0xFFD1705C),
        Color(0xFF8B6FD1), Color(0xFFD19A3F), Color(0xFF3FA3C4),
    )
    val index = (serverId.hashCode().toLong() and 0xFFFFFFFFL) % palette.size
    return palette[index.toInt()]
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
    onSeeAll: () -> Unit,
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
                // 共 N 集 is the label and the way in: a rail shows four of them, and the
                // count is exactly the promise the full list keeps.
                Row(
                    Modifier.pressable(onClick = onSeeAll),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("共 $episodeCount 集", style = mr(10.5f, 500), color = palette.sub2)
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "查看全部剧集",
                        tint = palette.hint,
                        modifier = Modifier.size(11.dp),
                    )
                }
                if (seasons.size > 1) {
                    Row(
                        Modifier
                            .pressable(onClick = onTogglePicker)
                            .shadow(GlassLift.control, GlassShapes.thumb)
                            .liquidGlass(
                                shape = GlassShapes.thumb,
                                fill = accent.copy(alpha = 0.13f),
                                border = accent.copy(alpha = 0.28f),
                                sheen = 0.7f,
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
                            // No lift: these read as one picker's options, and a shadow under
                            // each would break the row into a scatter of separate keys.
                            .liquidGlass(
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
                                sheen = 0.7f,
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
    accessToken: String,
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
    onSeeAll: () -> Unit,
) {
    Column(Modifier.padding(top = Dimens.sectionGap)) {
        EpisodeHeader(
            accent = accent,
            onSeeAll = onSeeAll,
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
                EpisodeCard(baseUrl, accessToken, episode, accent) { onPlayEpisode(episode) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    baseUrl: String,
    accessToken: String,
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
            url = EmbyImages.primary(
                baseUrl,
                episode.id,
                episode.primaryTag,
                maxHeight = 240,
                accessToken = accessToken,
            ),
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
private fun CastRow(
    baseUrl: String,
    accessToken: String,
    people: List<Person>,
    modifier: Modifier = Modifier,
) {
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
                        url = EmbyImages.avatar(baseUrl, person, accessToken = accessToken),
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
