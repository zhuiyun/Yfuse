package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkAccent
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyBadge
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.isSharedMediaArtworkActive
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.OfflineDownloadQuality
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.estimateOfflineBytes
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.player.PlaybackSelection
import com.yfuse.feature.player.PlaybackSelectionState
import com.yfuse.feature.profile.formatDownloadBytes
import com.yfuse.feature.watch.WatchInviteShareSheet

/** Height of the collapsing top bar's content row, above the status bar inset. */
private val TopBarHeight = 52.dp

/** The sheet's own rhythm — above the title block, and between it and everything after. */
private val SheetGap = 18.dp

/** The hero overlap is measured through the premium primary play key. */
private val DetailPlayButtonHeight = 52.dp

/** Keep the hero artwork visible for 20dp below the primary play button. */
private val PlayButtonHeroOverlap = SheetGap + DetailPlayButtonHeight + 20.dp

/**
 * A one-line title with year, rating and genre under it. Only the seed for the measured
 * lift, so being a little out costs one frame of settling and nothing else.
 */
private val TypicalCaptionHeight = 116.dp

/**
 * A player selection is consumed only after this detail screen can resolve it.
 *
 * The player may publish its selection before either the initial playable episode or the
 * cross-server comparison has arrived. Returning false leaves the one-shot pending so the
 * surrounding [LaunchedEffect] can try again when those asynchronous inputs change.
 */
internal fun shouldApplyPlaybackSelection(
    selection: PlaybackSelectionState,
    appliedSelection: PlaybackSelectionState?,
    detailReady: Boolean,
    playServerId: String?,
    currentRootItemId: String?,
    playTargetReady: Boolean,
    sources: List<ServerSource>,
): Boolean {
    if (!detailReady || selection == appliedSelection) return false
    val serverId = selection.serverId ?: return false
    val selectionItemId = selection.itemId ?: return false
    val selectionRootItemId = selection.seriesId ?: selectionItemId
    return if (playServerId == serverId) {
        playTargetReady && currentRootItemId == selectionRootItemId
    } else {
        sources.any { source ->
            source.serverId == serverId &&
                (source.itemId == selectionRootItemId || source.itemId == selectionItemId) &&
                source.reachable &&
                source.source != null
        }
    }
}

