package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.CalendarIdentityAmbiguousException
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.ArtworkAccent
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.HeroPageFade
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberArtworkPageColor
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.rememberRetainedArtworkPageColor
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.player.PlaybackSelection
import com.yfuse.feature.player.PlaybackSelectionState
import com.yfuse.feature.watch.WatchInviteShareSheet
import kotlinx.coroutines.launch

/** Height of the collapsing top bar's content row, above the status bar inset. */
internal val TopBarHeight = 52.dp

/** The sheet's own rhythm — above the title block, and between it and everything after. */
internal val SheetGap = 18.dp

/** The hero overlap is measured through the premium primary play key. */
internal val DetailPlayButtonHeight = 52.dp

/** Keep the hero artwork visible for 20dp below the primary play button. */
internal val PlayButtonHeroOverlap = SheetGap + DetailPlayButtonHeight + 20.dp

/**
 * A one-line title with year, rating and genre under it. Only the seed for the measured
 * lift, so being a little out costs one frame of settling and nothing else.
 */
internal val TypicalCaptionHeight = 116.dp

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
    val artworkColorUrl = resolvedHeroUrl ?: posterUrl
    val artworkFallback = remember(heroIdentity) { detailArtworkFallbackColor(heroIdentity) }
    // Use the image that actually resolved in the hero. Sampling a preferred poster URL even
    // when the backdrop was on screen made the primary action look unrelated to the page.
    val detailAccent =
        rememberAnimatedArtworkAccent(
            url = artworkColorUrl,
            fallback = artworkFallback,
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
    // The action describes what will play. Server identity belongs to 资源; putting it here
    // made labels such as "WordPress · S1 E3" look like episode metadata.
    val playDetailLine =
        remember(state.playTarget) {
            val target = state.playTarget
            val coordinate =
                target?.episodeNumber?.let { episodeNumber ->
                    target.seasonNumber
                        ?.takeIf { it > 1 }
                        ?.let { seasonNumber -> "第 $seasonNumber 季 · 第 $episodeNumber 集" }
                        ?: "第 $episodeNumber 集"
                }
            listOfNotNull(
                coordinate,
                target?.runtimeMinutes?.let(::runtimeLabel),
            ).joinToString(" · ").takeIf { it.isNotBlank() }
        }

    val watchTogether = component.dependencies.watchTogether
    val detailScope = rememberCoroutineScope()
    val followedSeries by component.dependencies.calendarFollowStore.followed
        .collectAsState()
    val watchPreferences = component.dependencies.watchTogetherPreferences
    val accountState by component.dependencies.account.state
        .collectAsState()
    val watchAvailable = accountState.canUseWatchTogether()
    val watchState by watchTogether.state.collectAsState()
    val watchEndpoint by watchPreferences.endpoint.collectAsState()
    val share = rememberShareHandler()
    var shareSheetOpen by remember { mutableStateOf(false) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var downloadSheetOpen by remember { mutableStateOf(false) }
    var organizationSheetOpen by remember { mutableStateOf(false) }
    var sourceListOpen by remember { mutableStateOf(false) }
    var allEpisodesOpen by remember { mutableStateOf(false) }
    var airingCalendarOpen by remember { mutableStateOf(false) }
    var airingCalendarReload by remember { mutableStateOf(0) }
    var airingCalendarLoading by remember(detail?.id) { mutableStateOf(false) }
    var airingCalendarDays by remember(detail?.id) { mutableStateOf<List<CalendarDay>>(emptyList()) }
    var airingCalendarError by remember(detail?.id) { mutableStateOf<String?>(null) }
    var airingCalendarCandidates by remember(detail?.id) {
        mutableStateOf<List<TmdbSeriesIdentityCandidate>>(emptyList())
    }
    var followAfterIdentitySelection by remember(detail?.id) { mutableStateOf(false) }
    val detailFollow =
        detail?.let { item ->
            val directTmdb = item.airingCalendarTmdbId()
            followedSeries.firstOrNull { followed ->
                (directTmdb != null && followed.tmdbId == directTmdb) ||
                    (followed.seriesItemId == item.id && followed.serverId == (state.server?.id ?: component.serverId))
            }
        }
    val detailIsFollowed = detailFollow != null

    LaunchedEffect(airingCalendarOpen, airingCalendarReload, detail?.id) {
        val target = detail ?: return@LaunchedEffect
        if (!airingCalendarOpen || !target.type.equals("Series", ignoreCase = true)) return@LaunchedEffect
        airingCalendarLoading = true
        airingCalendarError = null
        component
            .loadSeriesAiringCalendar(target) { preview -> airingCalendarDays = preview }
            .onSuccess { airingCalendarDays = it }
            .onFailure { error ->
                if (error is CalendarIdentityAmbiguousException) {
                    airingCalendarCandidates = error.candidates
                    airingCalendarError = error.message
                } else {
                    airingCalendarError = error.toUserMessage("播出日历加载失败，请重试")
                }
            }
        airingCalendarLoading = false
    }

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
                    WindowWidthTier.Compact -> (maxHeight * 0.48f).coerceAtMost(520.dp)
                    WindowWidthTier.Medium -> (maxHeight * 0.48f).coerceAtMost(580.dp)
                    WindowWidthTier.Expanded -> (maxHeight * 0.46f).coerceAtMost(620.dp)
                }
            val heroHeightPx = with(density) { heroHeight.toPx() }

            // Lift the measured caption and the primary action over the artwork. The backdrop
            // continues 20dp below 播放, while the blend into the page still begins at the
            // artwork's physical lower edge.
            var captionLift by remember {
                mutableStateOf(TypicalCaptionHeight + SheetGap + PlayButtonHeroOverlap)
            }

            val artworkAspectRatio = maxWidth.value / heroHeight.value.coerceAtLeast(1f)
            val artworkFadeFraction =
                (HeroPageFade.value / heroHeight.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f)
            val sampledPageColor =
                rememberArtworkPageColor(
                    url = resolvedHeroUrl,
                    targetAspectRatio = artworkAspectRatio,
                    fadeFraction = artworkFadeFraction,
                )
            val retainedPageColor =
                rememberRetainedArtworkPageColor(
                    "detail:${state.server?.id ?: component.serverId}:${detail?.id ?: component.itemId}",
                )
            LaunchedEffect(sampledPageColor) {
                sampledPageColor?.let(retainedPageColor::update)
            }
            val detailSurface =
                retainedPageColor.value
                    ?.let { sampled ->
                        // Keep the artwork hue without washing the entire detail page in the
                        // sampled colour. Light pages need more neutral ground than dark pages.
                        lerp(sampled, palette.background, if (palette.isDark) 0.18f else 0.34f)
                    } ?: palette.background
            val detailPlayColor =
                remember(detailAccent, detailSurface) {
                    // The primary key must be visibly separate from the artwork-tinted page,
                    // not merely another rectangle of the same purple/green/blue.
                    readableStateAccent(detailAccent, detailSurface, minimumRatio = 3.0f)
                }

            ArtworkPageTheme(
                background = detailSurface,
                artworkAccent = detailAccent,
            ) {
                val pagePalette = LocalPalette.current

                // A different detail route must always start at its hero. Keying the state by the
                // route item also prevents a newly opened title inheriting the previous title's offset.
                val listState = remember(component.itemId) { LazyListState() }
                val detailBackdrop = rememberBackdropState()
                val (overscrollPull, overscrollConnection) =
                    rememberOverscrollPull(
                        LocalAccessibilityOptions.current.reduceMotion,
                    )
                val heroScroll = rememberHeroScroll(listState, heroHeightPx, overscrollPull)
                val topBarProgress =
                    rememberTopBarProgress(
                        listState = listState,
                        heroHeightPx = heroHeightPx,
                        density = density,
                        // Take over before the lifted title reaches the status bar.
                        takeoverInset = captionLift + TopBarHeight,
                    )
                val barSolid by remember(topBarProgress) { derivedStateOf { topBarProgress.value > 0.5f } }

                StatusBarIconStyle(darkIcons = !pagePalette.isDark && (detail == null || barSolid))

                // The only opaque ground on the page. Hero, sheet and tail all reveal this exact colour.
                Box(Modifier.fillMaxSize().background(detailSurface))

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
                                        accent = detailPlayColor,
                                        label = if (state.playPositionTicks > 0L) "继续播放" else "播放",
                                        detailLine = playDetailLine,
                                        resumeTimeLabel = formatResumePosition(state.playPositionTicks),
                                        resolving = state.resolvingPlay || state.selectionLoading,
                                        favorite = detail.isFavorite,
                                        watchLater = state.watchLater,
                                        watchLaterBusy = state.watchLaterBusy,
                                        canPlayFromStart = state.playPositionTicks > 0L,
                                        onPlay = { component.store.accept(DetailIntent.Play) },
                                        onPlayFromStart = {
                                            component.store.accept(DetailIntent.PlayFromStart)
                                        },
                                        onFavorite = {
                                            component.store.accept(DetailIntent.ToggleFavorite)
                                        },
                                        onWatchLater = {
                                            component.store.accept(DetailIntent.ToggleWatchLater)
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
                                        availableEpisodeCount = state.episodes.size,
                                        seasons = state.seasons.map { it.id to it.name },
                                        selectedSeasonId = state.selectedSeasonId,
                                        pickerOpen = seasonPickerOpen,
                                        onTogglePicker = { seasonPickerOpen = !seasonPickerOpen },
                                        onSelectSeason = {
                                            seasonPickerOpen = false
                                            component.store.accept(DetailIntent.SelectSeason(it))
                                        },
                                        onManageProgress = {
                                            component.store.accept(DetailIntent.OpenProgressManager)
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
                    accent = detailPlayColor,
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
                    DetailMoreActionsDialog(
                        title = detail.title,
                        artworkUrls = heroUrls,
                        isSeries = detail.type.equals("Series", ignoreCase = true),
                        followed = detailIsFollowed,
                        played = detail.played,
                        isPlex = state.server?.kind == com.yfuse.core.model.MediaServerKind.Plex,
                        watchAvailable = watchAvailable,
                        watchActive = watchState.roomCode != null,
                        onDownload = {
                            moreSheetOpen = false
                            downloadSheetOpen = true
                        },
                        onCalendar = {
                            moreSheetOpen = false
                            airingCalendarOpen = true
                        },
                        onToggleFollow = {
                            moreSheetOpen = false
                            detailScope.launch {
                                component.toggleSeriesFollow(detail).onFailure { error ->
                                    if (error is CalendarIdentityAmbiguousException) {
                                        followAfterIdentitySelection = true
                                        airingCalendarCandidates = error.candidates
                                        airingCalendarError = error.message
                                        airingCalendarOpen = true
                                    } else {
                                        airingCalendarError = error.toUserMessage("追剧设置失败，请重试")
                                    }
                                }
                            }
                        },
                        onTogglePlayed = {
                            moreSheetOpen = false
                            component.store.accept(DetailIntent.TogglePlayed)
                        },
                        onOrganization = {
                            moreSheetOpen = false
                            organizationSheetOpen = true
                            component.store.accept(DetailIntent.LoadOrganizationContainers)
                        },
                        onRefresh = {
                            moreSheetOpen = false
                            detailScope.launch {
                                component.refreshServerMetadata(detail)
                            }
                        },
                        onAnalyze = {
                            moreSheetOpen = false
                            detailScope.launch {
                                component.analyzeServerMetadata(detail)
                            }
                        },
                        // Playback is intentionally not started here. The invite sheet owns the
                        // handoff so the host can share the room before entering the player.
                        onWatchTogether = {
                            moreSheetOpen = false
                            watchTogether.createRoom(
                                endpoint = watchEndpoint,
                                mediaKey = detail.providerIds.watchKey(detail.id),
                            )
                            shareSheetOpen = true
                        },
                        onDismiss = { moreSheetOpen = false },
                    )
                }

                val downloadTarget = state.playTarget
                if (downloadSheetOpen && downloadTarget != null) {
                    OfflineDownloadDialog(
                        detail = downloadTarget,
                        episodes = state.episodes,
                        selectedVersionId = state.selectedVersionId,
                        allowedQualities =
                            if (state.playServer?.kind == com.yfuse.core.model.MediaServerKind.Plex) {
                                listOf(com.yfuse.core.offline.OfflineDownloadQuality.Original)
                            } else {
                                com.yfuse.core.offline.OfflineDownloadQuality.entries
                            },
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

                if (airingCalendarOpen && detail != null) {
                    SeriesAiringCalendarDialog(
                        title = detail.title,
                        days = airingCalendarDays,
                        loading = airingCalendarLoading,
                        error = airingCalendarError,
                        artworkUrls = heroUrls,
                        artworkColorUrl = artworkColorUrl,
                        identityCandidates = airingCalendarCandidates,
                        followed = detailIsFollowed,
                        reminderMode = detailFollow?.reminderMode ?: CalendarReminderMode.Off,
                        remindBeforeMinutes = detailFollow?.remindBeforeMinutes ?: 30,
                        onToggleFollow = {
                            detailScope.launch {
                                component.toggleSeriesFollow(detail).onFailure { error ->
                                    if (error is CalendarIdentityAmbiguousException) {
                                        followAfterIdentitySelection = true
                                        airingCalendarCandidates = error.candidates
                                        airingCalendarError = error.message
                                    } else {
                                        airingCalendarError = error.toUserMessage("追剧设置失败，请重试")
                                    }
                                }
                            }
                        },
                        onSetReminder = { mode, beforeMinutes ->
                            detailScope.launch {
                                component.setSeriesReminder(detail, mode, beforeMinutes).onFailure { error ->
                                    airingCalendarError = error.toUserMessage("提醒设置失败，请重试")
                                }
                            }
                        },
                        onRebindIdentity = {
                            detailScope.launch {
                                airingCalendarError = null
                                component
                                    .findSeriesCalendarIdentityCandidates(detail)
                                    .onSuccess { candidates ->
                                        followAfterIdentitySelection = false
                                        airingCalendarCandidates = candidates
                                    }.onFailure { error ->
                                        airingCalendarError =
                                            error.toUserMessage("重新匹配失败，请重试")
                                    }
                            }
                        },
                        onSelectIdentity = { candidate ->
                            component.rememberSeriesCalendarIdentity(detail, candidate)
                            airingCalendarCandidates = emptyList()
                            airingCalendarError = null
                            airingCalendarReload += 1
                            if (followAfterIdentitySelection) {
                                followAfterIdentitySelection = false
                                detailScope.launch {
                                    component.toggleSeriesFollow(detail).onFailure { error ->
                                        airingCalendarError = error.toUserMessage("追剧设置失败，请重试")
                                    }
                                }
                            }
                        },
                        onRetry = { airingCalendarReload += 1 },
                        onDismiss = { airingCalendarOpen = false },
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

                if (state.progressManagerOpen) {
                    EpisodeProgressManager(
                        episodes = state.episodes,
                        baseUrl = playBaseUrl,
                        accessToken = playAccessToken,
                        seriesPosterUrl = heroUrls.getOrNull(1),
                        selectedIds = state.progressSelection,
                        saving = state.progressSaving,
                        accent = detailAccent,
                        onToggle = {
                            component.store.accept(DetailIntent.ToggleProgressEpisode(it))
                        },
                        onPreset = {
                            component.store.accept(DetailIntent.SelectProgressEpisodes(it))
                        },
                        onApply = {
                            component.store.accept(DetailIntent.ApplyEpisodeProgress(it))
                        },
                        onDismiss = {
                            component.store.accept(DetailIntent.CloseProgressManager)
                        },
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
}