/** The selected visual target puts the glass summary over the lower third of the hero. */
@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val playbackSelection by PlaybackSelection.state.collectAsState()
    val palette = LocalPalette.current
    val detail = state.detail
    val baseUrl = state.server?.baseUrl.orEmpty()
    // Emby answers 401 for artwork without it when the server requires authentication, so
    // every image on this page — hero, 艺术图, 剧集, 主演, 相关推荐 — is built with it.
    val accessToken = state.server?.accessToken.orEmpty()
    val playBaseUrl = state.playServer?.baseUrl ?: baseUrl
    val playAccessToken = state.playServer?.accessToken ?: accessToken
    // Episode details carry the episode title in `title` and the show's name separately.
    // The artwork is the show's visual identity, so its caption and collapsed bar use the
    // show name; episode coordinates remain on the play action below.
    val displayTitle =
        detail
            ?.let { item ->
                if (item.type == "Episode") {
                    item.seriesName?.takeIf { it.isNotBlank() } ?: item.title
                } else {
                    item.title
                }
            }.orEmpty()

    // The backdrop is the hero, the poster is what stands in when the item has none.
    val heroUrls =
        detail
            ?.let {
                listOf(
                    EmbyImages.backdrop(baseUrl, it, accessToken = accessToken),
                    EmbyImages.poster(baseUrl, it, accessToken = accessToken),
                )
            }.orEmpty()
    // The backdrop can fail while the poster succeeds. Wait for FallbackImage to report the
    // candidate that is actually on screen so Palette never tints one picture from another.
    // Include the server because different libraries may legitimately reuse the same item id.
    val heroIdentity = remember(baseUrl, detail?.id) { baseUrl to detail?.id }
    val sharedHeroKey =
        detail?.let {
            MediaSharedElementKey(
                serverId = state.server?.id ?: component.serverId,
                itemId = it.id,
            )
        }
    var resolvedHeroUrl by remember(heroIdentity) { mutableStateOf<String?>(null) }
    // The poster is what the page takes its colour from, and the backdrop is only the
    // stand-in. A backdrop is a frame of the film — a night exterior, a white-sky wide —
    // chosen for what it shows rather than for what the title *is*; the poster is the
    // artwork somebody graded to say that. Falling back to whatever the hero actually
    // resolved keeps items with no poster tinted from a picture that is on screen rather
    // than from one that failed to load.
    val posterUrl = detail?.let { EmbyImages.poster(baseUrl, it, accessToken = accessToken) }
    // Artwork is allowed to set the mood, not to redefine the product. Harmonize the final
    // target before animation; doing the thresholded correction on every frame caused jumps.
    val detailAccent =
        rememberAnimatedArtworkAccent(
            url = posterUrl ?: resolvedHeroUrl,
            fallback = Brand.Primary, // design-system: brand-identity
            darkTheme = palette.isDark,
            identity = heroIdentity,
        )

    var seasonPickerOpen by remember { mutableStateOf(false) }
    var overviewExpanded by remember { mutableStateOf(false) }
    // Hoisted out of the list: the hero badges what this copy is, and 媒体信息 at the foot
    // of the page spells the same file out — one answer to "which file", read twice.
    val serverVersions = state.playTarget?.versions.orEmpty()
    val playableVersions = remember(serverVersions) { serverVersions.bestVersionsFirst() }
    // Resolved against the server's own order, not the sorted one: the fallback is "whatever
    // the server lists first", which is also what an unqualified stream request returns.
    val selectedVersion =
        serverVersions.firstOrNull { it.id == state.selectedVersionId }
            ?: serverVersions.firstOrNull()
    // 资源 has to describe the file that will play, not the server's default — see `describing`.
    val comparableSources =
        remember(
            state.sources,
            selectedVersion,
            state.selectedSourceServerId,
            state.selectedSourceItemId,
        ) {
            // Restate first, then rank the facts the cards actually display. Sorting the old
            // default and rewriting it afterwards could leave a selected 720p copy wearing Best
            // while a visible 1080p copy sat behind it.
            state.sources
                .describing(
                    version = selectedVersion,
                    selectedServerId = state.selectedSourceServerId,
                    selectedItemId = state.selectedSourceItemId,
                ).let { sources ->
                    if (component.dependencies.playbackPreferences.smartCrossServerSource.value) {
                        rankServerSources(
                            sources = sources,
                            health = component.dependencies.serverHealthMonitor.health.value,
                            network = currentPlaybackNetworkClass(),
                        ).map { it.source }
                    } else {
                        sources.bestSourcesFirst()
                    }
                }
        }
    // Which library, and which episode. It used to append the version name, its quality
    // label and the resume timestamp as well, which on a long server name ran past the
    // button and ellipsized the part that identifies the episode. The version is stated by
    // 版本 and 媒体信息, and the resume point by the progress bar under the button.
    val playDetailLine =
        remember(state.playServer, state.playTarget) {
            val target = state.playTarget
            val coordinate =
                listOfNotNull(
                    target?.seasonNumber?.let { "S$it" },
                    target?.episodeNumber?.let { "E$it" },
                ).joinToString(" ").takeIf { it.isNotBlank() }
            listOfNotNull(
                state.playServer?.serverName,
                coordinate,
            ).joinToString(" · ").takeIf { it.isNotBlank() }
        }

    val watchTogether = component.dependencies.watchTogether
    val watchPreferences = component.dependencies.watchTogetherPreferences
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchPreferences.endpoint.collectAsState()
    val share = rememberShareHandler()
    var shareSheetOpen by remember { mutableStateOf(false) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var downloadSheetOpen by remember { mutableStateOf(false) }
    var organizationSheetOpen by remember { mutableStateOf(false) }
    var sourceListOpen by remember { mutableStateOf(false) }
    var allEpisodesOpen by remember { mutableStateOf(false) }

    // Mirroring the player's selection is a one-shot per *new* selection, not a standing
    // rule. The value already present when this page is created belongs to an older player
    // session and must not override the server from the navigation request. Keeping it as
    // the initial applied value still lets the player update this existing page while it is
    // covered, including episode/version changes made before the user returns.
    //
    // `state.playTarget?.versions` used to be a key, and selecting an episode is precisely
    // what changes it — so every manual switch re-ran this effect, which pushed the episode
    // the *player* had last played straight back over the one just tapped. Returning from
    // the player therefore froze the episode list on that episode: the selection was applied,
    // reverted, and applied again on every attempt.
    var appliedSelection by remember { mutableStateOf<PlaybackSelectionState?>(playbackSelection) }
    LaunchedEffect(
        playbackSelection,
        state.detail,
        state.sources,
        state.episodes,
        state.playServer,
        state.playTarget,
    ) {
        // Do not consume the one-shot until the store has enough data to act on it. The old
        // code marked it applied as soon as the title detail arrived; if cross-server sources
        // or the playable episode were still loading, SyncPlaybackSelection did nothing and
        // every later re-run skipped the selection forever.
        if (
            !shouldApplyPlaybackSelection(
                selection = playbackSelection,
                appliedSelection = appliedSelection,
                detailReady = state.detail != null,
                playServerId = state.playServer?.id,
                currentRootItemId =
                    state.playSourceDetail?.let { source ->
                        if (source.type == "Episode") source.seriesId ?: source.id else source.id
                    },
                playTargetReady = state.playTarget != null,
                sources = state.sources,
            )
        ) {
            return@LaunchedEffect
        }
        val syncedServerId = playbackSelection.serverId ?: return@LaunchedEffect
        val syncedItemId = playbackSelection.itemId ?: return@LaunchedEffect
        component.store.accept(
            DetailIntent.SyncPlaybackSelection(
                serverId = syncedServerId,
                itemId = syncedItemId,
                versionId = playbackSelection.versionId,
            ),
        )
        appliedSelection = playbackSelection
    }

    // 影视详情页 has always coloured its own controls from the poster; under 跟随封面 that
    // becomes the page's whole accent, so chips, switches and sheets follow it too.
    ArtworkAccent(detailAccent) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            // Enough artwork to feel cinematic while still exposing the title and primary
            // decision on compact phones. The old 60% crop hid too much of the useful page.
            val heroHeight =
                when (windowWidthTier(maxWidth)) {
                    WindowWidthTier.Compact -> (maxHeight * 0.55f).coerceIn(360.dp, 520.dp)
                    WindowWidthTier.Medium -> (maxHeight * 0.52f).coerceIn(420.dp, 600.dp)
                    WindowWidthTier.Expanded -> (maxHeight * 0.50f).coerceIn(460.dp, 640.dp)
                }
            val heroHeightPx = with(density) { heroHeight.toPx() }

            // Lift the measured caption and the primary action over the artwork. The backdrop
            // continues 20dp below 播放, while the blend into the page still begins at the
            // artwork's physical lower edge.
            var captionLift by remember {
                mutableStateOf(TypicalCaptionHeight + SheetGap + PlayButtonHeroOverlap)
            }

            val detailSurface =
                remember(detailAccent, palette.isDark) {
                    heroSurface(detailAccent, palette.isDark)
                }
            val ambientBrush =
                remember(
                    detailAccent,
                    detailSurface,
                    heroHeightPx,
                    palette.isDark,
                ) {
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                detailAccent.copy(alpha = if (palette.isDark) 0.18f else 0.10f),
                                detailSurface.copy(alpha = 0f),
                            ),
                        startY = 0f,
                        // The hero scrim reaches pure [detailSurface] at this exact edge. Ambient
                        // colour must also be fully transparent here or the two sides cannot match.
                        endY = heroHeightPx,
                    )
                }
            // Blend band between the artwork and the page. It starts where the artwork ends,
            // leaving the title block on clean artwork — see [heroPanelBrush].
            val panelBrush =
                remember(detailSurface, density, captionLift) {
                    heroPanelBrush(detailSurface, density, start = captionLift)
                }

            // A different detail route must always start at its hero. Keying the state by the
            // route item also prevents a newly opened title inheriting the previous title's offset.
            val listState = remember(component.itemId) { LazyListState() }
            val detailBackdrop = rememberBackdropState()
            val (overscrollPull, overscrollConnection) =
                rememberOverscrollPull(
                    LocalAccessibilityOptions.current.reduceMotion,
                )
            val heroScroll = rememberHeroScroll(listState, heroHeightPx, overscrollPull)
            val topBarProgress = rememberTopBarProgress(listState, heroHeightPx, density)
            val barSolid by remember(topBarProgress) { derivedStateOf { topBarProgress.value > 0.5f } }

            StatusBarIconStyle(darkIcons = !palette.isDark && (detail == null || barSolid))

            Box(
                Modifier
                    .fillMaxSize()
                    .background(detailSurface)
                    .background(ambientBrush),
            )

            when {
                detail == null && state.error == null -> DetailSkeleton(heroHeight)

                detail == null ->
                    ErrorState(
                        message = state.error ?: "加载失败",
                        onRetry = { component.store.accept(DetailIntent.Retry) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(overscrollConnection)
                            // What the collapsed top bar blurs. The bar is a sibling drawn after
                            // this, which is what keeps it out of its own backdrop.
                            .backdropSource(detailBackdrop),
                        state = listState,
                        contentPadding = PaddingValues(bottom = Dimens.contentBottom),
                    ) {
                        item(key = "hero") {
                            Hero(
                                urls = heroUrls,
                                title = displayTitle,
                                height = heroHeight,
                                surfaceColor = detailSurface,
                                animationKey = "detail-hero-${detail.id}",
                                sharedKey = sharedHeroKey,
                                scroll = heroScroll,
                                onResolvedUrl = { resolvedHeroUrl = it },
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
                                    title = displayTitle,
                                    accent = detailAccent,
                                    // A series has no file of its own, so its 杜比 facts belong to
                                    // the episode 继续观看 would open — which is the copy the badge
                                    // would be describing anyway.
                                    version = selectedVersion ?: state.playTarget?.versions?.firstOrNull(),
                                    modifier =
                                        Modifier.onSizeChanged {
                                            captionLift = with(density) { it.height.toDp() } +
                                                SheetGap + PlayButtonHeroOverlap
                                        },
                                )
                                DetailActionDock(
                                    accent = detailAccent,
                                    label = if (state.playPositionTicks > 0L) "继续观看" else "播放",
                                    detailLine = playDetailLine,
                                    resolving = state.resolvingPlay || state.selectionLoading,
                                    favorite = detail.isFavorite,
                                    canPlayFromStart = state.playPositionTicks > 0L,
                                    onPlay = { component.store.accept(DetailIntent.Play) },
                                    onPlayFromStart = {
                                        component.store.accept(DetailIntent.PlayFromStart)
                                    },
                                    onFavorite = {
                                        component.store.accept(DetailIntent.ToggleFavorite)
                                    },
                                    onWatchLater = {
                                        component.store.accept(DetailIntent.AddToWatchLater)
                                    },
                                )
                            }
                        }

                        val overview = detail.overview
                        if (!overview.isNullOrBlank()) {
                            item(key = "overview") {
                                OverviewSection(
                                    text = overview,
                                    expanded = overviewExpanded,
                                    onToggle = { overviewExpanded = !overviewExpanded },
                                    accent = detailAccent,
                                    modifier = Modifier.sectionPadding(),
                                )
                            }
                        }

                        // Episodes are the next decision after reading the synopsis. Keeping the
                        // rail here avoids making a series viewer cross file metadata, artwork and
                        // external links before they can choose what to watch.
                        if (state.episodes.isNotEmpty()) {
                            item(key = "episodes") {
                                EpisodeSection(
                                    baseUrl = playBaseUrl,
                                    accessToken = playAccessToken,
                                    episodes = state.episodes,
                                    seriesPosterUrl = heroUrls.getOrNull(1),
                                    selectedEpisodeId = state.selectedEpisodeId,
                                    accent = detailAccent,
                                    seasonLabel =
                                        state.seasons
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
                                            DetailIntent.SelectEpisode(
                                                episode.id,
                                                episode.resumePositionTicks ?: 0L,
                                            ),
                                        )
                                    },
                                    onSeeAll = { allEpisodesOpen = true },
                                )
                            }
                        }

                        if (playableVersions.isNotEmpty()) {
                            item(key = "versions") {
                                VersionSection(
                                    versions = playableVersions,
                                    selectedId = state.selectedVersionId,
                                    accent = detailAccent,
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
                        val playableVersion = selectedVersion
                        if (playableVersion != null &&
                            (
                                playableVersion.audioTracks.size > 1 ||
                                    playableVersion.subtitleTracks.isNotEmpty()
                            )
                        ) {
                            item(key = "tracks") {
                                TrackSection(
                                    version = playableVersion,
                                    audioLanguage = state.preferredAudioLanguage,
                                    subtitleLanguage = state.preferredSubtitleLanguage,
                                    accent = detailAccent,
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

                        if (comparableSources.any { it.reachable && it.source != null && it.itemId != null }) {
                            item(key = "sources") {
                                SourceSection(
                                    sources = comparableSources,
                                    selectedServerId = state.selectedSourceServerId,
                                    selectedItemId = state.selectedSourceItemId,
                                    accent = detailAccent,
                                    onSelect = { serverId, itemId ->
                                        component.store.accept(DetailIntent.SelectSource(serverId, itemId))
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
                                    serverId = state.server?.id,
                                    items = state.related,
                                    accent = detailAccent,
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
                                    dateCreated = state.playTarget?.dateCreated,
                                    modifier = Modifier.padding(top = Dimens.sectionGap),
                                )
                            }
                        }
                    }
            }

            DetailTopBar(
                title = displayTitle,
                backdrop = detailBackdrop,
                progress = topBarProgress,
                surfaceColor = detailSurface,
                accent = detailAccent,
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
                GlassDialog(liquidButtons = false, onDismiss = { moreSheetOpen = false }) {
                    OverlayHeader(
                        title = detail.title,
                        subtitle = "更多操作",
                        onClose = { moreSheetOpen = false },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                        OverlayOptionRow(
                            label = "下载到本地",
                            selected = false,
                            onClick = {
                                moreSheetOpen = false
                                downloadSheetOpen = true
                            },
                        )
                        OverlayOptionRow(
                            label = if (detail.played) "标记未看" else "标记已看",
                            selected = detail.played,
                            onClick = {
                                moreSheetOpen = false
                                component.store.accept(DetailIntent.TogglePlayed)
                            },
                        )
                        OverlayOptionRow(
                            label = "加入合集或播放列表",
                            selected = false,
                            onClick = {
                                moreSheetOpen = false
                                organizationSheetOpen = true
                                component.store.accept(DetailIntent.LoadOrganizationContainers)
                            },
                        )
                        // 一起看 belongs where the decision is made — at the point of choosing
                        // what to watch, not in the settings of a player you must already have
                        // open.
                        //
                        // Playback is *not* started here. It used to be, in the same tap, and the
                        // player activity that came up covered the invite sheet this opens — the
                        // host reached the film without ever being shown the link they created it
                        // for. The sheet starts playback itself, once the invite has been sent.
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
            }

            val downloadTarget = state.playTarget
            if (downloadSheetOpen && downloadTarget != null) {
                OfflineDownloadDialog(
                    detail = downloadTarget,
                    episodes = state.episodes,
                    selectedVersionId = state.selectedVersionId,
                    onConfirm = { selection ->
                        downloadSheetOpen = false
                        component.download(selection)
                    },
                    onDismiss = { downloadSheetOpen = false },
                )
            }

            if (organizationSheetOpen && detail != null) {
                OrganizationContainerDialog(
                    containers = state.organizationContainers,
                    loading = state.organizationLoading,
                    error = state.organizationError,
                    addingIds = state.addingContainerIds,
                    addedIds = state.addedContainerIds,
                    onRetry = {
                        component.store.accept(DetailIntent.LoadOrganizationContainers)
                    },
                    onAdd = {
                        component.store.accept(DetailIntent.AddToOrganizationContainer(it))
                    },
                    onDismiss = { organizationSheetOpen = false },
                )
            }

            if (sourceListOpen) {
                SourceListDialog(
                    sources = comparableSources,
                    selectedServerId = state.selectedSourceServerId,
                    selectedItemId = state.selectedSourceItemId,
                    accent = detailAccent,
                    onSelect = { serverId, itemId ->
                        val willPlay =
                            state.selectedSourceServerId == serverId &&
                                state.selectedSourceItemId == itemId
                        if (willPlay) sourceListOpen = false
                        component.store.accept(DetailIntent.SelectSource(serverId, itemId))
                    },
                    onDismiss = { sourceListOpen = false },
                )
            }

            // A layer rather than a route: it covers the page that owns this season and its
            // artwork, and the detail store has already loaded the episodes it lists.
            if (allEpisodesOpen && detail != null) {
                SeasonEpisodesPage(
                    seasonLabel =
                        state.seasons
                            .firstOrNull { it.id == state.selectedSeasonId }
                            ?.name
                            ?: "剧集",
                    seriesName = detail.seriesName?.ifBlank { null } ?: detail.title,
                    episodes = state.episodes,
                    heroUrls = heroUrls,
                    baseUrl = playBaseUrl,
                    accessToken = playAccessToken,
                    seriesPosterUrl = heroUrls.getOrNull(1),
                    accent = detailAccent,
                    currentEpisodeId = state.selectedEpisodeId,
                    onPlayEpisode = { episode ->
                        if (state.selectedEpisodeId == episode.id) allEpisodesOpen = false
                        component.store.accept(
                            DetailIntent.SelectEpisode(
                                episode.id,
                                episode.resumePositionTicks ?: 0L,
                            ),
                        )
                    },
                    onDismiss = { allEpisodesOpen = false },
                )
            }

            // Opened as soon as the room is asked for, not once it exists: the relay can be slow
            // or down, and the tap used to have no visible result at all in either case.
            if (shareSheetOpen) {
                val invite =
                    WatchInvite(
                        roomCode = watchState.roomCode.orEmpty(),
                        mediaKey = detail?.let { it.providerIds.watchKey(it.id) },
                        title = detail?.title,
                        // Only travel the endpoint when it isn't the built-in default, so the common
                        // case produces a short link and no "unfamiliar relay" warning on the far end.
                        endpoint =
                            watchEndpoint.takeIf {
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

            // Over the page rather than inside it: as a row in the action column this
            // pushed 简介 and everything under it down the moment a tap was confirmed,
            // and it stayed there until some other action happened to replace it.
            ActionToast(
                message = state.actionMessage ?: state.sourceFailure?.toDetailMessage(),
                onDismiss = { component.store.accept(DetailIntent.DismissMessage) },
                accent = detailAccent,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun OfflineDownloadDialog(
    detail: MediaDetail,
    episodes: List<Episode>,
    selectedVersionId: String?,
    onConfirm: (OfflineDownloadSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val versions = detail.versions
    var versionId by remember(detail.id, selectedVersionId) {
        mutableStateOf(selectedVersionId ?: versions.firstOrNull()?.id)
    }
    var quality by remember(detail.id) { mutableStateOf(OfflineDownloadQuality.Original) }
    var subtitleIndex by remember(detail.id) { mutableStateOf<Int?>(null) }
    var batchMode by remember(detail.id) { mutableStateOf(OfflineBatchMode.Current) }
    val selectedVersion = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()
    val selectedSubtitle = selectedVersion?.subtitleTracks?.firstOrNull { it.index == subtitleIndex }
    val batchCount =
        when (batchMode) {
            OfflineBatchMode.Current -> 1
            OfflineBatchMode.Season -> episodes.size.coerceAtLeast(1)
            OfflineBatchMode.Unwatched -> episodes.count { !it.played }
        }
    val estimatePerItem =
        estimateOfflineBytes(
            sourceSizeBytes = selectedVersion?.sizeBytes,
            sourceBitrateBps = selectedVersion?.bitrateBps,
            runtimeMinutes = detail.runtimeMinutes,
            quality = quality,
            includeSubtitle = selectedSubtitle != null,
        )
    val totalEstimate =
        estimatePerItem?.let { bytes ->
            if (batchCount > 0 && bytes > Long.MAX_VALUE / batchCount) Long.MAX_VALUE else bytes * batchCount
        }

    GlassDialog(liquidButtons = false, onDismiss = onDismiss) {
        OverlayHeader(
            title = "智能离线",
            subtitle =
                buildString {
                    append("下载前确认版本、画质和字幕")
                    append(" · ")
                    append(totalEstimate?.let { "预计 ${formatDownloadBytes(it)}" } ?: "空间待服务器确认")
                },
            onClose = onDismiss,
        )

        Text("范围", style = AppTypography.caption.strong, color = palette.sub2)
        OfflineChoiceRow(OfflineBatchMode.entries, batchMode, { it.label }) { batchMode = it }

        if (versions.size > 1) {
            Text("版本", style = AppTypography.caption.strong, color = palette.sub2)
            versions.forEach { version ->
                OverlayOptionRow(
                    label =
                        listOfNotNull(version.name, version.summary.takeIf(String::isNotBlank))
                            .joinToString(" · "),
                    selected = version.id == selectedVersion?.id,
                    onClick = {
                        versionId = version.id
                        subtitleIndex = null
                    },
                )
            }
        }

        Text("画质", style = AppTypography.caption.strong, color = palette.sub2)
        OfflineChoiceRow(OfflineDownloadQuality.entries, quality, { it.label }) { quality = it }

        selectedVersion?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
            Text("字幕", style = AppTypography.caption.strong, color = palette.sub2)
            OverlayOptionRow(
                label = "不下载字幕",
                selected = subtitleIndex == null,
                onClick = { subtitleIndex = null },
            )
            tracks.forEach { track ->
                val index = track.index ?: return@forEach
                OverlayOptionRow(
                    label = track.label,
                    selected = index == subtitleIndex,
                    onClick = { subtitleIndex = index },
                )
            }
        }

        Text(
            when (batchMode) {
                OfflineBatchMode.Current -> "将加入 1 个下载任务"
                OfflineBatchMode.Season -> "将加入 $batchCount 集；每集自动选择对应媒体源"
                OfflineBatchMode.Unwatched ->
                    if (batchCount == 0) {
                        "本季已全部看完，没有可下载的未看剧集"
                    } else {
                        "将加入 $batchCount 集，已看剧集会跳过"
                    }
            },
            style = AppTypography.caption.regular,
            color = palette.sub2,
            modifier = Modifier.padding(top = 8.dp),
        )
        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "加入下载",
            onDismiss = onDismiss,
            onConfirm = {
                onConfirm(
                    OfflineDownloadSelection(
                        batchMode = batchMode,
                        mediaSourceId = selectedVersion?.id,
                        quality = quality,
                        subtitleStreamIndex = selectedSubtitle?.index,
                        subtitleCodec = selectedSubtitle?.codec,
                        subtitleLanguage = selectedSubtitle?.language,
                    ),
                )
            },
            confirmEnabled =
                (versions.isEmpty() || selectedVersion != null) &&
                    !(batchMode == OfflineBatchMode.Unwatched && batchCount == 0),
        )
    }
}

@Composable
private fun <T> OfflineChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values) { value ->
            OverlayOptionRow(
                label = label(value),
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.width(118.dp),
            )
        }
    }
}

@Composable
private fun OrganizationContainerDialog(
    containers: List<MediaContainer>,
    loading: Boolean,
    error: String?,
    addingIds: Set<String>,
    addedIds: Set<String>,
    onRetry: () -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(liquidButtons = false, onDismiss = onDismiss, scrollable = false) {
        OverlayHeader(
            title = "加入合集或播放列表",
            subtitle = "使用服务器上已有的容器",
            onClose = onDismiss,
        )
        when {
            loading && containers.isEmpty() ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("正在读取服务器容器…", style = AppTypography.body.regular, color = palette.sub)
                }

            error != null && containers.isEmpty() -> {
                Text(
                    text = error,
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                OverlayOptionRow(label = "重试", selected = false, onClick = onRetry)
            }

            containers.isEmpty() ->
                Text(
                    text = "此服务器没有可用的合集或播放列表。Yfuse 不会偷偷创建替代片单。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )

            else -> {
                if (error != null) {
                    Text(
                        text = error,
                        style = AppTypography.caption.medium,
                        color = palette.sub,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                // The header stays visible while a bounded lazy list handles hundreds of
                // server containers. Rows keep their intrinsic large-text height and the
                // option component supplies the 48dp minimum touch target.
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing),
                ) {
                    items(
                        items = containers,
                        key = { "${it.serverId}-${it.kind}-${it.id}" },
                    ) { container ->
                        val added = container.id in addedIds
                        val adding = container.id in addingIds
                        val kind =
                            if (container.kind == MediaContainerKind.BoxSet) {
                                "合集"
                            } else {
                                "播放列表"
                            }
                        OverlayOptionRow(
                            label =
                                buildString {
                                    append(kind)
                                    append(" · ")
                                    append(container.title)
                                    if (adding) append(" · 正在加入…")
                                },
                            selected = added,
                            onClick = { if (!adding && !added) onAdd(container.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedSection(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    items: List<MediaItem>,
    accent: Color,
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
                val sharedKey = MediaSharedElementKey(serverId, item.id)
                Column(
                    Modifier
                        .width(96.dp)
                        .pressable(
                            onClick = sharedMediaOnClick(sharedKey) { onOpen(item.id) },
                        ),
                ) {
                    Poster(
                        url =
                            EmbyImages.primary(
                                baseUrl = baseUrl,
                                itemId = item.posterItemId,
                                tag = item.posterTag,
                                maxHeight = 480,
                                accessToken = accessToken,
                            ),
                        shape = GlassShapes.poster,
                        sharedTransitionKey = sharedKey,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.communityRating?.let { ((it * 10).toInt() / 10.0).toString() }
                            ?: item.year?.toString().orEmpty(),
                        style = AppTypography.caption.strong,
                        color = accent,
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
private fun rememberHeroScroll(
    listState: LazyListState,
    heroHeightPx: Float,
    /**
     * How far past the top the list is being dragged, negative-going.
     *
     * `firstVisibleItemScrollOffset` bottoms out at 0 and knows nothing about over-scroll, so
     * on its own it can never describe a pull downward — which is why the hero had no
     * rubber band to ignore in the first place. The over-drag comes from the nested-scroll
     * connection instead; see [rememberOverscrollPull].
     */
    pull: State<Float>,
): State<Float> =
    remember(listState, heroHeightPx, pull) {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> heroHeightPx
                pull.value > 0f -> -pull.value
                else -> listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }

/**
 * Distance the user is currently dragging the list past its top, in pixels.
 *
 * Taken from the scroll the list itself could not consume, damped so the artwork trails the
 * finger rather than matching it — the resistance is what says "this is as far as it goes"
 * without stopping the gesture dead. Released, it returns on the settle spring so the
 * artwork comes home with the same weight it went out with.
 */
@Composable
private fun rememberOverscrollPull(reduceMotion: Boolean): Pair<State<Float>, NestedScrollConnection> {
    val raw = remember { mutableFloatStateOf(0f) }
    val connection =
        remember(reduceMotion) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (reduceMotion) return Offset.Zero
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    if (available.y <= 0f) return Offset.Zero
                    raw.floatValue += available.y * OVERSCROLL_DAMPING
                    // Not consumed: the list's own overscroll effect should still play, and
                    // claiming it here would fight the pull-to-refresh above it.
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (raw.floatValue != 0f) {
                        Animatable(raw.floatValue).animateTo(0f, Motion.settle<Float>()) {
                            raw.floatValue = value
                        }
                    }
                    return Velocity.Zero
                }
            }
        }
    return remember(raw, connection) { raw to connection }
}

/** How much of an over-drag the artwork actually takes. */
private const val OVERSCROLL_DAMPING = 0.5f

/** 0 while the artwork owns the top edge, 1 once the glass bar has taken over. */
@Composable
private fun rememberTopBarProgress(
    listState: LazyListState,
    heroHeightPx: Float,
    density: Density,
): State<Float> =
    remember(listState, heroHeightPx, density) {
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
    animationKey: String,
    sharedKey: MediaSharedElementKey?,
    scroll: State<Float>,
    onResolvedUrl: (String) -> Unit,
) {
    // 详情页顶图 1.08 → 1, §3.1. The parallax below has always been here; the entrance
    // it belongs to was not, so the artwork simply appeared at rest.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val sharedEntrance = isSharedMediaArtworkActive(sharedKey)
    var entered by remember(animationKey) { mutableStateOf(sharedEntrance) }
    LaunchedEffect(animationKey) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec =
            tween(
                durationMillis = if (reduceMotion) 0 else Motion.EXPAND,
                easing = Motion.Curve,
            ),
        label = "heroEntrance",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            // FallbackImage is transparent until its first drawable is ready. Paint the same
            // surface used by the page and the hero scrim from frame one, so image loading
            // cannot expose a separate band between the artwork and the content below.
            .background(surfaceColor)
            .graphicsLayer {
                // Upward, a restrained parallax: the artwork lags the page so the two read
                // as separate planes.
                //
                // Downward, the artwork stretches to meet the finger. [scroll] goes negative
                // on over-drag, and the hero used to ignore that entirely — the page rubber-
                // banded away and left a gap of backdrop above a picture that stayed exactly
                // where it was. Growing it from the top edge instead is the oldest gesture on
                // the platform and the reason a large-artwork page feels attached to the
                // hand: pull, and the thing you are pulling gives.
                val over = (-scroll.value).coerceAtLeast(0f)
                if (over > 0f) {
                    val grow = 1f + over / size.height
                    scaleX = grow
                    scaleY = grow
                    // Anchored to the top edge so the stretch fills the gap the over-drag
                    // opened rather than pushing the artwork further down it.
                    transformOrigin = TransformOrigin(0.5f, 0f)
                } else {
                    translationY = scroll.value * 0.35f
                }
            },
    ) {
        // Clip only the artwork plane. The outer hero may still grow for pull-down overscroll,
        // while its 1.08 entrance can no longer bleed below the physical hero edge without
        // the scrim and flash through the sheet's transparent gradient start.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            FallbackImage(
                urls = urls,
                contentDescription = title,
                // The hero already owns the scale entrance. Resolve readiness with alpha only
                // so a network/disk result does not arrive as a hard cut or add a second scale.
                progressive = true,
                alphaOnly = true,
                onResolvedUrl = onResolvedUrl,
                modifier =
                    Modifier
                        .sharedMediaArtwork(sharedKey)
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale =
                                1f +
                                    (Motion.DETAIL_HERO_SCALE_FROM - 1f) * (1f - entrance)
                            scaleX = scale
                            scaleY = scale
                        },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(heroScrim(surfaceColor)),
            )
        }
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
    backdrop: BackdropState,
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
    // 0.94 was very nearly opaque, and it had to be: with nothing blurred behind it, any
    // less and the poster underneath read straight through the title. Now that §8.1's blur
    // is actually under the plate, the fill can go back to being a fill — this bar was the
    // last chrome in the app still compensating for a missing material with alpha.
    val plateFill = surfaceColor.copy(alpha = 0.72f)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value }
                .backdropBlur(backdrop, RectangleShape)
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
                modifier =
                    Modifier
                        .pressable(onClick = onBack)
                        .touchTarget()
                        .size(38.dp)
                        .liquidGlass(
                            shape = CircleShape,
                            fill =
                                lerp(
                                    Color(0xFF11151F).copy(alpha = 0.28f),
                                    palette.card2,
                                    p,
                                ),
                            border =
                                lerp(
                                    Color.White.copy(alpha = 0.34f),
                                    palette.border,
                                    p,
                                ),
                            over = behind,
                            sheen = 0.7f,
                        ).padding(11.dp),
            )
            Text(
                title,
                style = AppTypography.section.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = progress.value },
            )
            if (showPlay) {
                Row(
                    Modifier
                        .graphicsLayer { alpha = progress.value }
                        .pressable(enabled = solid, onClick = onPlay)
                        .touchTarget()
                        .liquidGlass(
                            shape = GlassShapes.chip,
                            fill = accent.copy(alpha = 0.14f),
                            border = accent.copy(alpha = 0.30f),
                            // It only ever appears once the bar's own plate is opaque.
                            over = surfaceColor,
                            sheen = 0.7f,
                        ).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Play, null, tint = accent, modifier = Modifier.size(10.dp))
                    Text("播放", style = AppTypography.body.strong, color = accent)
                }
            }
            if (showMore) {
                // Unlike the title and the play shortcut this does not fade in with scroll:
                // it is the only route to 下载 / 标记已看 / 一起看, so it has to be reachable
                // from the top of the page as well as the bottom.
                Icon(
                    AppIcons.More,
                    contentDescription = "更多操作",
                    tint = lerp(Color.White, palette.text, p),
                    modifier =
                        Modifier
                            .pressable(onClick = onMore)
                            .touchTarget()
                            .size(38.dp)
                            .liquidGlass(
                                shape = CircleShape,
                                fill =
                                    lerp(
                                        Color(0xFF11151F).copy(alpha = 0.28f),
                                        palette.card2,
                                        p,
                                    ),
                                border =
                                    lerp(
                                        Color.White.copy(alpha = 0.34f),
                                        palette.border,
                                        p,
                                    ),
                                over = behind,
                                sheen = 0.7f,
                            ).padding(11.dp),
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
 * continues beneath 播放 and ends after the primary action.
 *
 * Which is also why the inks here are fixed rather than from the palette: this copy is on
 * a photograph in both themes. The caller keeps the artwork clean behind it — see the
 * `start` on [heroPanelBrush] — so nothing here ever sits half on artwork and half on
 * page, where no single ink would work.
 */
@Composable
private fun TitleBlock(
    detail: MediaDetail,
    title: String,
    accent: Color,
    version: MediaVersion?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            title,
            style = AppTypography.display.strong,
            color = ArtworkInk,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts =
            listOfNotNull(
                detail.year?.toString(),
                detail.runtimeMinutes?.let(::runtimeLabel),
            )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                facts.joinToString(" · "),
                style = AppTypography.body.regular,
                color = ArtworkInkSub,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
        if (detail.communityRating != null || detail.officialRating != null) {
            Spacer(Modifier.height(9.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                detail.communityRating?.let {
                    RatingFigure((it * 10).toInt() / 10.0, accent)
                }
                detail.officialRating?.let { CertificationBadge(it) }
            }
        }
        detail.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() }?.let { genre ->
            Spacer(Modifier.height(8.dp))
            Text(genre, style = AppTypography.body.medium, color = ArtworkInkFaint, maxLines = 1)
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
 * Body of the primary key, derived from the same artwork accent the rest of the page is tinted
 * by. A fixed brand gradient made the one saturated element on the page the one element that
 * ignored the poster it sits under.
 *
 * A poster's vibrant swatch lands anywhere on the lightness scale and this key carries white
 * copy, so the hue is kept while lightness is pulled into a band white stays legible on.
 */
private fun actionKeyBrush(accent: Color): Brush {
    val body = accent.forWhiteInk()
    return cssLinearGradient(135f, 0f to lerp(body, Color.White, 0.22f), 1f to body)
}

private fun Color.forWhiteInk(): Color {
    val luminance = luminance()
    if (luminance <= MaxActionKeyLuminance) return this
    // Straight toward black keeps the hue and spends only lightness.
    val excess = ((luminance - MaxActionKeyLuminance) / luminance).coerceIn(0f, 1f)
    return lerp(this, Color.Black, excess)
}

private const val MaxActionKeyLuminance = 0.22f

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
private val ArtworkInkSub = Color.White.copy(alpha = 0.94f)
private val ArtworkInkFaint = Color.White.copy(alpha = 0.84f)

/** `TMDB` in the secondary ink, the figure itself large and in the accent. */
@Composable
private fun RatingFigure(
    rating: Double,
    accent: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TMDB", style = AppTypography.body.strong, color = ArtworkInkSub)
        Text(rating.toString(), style = AppTypography.section.strong, color = lerp(accent, Color.White, 0.38f))
    }
}

/** 分级 is a classification, not a score — it gets a neutral outline, not a brand colour. */
@Composable
private fun CertificationBadge(label: String) {
    Text(
        label,
        style = AppTypography.caption.strong,
        color = ArtworkInkSub,
        modifier =
            Modifier
                .solidGlass(
                    shape = AppShapes.micro,
                    fill = Color.Transparent,
                    border = ArtworkInkSub.copy(alpha = 0.42f),
                ).padding(horizontal = 7.dp, vertical = 2.dp),
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
    /** Shown only when there is progress to discard. */
    canPlayFromStart: Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onFavorite: () -> Unit,
    onWatchLater: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One confident primary key, with a translucent icon well inside the branded body.
        // The two material layers and the lifted shadow keep it dimensional in both themes,
        // while the established brand gradient preserves the rest of the page's palette.
        Row(
            Modifier
                .fillMaxWidth()
                .height(DetailPlayButtonHeight)
                .shadow(GlassLift.key, GlassShapes.card)
                .clip(GlassShapes.card)
                .background(actionKeyBrush(accent))
                .border(
                    Dimens.hairline,
                    Color.White.copy(alpha = 0.34f),
                    GlassShapes.card,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(DetailPlayButtonHeight)
                    .pressable(enabled = !resolving, onClick = onPlay)
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(
                            Dimens.hairline,
                            Color.White.copy(alpha = 0.22f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (resolving) {
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = AppTypography.body.strong,
                        color = Color.White,
                        maxLines = 1,
                    )
                    detailLine?.let {
                        Text(
                            it,
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.76f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(14.dp),
                )
            }
            if (canPlayFromStart) {
                Box(
                    Modifier
                        .width(Dimens.hairline)
                        .height(30.dp)
                        .background(Color.White.copy(alpha = 0.26f)),
                )
                Column(
                    Modifier
                        .width(74.dp)
                        .height(DetailPlayButtonHeight)
                        .pressable(
                            enabled = !resolving,
                            onClickLabel = "从头播放",
                            onClick = onPlayFromStart,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        AppIcons.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "从头",
                        style = AppTypography.caption.strong,
                        color = Color.White,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassActionButton(
                icon = if (favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = if (favorite) "已收藏" else "收藏",
                active = favorite,
                accent = accent,
                onClick = onFavorite,
                modifier = Modifier.weight(1f),
            )
            GlassActionButton(
                icon = AppIcons.Bookmark,
                label = "稍后观看",
                active = false,
                accent = accent,
                onClick = onWatchLater,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A layered secondary key: glass body, inset icon well and a visible selected state. */
@Composable
private fun GlassActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val fill =
        when {
            active -> accent.copy(alpha = if (palette.isDark) 0.20f else 0.12f)
            palette.isDark -> Color.White.copy(alpha = 0.075f)
            else -> Color.White.copy(alpha = 0.72f)
        }
    val edge =
        when {
            active -> accent.copy(alpha = 0.38f)
            palette.isDark -> Color.White.copy(alpha = 0.19f)
            else -> Color(0xFFE0E7F1)
        }
    Row(
        modifier
            .height(46.dp)
            // 收藏 / 稍后观看 change state in place and navigate nowhere, so the tap needs
            // to be felt as well as seen.
            .pressable(haptic = HapticSignal.Confirm, onClick = onClick)
            .shadow(GlassLift.control, GlassShapes.card)
            .liquidGlass(
                shape = GlassShapes.card,
                fill = fill,
                border = edge,
                sheen = 0.72f,
            ).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        accent.copy(alpha = 0.14f)
                    } else {
                        palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.045f)
                    },
                ).border(
                    Dimens.hairline,
                    if (active) accent.copy(alpha = 0.20f) else palette.border,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BurstIcon(
                icon = icon,
                active = active,
                contentDescription = label,
                tint = if (active) accent else palette.body,
                burstColor = accent,
            )
        }
        Text(
            label,
            style = if (active) AppTypography.body.strong else AppTypography.body.medium,
            color = if (active) accent else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
        }
    }
}

// ---------------------------------------------------------------- sections

/** 分类 — the genres as chips, which is the only place they are listed in full. */
@Composable
private fun GenreSection(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("分类")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genres.take(6).forEach { genre ->
                Text(
                    genre,
                    style = AppTypography.body.strong,
                    color = palette.body,
                    modifier =
                        Modifier
                            .shadow(GlassLift.control, GlassShapes.chip)
                            .liquidGlass(
                                shape = GlassShapes.chip,
                                fill =
                                    if (palette.isDark) {
                                        Color.White.copy(alpha = 0.075f)
                                    } else {
                                        Color.White.copy(alpha = 0.72f)
                                    },
                                border = palette.border,
                                sheen = 0.7f,
                            ).padding(horizontal = 12.dp, vertical = 6.dp),
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
                    urls =
                        listOf(
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
                    modifier =
                        Modifier
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
                            fill =
                                if (palette.isDark) {
                                    Color.White.copy(alpha = 0.075f)
                                } else {
                                    Color.White.copy(alpha = 0.72f)
                                },
                            border = palette.border,
                            sheen = 0.7f,
                        ).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Cloud,
                        contentDescription = null,
                        tint = palette.sub,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(label, style = AppTypography.body.strong, color = palette.body)
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
    fun id(name: String) =
        providerIds.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    return buildList {
        id("Tmdb")?.let { add("TMDB" to "https://www.themoviedb.org/movie/$it") }
        id("Imdb")?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
        id("Tvdb")?.let { add("TheTVDB" to "https://thetvdb.com/dereferrer/series/$it") }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppTypography.section.strong, color = LocalPalette.current.text)
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
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var overflowed by remember(text) { mutableStateOf(false) }
    val canToggle = overflowed || expanded
    Column(
        modifier
            .animateContentSize(
                animationSpec = if (reduceMotion) snap() else Motion.settle(),
            ).then(
                if (canToggle) {
                    Modifier
                        .pressable(
                            onClickLabel = if (expanded) "收起剧情简介" else "展开剧情简介",
                            onClick = onToggle,
                        ).semantics {
                            stateDescription = if (expanded) "已展开" else "已收起"
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        SectionHeader("剧情简介")
        Text(
            text,
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.body,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
        )
        if (overflowed || expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) "收起" else "展开",
                style = AppTypography.body.strong,
                color = accent,
            )
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
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rotation by animateFloatAsState(
        targetValue = if (pickerOpen) 180f else 0f,
        animationSpec = Motion.settle(reduceMotion),
        label = "seasonChevron",
    )
    Column(
        modifier.animateContentSize(
            animationSpec = if (reduceMotion) snap() else Motion.settle(),
        ),
    ) {
        SectionHeader(seasonLabel) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 共 N 集 is the label and the way in: a rail shows four of them, and the
                // count is exactly the promise the full list keeps.
                Row(
                    Modifier
                        .pressable(onClick = onSeeAll)
                        .heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("共 $episodeCount 集", style = AppTypography.caption.medium, color = palette.sub2)
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
                            .heightIn(min = 44.dp)
                            .shadow(GlassLift.control, GlassShapes.thumb)
                            .liquidGlass(
                                shape = GlassShapes.thumb,
                                fill = accent.copy(alpha = 0.13f),
                                border = accent.copy(alpha = 0.28f),
                                sheen = 0.7f,
                            ).padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("切换季数", style = AppTypography.caption.medium, color = accent)
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
                        style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
                        color = if (selected) accent else palette.body,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .pressable { onSelectSeason(id) }
                                // No lift: these read as one picker's options, and a shadow under
                                // each would break the row into a scatter of separate keys.
                                .liquidGlass(
                                    shape = GlassShapes.thumb,
                                    fill =
                                        if (selected) {
                                            accent.copy(alpha = 0.14f)
                                        } else if (palette.isDark) {
                                            palette.card2
                                        } else {
                                            Color.White.copy(alpha = 0.52f)
                                        },
                                    border =
                                        if (selected) {
                                            accent.copy(alpha = 0.30f)
                                        } else {
                                            palette.border
                                        },
                                    sheen = 0.7f,
                                ).padding(horizontal = 12.dp, vertical = 7.dp),
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
    seriesPosterUrl: String?,
    selectedEpisodeId: String?,
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
    val listState = rememberLazyListState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    val focusedEpisodeIndex =
        remember(episodes, selectedEpisodeId) {
            episodeFocusIndex(episodes, selectedEpisodeId)
        }
    var initiallyPositioned by remember(selectedSeasonId) { mutableStateOf(false) }

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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val centeredOffset =
                with(density) {
                    -((maxWidth - 172.dp) / 2f).coerceAtLeast(0.dp).roundToPx()
                }
            LaunchedEffect(
                selectedEpisodeId,
                focusedEpisodeIndex,
                reduceMotion,
                routeVisible,
                centeredOffset,
            ) {
                if (!routeVisible || focusedEpisodeIndex < 0) return@LaunchedEffect
                if (!initiallyPositioned || reduceMotion) {
                    listState.scrollToItem(focusedEpisodeIndex, centeredOffset)
                    initiallyPositioned = true
                } else {
                    listState.animateScrollToItem(focusedEpisodeIndex, centeredOffset)
                }
            }
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            ) {
                itemsIndexed(
                    episodes,
                    key = { index, episode -> "ep-${episode.id}-$index" },
                ) { _, episode ->
                    EpisodeCard(
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        episode = episode,
                        seriesPosterUrl = seriesPosterUrl,
                        accent = accent,
                        selected = episode.id == selectedEpisodeId,
                        onPlay = { onPlayEpisode(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    baseUrl: String,
    accessToken: String,
    episode: Episode,
    seriesPosterUrl: String?,
    accent: Color,
    selected: Boolean,
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
                fill =
                    when {
                        selected -> accent.copy(alpha = 0.14f)
                        watching -> accent.copy(alpha = 0.08f)
                        palette.isDark -> palette.card
                        else -> Color.White.copy(alpha = 0.56f)
                    },
                border =
                    if (selected) {
                        accent.copy(alpha = 0.52f)
                    } else {
                        Color.White.copy(alpha = if (palette.isDark) 0.20f else 0.86f)
                    },
            ).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(86.dp)) {
            Poster(
                url =
                    EmbyImages.primary(
                        baseUrl,
                        episode.id,
                        episode.primaryTag,
                        maxHeight = 240,
                        accessToken = accessToken,
                    ),
                fallbackUrls = listOfNotNull(seriesPosterUrl),
                shape = GlassShapes.thumb,
                progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                modifier = Modifier.fillMaxSize(),
            )
            if (episode.played) {
                EpisodeWatchedBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Column {
            Text(
                listOfNotNull(episode.indexNumber?.let { "第${it}集" }, episode.name)
                    .joinToString(" · "),
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!episode.overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    episode.overview,
                    style = AppTypography.caption.regular.copy(lineHeight = 16.5.sp),
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (selected) {
                        append("已选中 · 再次点击播放")
                    } else if (watching) {
                        append("正在观看")
                    }
                    val runtime = episode.runtimeMinutes?.let { "$it 分钟" }
                    if ((selected || watching) && runtime != null) append(" · ")
                    if (runtime != null) append(runtime)
                },
                style = AppTypography.caption.medium,
                color = if (selected || watching) accent else palette.sub2,
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
                        modifier =
                            Modifier
                                .size(52.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.88f), CircleShape),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.name,
                        style = AppTypography.caption.medium,
                        color = palette.body,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!person.role.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            person.role,
                            style = AppTypography.caption.regular,
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
 * Loading placeholder shaped like the page it becomes.
 */
@Composable
private fun DetailSkeleton(heroHeight: Dp) {
    val palette = LocalPalette.current
    val fill = if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)
    Column(Modifier.fillMaxSize()) {
        // A loading placeholder can disappear before Compose's shared-transition overlay has
        // received its first bounds. Making that short-lived node a shared element leaves the
        // overlay trying to draw a detached node and crashes with "current bounds not set yet".
        // The real hero below remains shared once the detail has loaded.
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(fill),
        )
        Column(
            Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .width(96.dp)
                        .height(142.dp)
                        .clip(GlassShapes.poster)
                        .background(fill),
                )
                Column(
                    Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(18.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.46f)
                            .height(11.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(11.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(GlassShapes.card)
                    .background(fill),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(GlassShapes.chip)
                            .background(fill),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(GlassShapes.thumb)
                    .background(fill),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .height(12.dp)
                    .clip(GlassShapes.thumb)
                    .background(fill),
            )
        }
    }
}
