package com.yfuse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArrivalMotion
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.CarouselAutoAdvance
import com.yfuse.core.designsystem.CloudPlayerLogo
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HeroActionDock
import com.yfuse.core.designsystem.HeroPageFade
import com.yfuse.core.designsystem.HeroPageIndicator
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LivingPosterAmbient
import com.yfuse.core.designsystem.LivingPosterDefaults
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.MediaSizing
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OrbProgressDefaults
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.OverlayPage
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.PrimaryGradient
import com.yfuse.core.designsystem.RefreshIndicator
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SkeletonArrivalScope
import com.yfuse.core.designsystem.SkeletonRail
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.arrivalSweep
import com.yfuse.core.designsystem.carouselArtworkMotion
import com.yfuse.core.designsystem.carouselCaptionEntry
import com.yfuse.core.designsystem.carouselPageVisual
import com.yfuse.core.designsystem.carouselTouchPause
import com.yfuse.core.designsystem.fadeIntoPage
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroDurationLabel
import com.yfuse.core.designsystem.heroMediaTypeLabel
import com.yfuse.core.designsystem.heroScrollCollapse
import com.yfuse.core.designsystem.heroTopScrim
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.livingPosterFrame
import com.yfuse.core.designsystem.livingPosterHeroHeight
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselSemantics
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.playerArtworkOnClick
import com.yfuse.core.designsystem.playerArtworkSource
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.refreshAction
import com.yfuse.core.designsystem.rememberArtworkAccentTarget
import com.yfuse.core.designsystem.rememberArtworkPageColor
import com.yfuse.core.designsystem.rememberCarouselCaptionProgress
import com.yfuse.core.designsystem.rememberCarouselPageColor
import com.yfuse.core.designsystem.rememberLightFeedback
import com.yfuse.core.designsystem.rememberLoopingCarouselState
import com.yfuse.core.designsystem.rememberRefreshReveal
import com.yfuse.core.designsystem.rememberRetainedArtworkPageColor
import com.yfuse.core.designsystem.rememberScrolledPastHero
import com.yfuse.core.designsystem.skeletonSweep
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.model.showsReleaseDate
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.util.currentHourOfDay
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The hero artwork changes every few seconds, while the status-bar ink stays white.
 *
 * [heroReelScrim] keeps the whole header readable without flattening the artwork, but its
 * deliberately light top wash is not a contrast guarantee for the smaller system glyphs.
 * This short, stronger cap is confined to the status/header area so even a white poster has
 * a stable dark surface behind the clock and indicators.
 */
private val HomeStatusBarScrim =
    Brush.verticalGradient(
        0f to Color(0xFF080B12).copy(alpha = 0.78f),
        0.46f to Color(0xFF080B12).copy(alpha = 0.46f),
        1f to Color.Transparent,
    )

private val HomeStatusBarScrimHeight = 128.dp

/** Successive placeholder shelves breathe a little after one another, top to bottom. */
private const val SKELETON_SHELF_PHASE_MS = 300

/** Posters a TMDB shelf shows before its 全部 takes over. */
private const val HOME_SHELF_POSTERS = 12

/**
 * The caption clears the whole dissolve band.
 *
 * White copy is legible on artwork and on the scrim over it; it is not legible on the page
 * the artwork is turning into. Every line of the caption therefore stays above the band,
 * and only the dots — whose ink is the page's — sit inside it.
 */
private val HomeHeroContentBottom = LivingPosterDefaults.CAPTION_BOTTOM

/**
 * 首页 — the prototype's `isHome` screen:
 * `padding:52px 18px 100px; gap:22px`, greeting, search entry, hero, 继续观看, 为你推荐.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(component: HomeComponent) {
    // The carousel owns which slide is settled, so it reports the colour up rather than the
    // page trying to work it out from an index it does not hold. Hoisted above the content
    // so 跟随封面 can hand it to every control on the page — see [ArtworkPageTheme].
    val navigationState by component.store.states.collectAsState(component.store.state)
    val retainedPageColor =
        rememberRetainedArtworkPageColor("home:${navigationState.server?.id.orEmpty()}")
    val pageColor = rememberCarouselPageColor(retainedPageColor.value)
    var heroAccent by remember { mutableStateOf<Color?>(null) }
    ArtworkPageTheme(
        background = retainedPageColor.value,
        artworkAccent = heroAccent,
    ) {
        HomeContent(
            component = component,
            heroPageColor = pageColor,
            heroPageSampled = retainedPageColor.value != null,
            onHeroAccent = { heroAccent = it },
            onHeroPageColor = retainedPageColor::update,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    component: HomeComponent,
    heroPageColor: State<Color>,
    heroPageSampled: Boolean,
    onHeroAccent: (Color) -> Unit,
    onHeroPageColor: (Color) -> Unit,
) {
    val state by component.store.states.collectAsState(component.store.state)
    val calendarState by component.calendar.collectAsState()
    val listState = component.listState
    // Same measurement as 媒体库's hero, off the same height token: the two roots open on the
    // same picture, so the point where the status bar flips its icons has to be the same one.
    // The shelf opened out into a grid, or null. Held here rather than in the store: it is
    // which page is on screen, not anything about the data.
    val routeVisible = LocalRouteVisible.current
    var hiddenSinceLastRefresh by remember { mutableStateOf(false) }
    var hasBeenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(routeVisible) {
        if (routeVisible && hiddenSinceLastRefresh) {
            hiddenSinceLastRefresh = false
            component.store.accept(HomeIntent.RefreshLibrary)
            // Episodes can arrive while detail/player covers the tab. Refresh the compact
            // calendar too so its 入库/下一集 state is correct on the first frame back home.
            component.refreshCalendar()
        } else if (!routeVisible && hasBeenVisible) {
            hiddenSinceLastRefresh = true
        }
        if (routeVisible) hasBeenVisible = true
    }

    HomeContentBody(
        state = state,
        calendarState = calendarState,
        listState = listState,
        heroPageColor = heroPageColor,
        heroPageSampled = heroPageSampled,
        onHeroAccent = onHeroAccent,
        onHeroPageColor = onHeroPageColor,
        onIntent = component.store::accept,
        onRefreshCalendar = { component.refreshCalendar(forceRefresh = true) },
        onOpenProfile = component.onOpenProfile,
        onOpenCalendar = component.onOpenCalendar,
        onOpenCalendarEntry = component::openCalendarEntry,
    )
}

/** Shared production rendering; benchmark builds supply bounded local data through the same UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContentBody(
    state: HomeState,
    calendarState: HomeCalendarState,
    listState: LazyListState,
    heroPageColor: State<Color>,
    onHeroAccent: (Color) -> Unit,
    onHeroPageColor: (Color) -> Unit,
    onIntent: (HomeIntent) -> Unit,
    onRefreshCalendar: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCalendarEntry: (CalendarEntry) -> Unit,
    /** False until a slide's artwork has been sampled: there is no page colour to paint yet. */
    heroPageSampled: Boolean = true,
) {
    val calendarItems = remember(calendarState.days, state) { homeCalendarPreviews(calendarState.days, state) }
    val showSmartPlaylists =
        com.yfuse.feature.search
            .hasPinnedSmartPlaylists()
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current.accent
    var expandedRow by remember { mutableStateOf<TmdbRow?>(null) }
    var quickActions by remember { mutableStateOf<HomeQuickActions?>(null) }
    // A library shelf opened out by its 全部. Held by kind, and its entries read live, so a card
    // marked watched from inside the page leaves it as it leaves the shelf.
    var expandedShelf by remember { mutableStateOf<HomeLibraryShelf?>(null) }
    val upNext = remember(state.nextUp, state.resume) { homeNextUpShelf(state.nextUp, state.resume) }

    fun shelfEntries(shelf: HomeLibraryShelf): List<HomeResumeEntry> =
        when (shelf) {
            HomeLibraryShelf.ContinueWatching -> state.resume
            HomeLibraryShelf.NextUp -> upNext
            HomeLibraryShelf.Favorites -> state.favorites
        }
    val openEntry: (HomeResumeEntry) -> Unit = { entry ->
        // Detail is pushed over the home page; coming back lands on the feed, as it does from a
        // TMDB shelf's 全部.
        expandedShelf = null
        onIntent(HomeIntent.OpenResume(entry))
    }
    val openShelfEmptied = expandedShelf?.let { shelfEntries(it).isEmpty() } == true
    LaunchedEffect(openShelfEmptied) {
        // The last card marked watched: the shelf has left the home page, and its page goes too.
        if (openShelfEmptied) expandedShelf = null
    }
    val heroSlides = state.featuredSlides
    // With no picks to show — TMDB out of reach, most often — the reel was still 480dp of empty
    // gradient, and the card saying why sat under it with 继续观看 below the fold. It folds to
    // its header once a load has come back empty, and stays folded through the next retry or
    // pull instead of opening back up only to fold again if that one fails too. The first load
    // keeps the full-height placeholder, so arriving picks do not push the page down.
    var heroFoldedBefore by remember { mutableStateOf(false) }
    val heroFolded = heroSlides.isEmpty() && (!state.loading || heroFoldedBefore)
    SideEffect { heroFoldedBefore = heroFolded }

    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = state.refreshing)
    // 首页's calendar and account entries live inside a hero that scrolls away, so
    // this tab is the one where tapping the tab again matters most.
    ScrollToTopOnReselect(listState)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = livingPosterHeroHeight(maxHeight, wideLayout = maxWidth >= 600.dp)
        // Clears the floating dock as it is actually laid out: the fixed 122dp left the last shelf
        // under the glass with three-button navigation or large text.
        val bottomContentInset = floatingNavigationContentInset()
        val showSidePreview = maxWidth >= 600.dp || maxWidth > maxHeight
        // The artwork alpha dissolves directly into this one opaque, poster-derived colour.
        // No seam overlay or local colour band exists between the hero and the page.
        //
        // Until a slide has been sampled there is no such colour, only the palette's own
        // ground — the very colour the shared app backdrop already draws, under the ambient
        // field and the user's wallpaper. Painting it hid both behind an opaque slab, on
        // every root tab, for as long as the hero took to decode. The ground now arrives
        // instead: it fades up over the backdrop on the theme crossfade's own clock once a
        // poster has earned it, and snaps under 减弱动态效果. Both the colour and the fade are
        // read in the draw phase, so neither a settled slide nor the fade recomposes the feed.
        val pageGround =
            animateFloatAsState(
                targetValue = if (heroPageSampled) 1f else 0f,
                animationSpec =
                    if (LocalAccessibilityOptions.current.reduceMotion) {
                        snap()
                    } else {
                        tween(Motion.THEME_CROSSFADE, easing = Motion.Curve)
                    },
                label = "home-page-ground",
            )
        Box(
            Modifier.fillMaxSize().drawBehind {
                val amount = pageGround.value
                if (amount > 0f) drawRect(heroPageColor.value, alpha = amount)
            },
        )

        val scrolledPastHero by rememberScrolledPastHero(listState, heroHeight)
        val heroVisible = !scrolledPastHero
        // A folded header has no artwork under the status bar, so the icons follow the page.
        StatusBarIconStyle(darkIcons = (heroFolded || !heroVisible) && !palette.isDark)
        // Reading `listState.isScrollInProgress` directly in the item's content recomposed the
        // hero every time a scroll started or stopped (LibraryHomeScreen's carouselVisible
        // already takes this shape); derivedStateOf collapses that to one flip per visibility
        // change instead of one recomposition per scroll-state tick.
        val heroCarouselVisible by remember(listState) {
            derivedStateOf { !scrolledPastHero && !listState.isScrollInProgress }
        }
        // 首页 and 媒体库 open on the same full-bleed reel, and they used to disagree about how
        // tall it is: this one sized itself from the window and from whether 继续观看 had
        // anything in it, so the same carousel was one height on the library tab and a
        // different one — changing under the user as their resume list filled up — on the
        // home tab. Both read the shared token now.
        // One page-wide clock for the shelves replaced by a refresh, so every shelf's posters
        // rise under a single sweep of light rather than each shelf flashing on its own. Only a
        // refresh that changed what the shelves hold plays it: one that failed or brought back
        // the same picks is not dressed up as new arrivals, and its notice below says why.
        val shelfRevision = remember(state.content.rows) { homeShelfRevision(state.content.rows) }
        val refreshArrival = rememberRefreshReveal(state.refreshing, revision = shelfRevision)
        val refreshPage = {
            onIntent(HomeIntent.Refresh)
            onRefreshCalendar()
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refreshPage,
            state = pullState,
            indicator = { RefreshIndicator(pullState, state.refreshing, Modifier.align(Alignment.TopCenter)) },
            modifier = Modifier.fillMaxSize(),
        ) {
            SkeletonArrivalScope(state.loading && state.content.isEmpty) {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            // Page-level light for a refresh landing; it draws only while its clock
                            // runs. The skeleton's band crosses the loading shelves alone: across the
                            // whole list it also swept whatever had already arrived.
                            .arrivalSweep(refreshArrival)
                            .testTag("home-feed"),
                    state = listState,
                    contentPadding = PaddingValues(bottom = bottomContentInset),
                    verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                ) {
                    // Navigation in the hero header must remain available even when the remote
                    // recommendation feed is loading or unavailable.
                    motionItem(key = "home-hero", arrival = false) {
                        if (heroFolded) {
                            HeroHeader(
                                userName = state.server?.userName,
                                refreshing = state.refreshing,
                                onRefresh = refreshPage,
                                onOpenProfile = onOpenProfile,
                                onOpenCalendar = onOpenCalendar,
                                onArtwork = false,
                            )
                        } else {
                            Box(Modifier.heroScrollCollapse(listState, heroHeight)) {
                                HomeHeroCarousel(
                                    items = heroSlides.take(8),
                                    userName = state.server?.userName,
                                    height = heroHeight,
                                    showSidePreview = showSidePreview,
                                    visible = heroCarouselVisible,
                                    held = quickActions != null || expandedRow != null || expandedShelf != null,
                                    refreshing = state.refreshing,
                                    onRefresh = refreshPage,
                                    onOpenProfile = onOpenProfile,
                                    onOpenCalendar = onOpenCalendar,
                                    onPlay = { onIntent(HomeIntent.Play(it)) },
                                    onDetails = { onIntent(HomeIntent.Open(it)) },
                                    onFavorite = { onIntent(HomeIntent.Favorite(it)) },
                                    onAccent = onHeroAccent,
                                    onPageColor = onHeroPageColor,
                                )
                            }
                        }
                    }

                    if (showSmartPlaylists) {
                        motionItem(key = "smart-playlists") {
                            com.yfuse.feature.search
                                .SmartPlaylistShelf()
                        }
                    }
                    // Offline, the calendar fails for the same reason the recommendations did.
                    // One card says so, and its 重试 retries both; a second card below it only
                    // repeated the first.
                    val recommendationsFailed = state.error != null && state.content.isEmpty
                    if (state.loading && state.content.isEmpty) {
                        // Two shelves' worth of placeholders rather than one spinner: the page
                        // this becomes is a stack of rails, and a skeleton that is the wrong
                        // shape moves the content once it arrives.
                        motionItem(key = "recommendations-loading") {
                            // One band across both, rather than one per shelf flashing together.
                            Column(
                                Modifier.skeletonSweep(),
                                verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                            ) {
                                repeat(2) { shelf ->
                                    SkeletonRail(
                                        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                                        phaseMs = shelf * SKELETON_SHELF_PHASE_MS,
                                    )
                                }
                            }
                        }
                    } else if (recommendationsFailed) {
                        motionItem(key = "recommendations-error") {
                            ErrorState(
                                message = state.error!!,
                                onRetry = {
                                    onIntent(HomeIntent.Retry)
                                    if (calendarState.error != null) onRefreshCalendar()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    state.recommendationNotice?.let { notice ->
                        motionItem(key = "recommendations-cache-notice") {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Dimens.pageHorizontal)
                                        .glass(AppShapes.chip, palette.card2, palette.border)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = notice,
                                    style = AppTypography.body.medium,
                                    color = palette.sub,
                                    // How a pull that failed, or only partly landed, is told: the
                                    // shelves no longer rise for it, so it has to be heard too.
                                    modifier = Modifier.weight(1f).liveStatus(),
                                )
                                Text(
                                    text = "重新刷新",
                                    style = AppTypography.body.strong,
                                    color = themeAccent,
                                    modifier =
                                        Modifier
                                            .pressable(
                                                onClickLabel = "重新刷新首页",
                                                onClick = { onIntent(HomeIntent.Retry) },
                                            ).touchTarget(),
                                )
                            }
                        }
                    }

                    // 全部 on these used to switch to the 库 tab, which reads one server where these
                    // shelves gather every server's, and whose 播放记录 has no 全部 at all. It opens
                    // the shelf itself now, the way a TMDB shelf's 全部 does.
                    if (state.resume.isNotEmpty()) {
                        motionItem(key = "continue-watching") {
                            ContinueWatching(
                                items = state.resume,
                                onSeeAll = { expandedShelf = HomeLibraryShelf.ContinueWatching },
                                onClick = openEntry,
                                onQuickActions = { quickActions = it.homeQuickActions(onIntent, openEntry) },
                            )
                        }
                    }

                    // The next episode of a show just finished was loaded all along and only used
                    // to rank the calendar, so the show left the home page with the episode.
                    if (upNext.isNotEmpty()) {
                        motionItem(key = "next-up") {
                            ContinueWatching(
                                title = HomeLibraryShelf.NextUp.title,
                                items = upNext,
                                onSeeAll = { expandedShelf = HomeLibraryShelf.NextUp },
                                onClick = openEntry,
                                onQuickActions = { quickActions = it.homeQuickActions(onIntent, openEntry) },
                            )
                        }
                    }

                    if (state.favorites.isNotEmpty()) {
                        motionItem(key = "favorites") {
                            LibraryMediaShelf(
                                title = HomeLibraryShelf.Favorites.title,
                                items = state.favorites,
                                onSeeAll = { expandedShelf = HomeLibraryShelf.Favorites },
                                onClick = openEntry,
                                onQuickActions = { quickActions = it.homeQuickActions(onIntent, openEntry) },
                            )
                        }
                    }

                    when {
                        calendarItems.isNotEmpty() -> {
                            motionItem(key = "airing-calendar-preview") {
                                HomeCalendarShelf(
                                    items = calendarItems,
                                    onSeeAll = onOpenCalendar,
                                    onClick = { onOpenCalendarEntry(it.entry) },
                                    onQuickActions = { preview ->
                                        quickActions =
                                            HomeQuickActions(
                                                title = preview.entry.episode.showTitle,
                                                actions =
                                                    listOf(
                                                        HomeQuickAction("查看详情") {
                                                            onOpenCalendarEntry(preview.entry)
                                                        },
                                                    ),
                                            )
                                    },
                                )
                            }
                        }

                        calendarState.loading -> {
                            motionItem(key = "airing-calendar-loading") {
                                SkeletonRail(
                                    modifier = Modifier.skeletonSweep().padding(horizontal = Dimens.pageHorizontal),
                                    count = 3,
                                )
                            }
                        }

                        calendarState.error != null && !recommendationsFailed -> {
                            motionItem(key = "airing-calendar-error") {
                                ErrorState(
                                    message = calendarState.error!!,
                                    onRetry = onRefreshCalendar,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    state.content.rows.forEach { row ->
                        if (row.items.isNotEmpty()) {
                            motionItem(key = "tmdb-${row.title}") {
                                Recommended(
                                    title = row.title,
                                    items = row.items,
                                    arrival = refreshArrival,
                                    showReleaseDate = row.showsReleaseDate,
                                    // Opens this shelf, not the 库 tab. These come from TMDB and
                                    // most are not in the library at all, so the old destination
                                    // showed none of what the chip had just offered.
                                    onSeeAll = { expandedRow = row },
                                    onClick = { onIntent(HomeIntent.Open(it)) },
                                    onQuickActions = { item ->
                                        quickActions =
                                            HomeQuickActions(
                                                title = item.title,
                                                actions =
                                                    listOf(
                                                        HomeQuickAction("查看详情") {
                                                            onIntent(HomeIntent.Open(item))
                                                        },
                                                        HomeQuickAction("加入收藏") {
                                                            onIntent(HomeIntent.Favorite(item))
                                                        },
                                                    ),
                                            )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floats over the page rather than sitting in it: as a list item this pushed the
        // whole feed down and then let it snap back, and it never cleared itself.
        ActionToast(
            message = state.actionMessage,
            onDismiss = { onIntent(HomeIntent.DismissMessage) },
        )

        if (state.resolving) {
            OrbProgress(modifier = Modifier.align(Alignment.Center), size = OrbProgressDefaults.Page)
        }

        quickActions?.let { sheet ->
            HomeQuickActionsSheet(sheet = sheet, onDismiss = { quickActions = null })
        }

        // Pushed and popped like a route rather than cut in and out; the reel above holds still
        // while it is up (see HomeHeroCarousel's `held`).
        OverlayPage(value = expandedRow, onBack = { expandedRow = null }) { row ->
            TmdbRowPage(
                title = row.title,
                items = row.items,
                showReleaseDate = row.showsReleaseDate,
                onOpen = {
                    onIntent(HomeIntent.Open(it))
                    expandedRow = null
                },
                onDismiss = { expandedRow = null },
            )
        }

        OverlayPage(value = expandedShelf, onBack = { expandedShelf = null }) { shelf ->
            val entries = shelfEntries(shelf)
            LibraryRowPage(
                title = shelf.title,
                caption =
                    libraryRowPageCaption(
                        source = shelf.source,
                        shown = entries.size,
                        total = if (shelf == HomeLibraryShelf.Favorites) state.favoritesTotal else entries.size,
                    ),
                entries = entries,
                onOpen = openEntry,
                onQuickActions = { quickActions = it.homeQuickActions(onIntent, openEntry) },
                onDismiss = { expandedShelf = null },
            )
        }
    }
}

/** The library-backed shelves whose 全部 opens a [LibraryRowPage]; titles and badges as on the shelf. */
private enum class HomeLibraryShelf(
    val title: String,
    val source: String,
) {
    ContinueWatching("继续观看", "Emby"),
    NextUp("下一集", "Emby"),
    Favorites("我的收藏", "媒体库"),
}

/**
 * "媒体库 · 12 项", or, when the home page holds only part of a shelf, how much it leaves out:
 * every server lends the home page its newest few favourites, not all of them.
 */
internal fun libraryRowPageCaption(
    source: String,
    shown: Int,
    total: Int,
): String = if (total > shown) "$source · 显示 $shown 项，共 $total 项" else "$source · $shown 项"

/**
 * 下一集 as its shelf shows it: one card per show, and none for a show 继续观看 already holds.
 *
 * The local next-up list names a half-watched episode as its own next one, so without this every
 * show in 继续观看 appeared twice, a shelf apart. [HomeState.nextUp] itself stays as loaded; the TV
 * home lays it out on its own terms.
 */
internal fun homeNextUpShelf(
    nextUp: List<HomeResumeEntry>,
    resume: List<HomeResumeEntry>,
): List<HomeResumeEntry> {
    val shelf = mutableListOf<HomeResumeEntry>()
    nextUp.forEach { candidate ->
        val shown = resume.any { it.isSameShowAs(candidate) } || shelf.any { it.isSameShowAs(candidate) }
        if (!shown) shelf += candidate
    }
    return shelf
}

/**
 * On one server a show is its series id. Across servers only the name can say: ids mean nothing
 * there, and an episode's provider ids name the episode rather than the show.
 */
private fun HomeResumeEntry.isSameShowAs(other: HomeResumeEntry): Boolean =
    if (server.id == other.server.id) {
        item.homeShowId() == other.item.homeShowId()
    } else {
        val title = item.title.homeCalendarIdentityTitle()
        item.isEpisodic() &&
            other.item.isEpisodic() &&
            title.isNotEmpty() &&
            title == other.item.title.homeCalendarIdentityTitle()
    }

/** An episode's show is its series, which is also whose poster it carries. */
private fun MediaItem.homeShowId(): String =
    if (type.equals("Episode", ignoreCase = true)) posterItemId.ifBlank { id } else id

private fun MediaItem.isEpisodic(): Boolean =
    type.equals("Episode", ignoreCase = true) || type.equals("Series", ignoreCase = true)

/**
 * 首屏大图 — 390px, edge to edge, starting behind the status bar (§2 首页).
 *
 * The greeting row floats on the artwork rather than sitting above it. Search remains the
 * shell's always-available destination instead of being repeated inside this header.
 */
@Composable
private fun HomeHeroCarousel(
    items: List<TmdbItem>,
    userName: String?,
    height: androidx.compose.ui.unit.Dp,
    showSidePreview: Boolean,
    visible: Boolean,
    /** Something is open over the reel — a menu, 查看全部 — so it must not turn under it. */
    held: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCalendar: () -> Unit,
    onPlay: (TmdbItem) -> Unit,
    onDetails: (TmdbItem) -> Unit,
    onFavorite: (TmdbItem) -> Unit,
    onAccent: (Color) -> Unit,
    onPageColor: (Color) -> Unit,
) {
    val pagerState = rememberLoopingCarouselState(items.map { it.id.toString() })
    val carouselTouched = remember { mutableStateOf(false) }
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    // `enabled` gates whether [rememberLightFeedback] even builds its state (see its own
    // `available` check), not just whether it may emit — so passing the carousel's `visible`
    // there rebuilt the state from scratch on every scroll start/stop. It now stays alive
    // permanently and `visible` only gates the `.emit(...)` calls below, the same way
    // [rememberLightFeedback] itself already treats route visibility as a post-build gate.
    val carouselLight = rememberLightFeedback(enhancedOnly = true)
    LaunchedEffect(carouselDragging, carouselLight, visible) {
        if (carouselDragging && visible) carouselLight.emit(LightEffect.Dust)
    }
    // Every level gets the settle: a page that has just left sweeps a gathering light along
    // the side it left from. Dust on the edges stays an 增强 detail.
    val carouselSweep = rememberLightFeedback()
    LaunchedEffect(pagerState, carouselSweep, visible) {
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            // Tracking keeps running while hidden so a page change during that time is not
            // mistaken for one later, once visible again; only the sweep itself is gated.
            if (page != previous && visible) {
                carouselSweep.emit(LightEffect.Converge, fractionX = if (page > previous) 0.04f else 0.96f)
            }
            previous = page
        }
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Touching the reel restarts its clock rather than stopping it for good. The pause
    // control this replaces could only be undone by finding it again, so a single swipe
    // left the hero permanently still with a play glyph as the only clue why.
    var interaction by remember { mutableStateOf(0) }
    val ambientItem = items.getOrNull(loopingCarouselItemIndex(pagerState.settledPage, items.size))
    val ambientUrls = remember(ambientItem) { tmdbHeroArtworkUrls(ambientItem) }

    CarouselAutoAdvance(
        pagerState = pagerState,
        pageCount = items.size,
        held = held || !visible || carouselDragging || carouselTouched.value,
        restartKey = interaction,
    )

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .carouselTouchPause(carouselTouched)
            .lightFeedback(carouselLight)
            .lightFeedback(carouselSweep),
    ) {
        val indicatorStart =
            if (showSidePreview) LivingPosterDefaults.LEADING_INSET else 0.dp
        val indicatorEnd =
            if (showSidePreview) LivingPosterDefaults.TRAILING_PEEK else 0.dp
        val artworkWidth =
            if (showSidePreview) {
                (maxWidth - LivingPosterDefaults.LEADING_INSET - LivingPosterDefaults.TRAILING_PEEK)
                    .coerceAtLeast(1.dp)
            } else {
                maxWidth
            }
        val artworkAspectRatio = artworkWidth.value / maxHeight.value.coerceAtLeast(1f)
        val artworkFadeFraction =
            (HeroPageFade.value / maxHeight.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f)
        // Full-bleed phone artwork must dissolve straight into the real page. Drawing a
        // second, blurred copy behind it made that copy show through the fade as a saturated
        // horizontal band and also decoded the first image twice. Wide layouts still need
        // the ambient layer behind their inset poster, so it shares the same dissolve.
        if (showSidePreview) {
            LivingPosterAmbient(
                urls = ambientUrls,
                modifier = Modifier.fillMaxSize().fadeIntoPage(),
            )
        }
        if (items.isEmpty()) {
            HeroSlide(
                item = null,
                onPlay = {},
                onDetails = {},
                onFavorite = {},
                artworkAspectRatio = artworkAspectRatio,
                artworkFadeFraction = artworkFadeFraction,
                modifier =
                    Modifier
                        .fillMaxSize(),
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .loopingCarouselSemantics(pagerState.currentPage, items.size),
                contentPadding =
                    if (showSidePreview) {
                        PaddingValues(
                            start = LivingPosterDefaults.LEADING_INSET,
                            end = LivingPosterDefaults.TRAILING_PEEK,
                        )
                    } else {
                        PaddingValues(0.dp)
                    },
                pageSpacing = if (showSidePreview) LivingPosterDefaults.PAGE_SPACING else 0.dp,
                beyondViewportPageCount = 1,
                key = { page -> page },
            ) { page ->
                val item = items[loopingCarouselItemIndex(page, items.size)]
                val settled = page == pagerState.settledPage
                HeroSlide(
                    item = item,
                    onPlay = { onPlay(item) },
                    onDetails = { onDetails(item) },
                    onFavorite = { onFavorite(item) },
                    settled = settled,
                    pageOffset = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction },
                    onAccent = onAccent,
                    onPageColor = onPageColor,
                    artworkAspectRatio = artworkAspectRatio,
                    artworkFadeFraction = artworkFadeFraction,
                    framed = showSidePreview,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val visual =
                                    carouselPageVisual(
                                        signedPageOffset =
                                            (pagerState.currentPage - page) +
                                                pagerState.currentPageOffsetFraction,
                                        reduceMotion = reduceMotion,
                                        preservePreviewEdge = showSidePreview,
                                    )
                                scaleX = visual.scale
                                scaleY = visual.scale
                                alpha = visual.alpha
                                translationX = size.width * visual.parallaxFraction
                            },
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(HomeStatusBarScrimHeight)
                .align(Alignment.TopCenter)
                .background(HomeStatusBarScrim),
        )

        HeroHeader(
            userName = userName,
            refreshing = refreshing,
            onRefresh = onRefresh,
            onOpenProfile = onOpenProfile,
            onOpenCalendar = onOpenCalendar,
            modifier = Modifier.align(Alignment.TopStart),
        )

        if (items.size > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = indicatorStart,
                        end = indicatorEnd,
                        bottom = LivingPosterDefaults.INDICATOR_BOTTOM,
                    ),
            ) {
                HeroPageIndicator(
                    pageCount = items.size,
                    selectedPage = loopingCarouselItemIndex(pagerState.currentPage, items.size),
                    pageOffsetProvider = { pagerState.currentPageOffsetFraction },
                    onPageSelected = { targetIndex ->
                        if (targetIndex != loopingCarouselItemIndex(pagerState.currentPage, items.size)) {
                            carouselLight.emit(LightEffect.Dust)
                        }
                        interaction++
                        carouselScope.launch {
                            val targetPage =
                                loopingCarouselTargetPage(
                                    currentPage = pagerState.currentPage,
                                    targetIndex = targetIndex,
                                    itemCount = items.size,
                                )
                            if (reduceMotion) {
                                pagerState.scrollToPage(targetPage)
                            } else {
                                pagerState.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(Motion.EMPHASIZED, easing = Motion.Curve),
                                )
                            }
                        }
                    },
                    onArtwork = false,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private fun tmdbHeroArtworkUrls(item: TmdbItem?): List<String?> =
    if (item == null) {
        emptyList()
    } else {
        listOf(
            TmdbImages.backdrop(item.backdropPath),
            TmdbImages.media(item.backdropPath, "w1280"),
            TmdbImages.poster(item.posterPath, "w780"),
            TmdbImages.media(item.posterPath, "w780"),
        )
    }

@Composable
private fun HeroSlide(
    item: TmdbItem?,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
    settled: Boolean = false,
    pageOffset: () -> Float = { 0f },
    onAccent: (Color) -> Unit = {},
    onPageColor: (Color) -> Unit = {},
    artworkAspectRatio: Float,
    artworkFadeFraction: Float,
    framed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val playerArtworkKey = item?.let { MediaSharedElementKey(null, "tmdb:${it.id}") }
    val artworkUrls: List<String?> =
        remember(item) { tmdbHeroArtworkUrls(item) }
    var resolvedArtworkUrl by remember(item?.id) { mutableStateOf<String?>(null) }
    val artworkAccent =
        rememberArtworkAccentTarget(
            url = artworkUrls.firstOrNull { it != null },
            fallback = Brand.Primary, // design-system: brand-identity
            darkTheme = palette.isDark,
            identity = item?.id,
        )
    val artworkPageColor =
        rememberArtworkPageColor(
            url = resolvedArtworkUrl,
            targetAspectRatio = artworkAspectRatio,
            fadeFraction = artworkFadeFraction,
        )
    // Only the slide the reader is actually on gets to colour the page; the pager keeps its
    // neighbours composed, and letting those report would tint the page from a slide that is
    // off screen.
    LaunchedEffect(settled, artworkAccent, artworkPageColor) {
        if (settled) {
            onAccent(artworkAccent)
            artworkPageColor?.let(onPageColor)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .then(if (framed) Modifier.livingPosterFrame() else Modifier)
            .then(
                if (item == null) {
                    Modifier
                } else {
                    // The CTA and info key already opened this title, but the artwork — the
                    // largest and most obvious target on the screen — did nothing. Keep its
                    // bounds still so a tap does not fight the pager's drag animation.
                    Modifier.pressable(
                        pressedScale = 1f,
                        onClickLabel = "查看${item.title}",
                        onClick = onDetails,
                    )
                },
            ),
    ) {
        if (item != null) {
            Box(Modifier.fillMaxSize().fadeIntoPage().clipToBounds()) {
                FallbackImage(
                    urls = artworkUrls,
                    contentDescription = item.title,
                    alphaOnly = false,
                    onResolvedUrl = { resolvedArtworkUrl = it },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .playerArtworkSource(playerArtworkKey, artworkUrls)
                            .carouselArtworkMotion(pageOffset, LocalAccessibilityOptions.current.reduceMotion),
                )
            }
        }
        // Contrast only. The image itself owns the lower transition through fadeIntoPage().
        Box(Modifier.fillMaxSize().background(heroTopScrim()))

        if (item != null) {
            HeroCaption(
                item = item,
                selected = settled,
                onPlay = playerArtworkOnClick(playerArtworkKey, onPlay),
                onDetails = onDetails,
                onFavorite = onFavorite,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * Header row over the hero — `gap:10px`; left cluster `gap:9px` with the 30px mark,
 * `下午好` at `400 11px Manrope`, `继续你的旅程` at `800 17px`; calendar + avatar.
 * Text is white here because it sits on the darkened artwork, not the page.
 */
@Composable
private fun HeroHeader(
    userName: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when there is no artwork under it: white on the bare page is unreadable in light. */
    onArtwork: Boolean = true,
) {
    val palette = LocalPalette.current
    val shadow = HeroTextShadow.takeIf { onArtwork }
    val ink = if (onArtwork) Color.White else palette.text
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = Dimens.contentTop, start = Dimens.pageHorizontal, end = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMark(Modifier.size(30.dp))
            Column {
                Text(
                    homeGreeting(currentHourOfDay()),
                    style = AppTypography.caption.regular.copy(shadow = shadow),
                    color = if (onArtwork) Color.White.copy(alpha = 0.82f) else palette.sub2,
                )
                Text(
                    "继续你的旅程",
                    style = AppTypography.section.strong.copy(shadow = shadow),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The page's title: where TalkBack lands first, and so where 下拉刷新 lives
                    // for someone who cannot pull.
                    modifier = Modifier.refreshAction(enabled = !refreshing, onRefresh = onRefresh),
                )
            }
        }
        Box(
            Modifier.size(48.dp).pressable(onClick = onOpenCalendar),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .glass(
                        shape = CircleShape,
                        fill = if (onArtwork) Color.White.copy(alpha = 0.14f) else palette.card2,
                        border = if (onArtwork) Color.White.copy(alpha = 0.34f) else palette.border,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.WatchCalendar,
                    "追剧日历",
                    tint = ink,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .pressable(onClickLabel = "打开个人中心", onClick = onOpenProfile)
                .semantics {
                    contentDescription = userName
                        ?.takeIf(String::isNotBlank)
                        ?.let { "个人中心，$it" }
                        ?: "个人中心"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(PrimaryGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    homeUserInitial(userName),
                    style = AppTypography.body.strong,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Hero caption — ✦今日精选 badge, Display 片名, 类型 · 年份, then the action row:
 * 主按钮「播放」+ 次级详情/收藏。TMDB picks are resolved only after the tap, so promising
 * immediate playback here was inaccurate whenever the title was not in the user's library.
 */
@Composable
private fun HeroCaption(
    item: TmdbItem,
    selected: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val captionProgress = rememberCarouselCaptionProgress(selected)
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = HomeHeroContentBottom,
            ),
    ) {
        Text(
            "TMDB · 今日精选",
            style = AppTypography.caption.medium,
            color = Color.White,
            modifier =
                Modifier
                    .carouselCaptionEntry(captionProgress, stage = 0)
                    .glass(
                        shape = AppShapes.chip,
                        fill = Color.White.copy(alpha = 0.14f),
                        border = Color.White.copy(alpha = 0.30f),
                    ).padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 0),
            style = AppTypography.display.strong.copy(shadow = HeroTextShadow),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        // Rating stays visually distinct; the decision facts follow the reference order:
        // year · type · duration. Runtime is detail-backed and disappears only when TMDB
        // did not provide one.
        Row(
            modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 1),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.rating
                ?.takeIf { it > 0.0 }
                ?.let { rating ->
                    Icon(
                        AppIcons.StarFilled,
                        contentDescription = "评分",
                        tint = Brand.Imdb,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        ((rating * 10).toInt() / 10.0).toString(),
                        style = AppTypography.caption.strong.copy(shadow = HeroTextShadow),
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            val facts =
                listOfNotNull(
                    item.year,
                    heroMediaTypeLabel(item.mediaType),
                    heroDurationLabel(item.runtimeMinutes),
                )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = AppTypography.caption.regular.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        item.overview
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { overview ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = overview,
                    modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 1),
                    style = AppTypography.caption.regular.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.80f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        Spacer(Modifier.height(14.dp))
        HeroActionDock(
            modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 2),
            onPlay = onPlay,
            onFavorite = onFavorite,
        )
    }
}

/** The prototype's literal 下午好 read wrong at breakfast; the greeting follows the clock. */
internal fun homeGreeting(hourOfDay: Int): String =
    when (hourOfDay.coerceIn(0, 23)) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

internal fun homeUserInitial(userName: String?): String =
    userName
        ?.trim()
        ?.firstOrNull(Char::isLetterOrDigit)
        ?.uppercaseChar()
        ?.toString()
        ?: "访"

@Composable
private fun HomeSourceBadge(source: String) {
    val accent = LocalAccentColors.current
    Text(
        source,
        style = AppTypography.caption.strong,
        color = accent.accent,
        modifier =
            Modifier
                .glass(
                    shape = AppShapes.chip,
                    fill = accent.container,
                    border = accent.border,
                ).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * What a long press on a 首页 poster offers.
 *
 * 媒体库 has answered this gesture since its grid was written — hold a poster and the handful
 * of things people do to a title without opening it appear. The rails here show the same
 * posters and answered nothing, so the gesture was learned in one place and silently absent in
 * the other. The rows differ by rail because 首页's store does: a shelf built from the media
 * library can only be opened from here, while a TMDB pick can also be resolved and favourited.
 */
private data class HomeQuickAction(
    val label: String,
    val onSelect: () -> Unit,
)

private data class HomeQuickActions(
    val title: String,
    val actions: List<HomeQuickAction>,
)

/**
 * Library-backed shelves. 查看详情 alone was only the tap again; the watched flag is the one thing
 * these shelves can change about a title without opening it, through the same writes the detail
 * page's 标记已看 makes.
 */
private fun HomeResumeEntry.homeQuickActions(
    onIntent: (HomeIntent) -> Unit,
    onOpen: (HomeResumeEntry) -> Unit,
): HomeQuickActions {
    val entry = this
    val item = entry.item
    return HomeQuickActions(
        // An episode goes by its show's name; the sheet has to say which episode it would mark.
        title =
            if (item.type.equals("Episode", ignoreCase = true)) {
                listOfNotNull(item.title, item.subtitle).joinToString(" · ")
            } else {
                item.title
            },
        actions =
            listOf(
                HomeQuickAction("查看详情") { onOpen(entry) },
                if (item.played) {
                    HomeQuickAction("标记未看") { onIntent(HomeIntent.SetPlayed(entry, false)) }
                } else {
                    HomeQuickAction("标记已看") { onIntent(HomeIntent.SetPlayed(entry, true)) }
                },
            ),
    )
}

@Composable
private fun HomeQuickActionsSheet(
    sheet: HomeQuickActions,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = sheet.title, onClose = onDismiss)
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            sheet.actions.forEach { action ->
                OverlayActionRow(
                    label = action.label,
                    // The sheet leaves the way it came before the action takes over the page.
                    onClick =
                        overlayAction {
                            onDismiss()
                            action.onSelect()
                        },
                )
            }
        }
    }
}

/** 继续观看 — Forward-style still, exact resume position and progress at first glance. */
@Composable
private fun ContinueWatching(
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
    onQuickActions: (HomeResumeEntry) -> Unit,
    /** 下一集 is the same rail of stills; only its title, and what each card announces, differ. */
    title: String = "继续观看",
) {
    Column {
        HomeShelfHeader(title = title, source = "Emby", onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            motionItems(items, key = { "${it.server.id}:${it.item.id}" }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    shelfTitle = title,
                    onClick = { onClick(entry) },
                    onLongClick = { onQuickActions(entry) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: HomeResumeEntry,
    shelfTitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val item = entry.item
    Column(modifier = Modifier.width(MediaSizing.landscapeCardWidth)) {
        Poster(
            url =
                EmbyImages.backdrop(
                    entry.server.baseUrl,
                    item,
                    maxWidth = 480,
                    accessToken = entry.server.accessToken,
                ),
            fallbackUrl =
                EmbyImages.poster(
                    entry.server.baseUrl,
                    item,
                    accessToken = entry.server.accessToken,
                ),
            rating = item.communityRating,
            progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
            contentDescription = "$shelfTitle ${item.title}${item.subtitle?.let { "，$it" }.orEmpty()}",
            onClick = onClick,
            onLongClick = onLongClick,
            sharedTransitionKey = MediaSharedElementKey(entry.server.id, item.id),
            modifier = Modifier.fillMaxWidth().height(MediaSizing.landscapeCardHeight),
        ) {
            resumePositionLabel(item.resumePositionTicks)?.let { position ->
                Text(
                    text = "看到 $position",
                    style = AppTypography.caption.strong.copy(shadow = HeroTextShadow),
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 9.dp, bottom = 9.dp)
                            .background(Color.Black.copy(alpha = 0.48f), AppShapes.chip)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = item.title,
            style = AppTypography.body.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                listOfNotNull(
                    item.subtitle,
                    compactLastPlayedDate(item.lastPlayedDate),
                    entry.server.serverName.takeIf(String::isNotBlank),
                ).joinToString(" · ").ifBlank { "Emby" },
            style = AppTypography.caption.regular,
            color = palette.sub2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun resumePositionLabel(positionTicks: Long?): String? {
    val totalSeconds = positionTicks?.takeIf { it > 0L }?.div(10_000_000L) ?: return null
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun compactLastPlayedDate(value: String?): String? {
    val date = value?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
    return date.substring(5).replace('-', '/')
}

@Composable
private fun LibraryMediaShelf(
    title: String,
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
    onQuickActions: (HomeResumeEntry) -> Unit,
) {
    Column {
        HomeShelfHeader(title = title, source = "媒体库", onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItems(items.take(12), key = { "library-${it.server.id}-${it.item.id}" }) { entry ->
                val item = entry.item
                CaptionedPoster(
                    url =
                        EmbyImages.poster(
                            entry.server.baseUrl,
                            item,
                            accessToken = entry.server.accessToken,
                        ),
                    title = item.title,
                    rating = item.communityRating,
                    year =
                        listOfNotNull(
                            item.year?.toString(),
                            entry.server.serverName.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                    onClick = { onClick(entry) },
                    onLongClick = { onQuickActions(entry) },
                    modifier = Modifier.width(MediaSizing.posterRailWidth),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
        }
    }
}

/**
 * Shared by every home shelf that carries a source badge next to its title (继续观看, 追剧日历,
 * 媒体库, 为你推荐) — they had drifted into four inline copies of the same row, one of which
 * (this one) baked its chevron into the "全部 ›" string instead of drawing it.
 */
@Composable
private fun HomeShelfHeader(
    title: String,
    source: String,
    onSeeAll: () -> Unit,
    onSeeAllLabel: String? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTypography.section.strong, color = palette.text)
            HomeSourceBadge(source)
        }
        Row(
            Modifier
                .pressable(onClickLabel = onSeeAllLabel, onClick = onSeeAll)
                .touchTarget()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部", style = AppTypography.caption.medium, color = palette.sub2)
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = palette.hint,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

private data class HomeCalendarPreview(
    val entry: CalendarEntry,
    val episodeLabel: String,
    val priorityLabel: String,
)

/** User-owned calendar rows, ordered ahead of discovery: watching, favourites, then next-up. */
private fun homeCalendarPreviews(
    days: List<com.yfuse.core.model.CalendarDay>,
    home: HomeState,
): List<HomeCalendarPreview> {
    fun tmdbIds(entries: List<HomeResumeEntry>): Set<Int> =
        entries
            .mapNotNull { entry ->
                entry.item.providerIds.entries
                    .firstOrNull { it.key.equals("tmdb", ignoreCase = true) }
                    ?.value
                    ?.toIntOrNull()
            }.toSet()

    fun titles(entries: List<HomeResumeEntry>): Set<String> =
        entries.map { it.item.title.homeCalendarIdentityTitle() }.filter(String::isNotBlank).toSet()

    val favoriteIds = tmdbIds(home.favorites)
    val nextIds = tmdbIds(home.nextUp)
    val resumeIds = tmdbIds(home.resume)
    val favoriteTitles = titles(home.favorites)
    val nextTitles = titles(home.nextUp)
    val resumeTitles = titles(home.resume)

    fun matches(
        entry: CalendarEntry,
        ids: Set<Int>,
        normalizedTitles: Set<String>,
    ): Boolean =
        entry.episode.showTmdbId
            .takeIf { it > 0 }
            ?.let(ids::contains) == true ||
            entry.episode.showTitle.homeCalendarIdentityTitle() in normalizedTitles

    return days
        .flatMap { it.entries }
        .groupBy { entry ->
            entry.episode.showTmdbId
                .takeIf { it > 0 }
                ?.let { "tmdb:$it" }
                ?: "title:${entry.episode.showTitle.homeCalendarIdentityTitle()}"
        }.mapNotNull { (_, showEntries) ->
            val ranked =
                showEntries.sortedWith(
                    compareBy<CalendarEntry> { entry ->
                        when {
                            entry.status == LibraryStatus.InProgress || matches(entry, resumeIds, resumeTitles) -> 0
                            matches(entry, favoriteIds, favoriteTitles) -> 1
                            matches(entry, nextIds, nextTitles) -> 2
                            entry.status == LibraryStatus.Available -> 3
                            entry.status == LibraryStatus.Missing -> 4
                            entry.status == LibraryStatus.Unaired -> 5
                            else -> 6
                        }
                    }.thenBy { entry ->
                        kotlin.math.abs(
                            com.yfuse.core.util
                                .daysBetweenIso(home.today, entry.episode.airDate),
                        )
                    },
                )
            val representative = ranked.firstOrNull() ?: return@mapNotNull null
            val sameDrop =
                showEntries
                    .filter { it.episode.airDate == representative.episode.airDate }
                    .sortedBy { it.episode.episodeNumber }
            val numbers = sameDrop.map { it.episode.episodeNumber }
            val episodeLabel =
                when {
                    representative.episode.isMovie -> "电影上映"
                    numbers.size <= 1 -> representative.episode.episodeLabel
                    numbers.zipWithNext().all { (a, b) -> b == a + 1 } -> "第 ${numbers.first()}～${numbers.last()} 集"
                    else -> numbers.joinToString("、", prefix = "第 ", postfix = " 集")
                }
            val reason =
                when {
                    representative.status == LibraryStatus.InProgress ||
                        matches(representative, resumeIds, resumeTitles) -> "正在观看"
                    matches(representative, favoriteIds, favoriteTitles) -> "我的收藏"
                    matches(representative, nextIds, nextTitles) -> "下一集"
                    representative.status == LibraryStatus.Available -> "可播放"
                    representative.status == LibraryStatus.Missing -> "等待入库"
                    representative.status == LibraryStatus.Unaired -> "即将更新"
                    else -> "正在追"
                }
            HomeCalendarPreview(representative, episodeLabel, reason)
        }.sortedWith(
            compareBy<HomeCalendarPreview> {
                when (it.priorityLabel) {
                    "正在观看" -> 0
                    "我的收藏" -> 1
                    "下一集" -> 2
                    "可播放" -> 3
                    "等待入库" -> 4
                    else -> 5
                }
            }.thenBy { it.entry.episode.airDate },
        ).take(12)
}

private fun String.homeCalendarIdentityTitle(): String = lowercase().filter(Char::isLetterOrDigit)

/** Same card geometry and spacing as 热门; only the data/status line comes from the calendar. */
@Composable
private fun HomeCalendarShelf(
    items: List<HomeCalendarPreview>,
    onSeeAll: () -> Unit,
    onClick: (HomeCalendarPreview) -> Unit,
    onQuickActions: (HomeCalendarPreview) -> Unit,
) {
    Column {
        HomeShelfHeader(title = "追剧日历", source = "日历", onSeeAll = onSeeAll, onSeeAllLabel = "打开追剧中心")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItems(
                items,
                key = { preview ->
                    val episode = preview.entry.episode
                    val showKey =
                        episode.showTmdbId
                            .takeIf { it > 0 }
                            ?.let { "tmdb:$it" }
                            ?: "title:${episode.showTitle.homeCalendarIdentityTitle()}"
                    "calendar:$showKey"
                },
            ) { preview ->
                val entry = preview.entry
                CaptionedPoster(
                    url = TmdbImages.poster(entry.episode.posterPath),
                    fallbackUrls =
                        listOfNotNull(
                            TmdbImages.media(entry.episode.posterPath),
                            TmdbImages.poster(entry.episode.posterPath, "original"),
                        ) + entry.posterUrls,
                    title = entry.episode.showTitle,
                    year = "${preview.priorityLabel} · ${preview.episodeLabel}",
                    onClick = { onClick(preview) },
                    onLongClick = { onQuickActions(preview) },
                    modifier = Modifier.width(MediaSizing.posterRailWidth),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
        }
    }
}

/**
 * Which posters each TMDB shelf shows, in order: all a pull-to-refresh can visibly change there.
 * Equal before and after a refresh that failed or brought back the same picks.
 */
internal fun homeShelfRevision(rows: List<TmdbRow>): List<List<String>> =
    rows.map { row ->
        listOf(row.title) + row.items.take(HOME_SHELF_POSTERS).map { "${it.mediaType}:${it.id}" }
    }

/** 为你推荐 — horizontal 2:3 rail; the next card remains visible as a scroll cue. */
@Composable
private fun Recommended(
    title: String,
    items: List<TmdbItem>,
    /** The page's refresh clock: after a refresh the new posters rise in from the left. */
    arrival: ArrivalMotion,
    showReleaseDate: Boolean,
    onSeeAll: () -> Unit,
    onClick: (TmdbItem) -> Unit,
    onQuickActions: (TmdbItem) -> Unit,
) {
    Column {
        HomeShelfHeader(title = title, source = "TMDB", onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItemsIndexed(
                items.take(HOME_SHELF_POSTERS),
                key = { _, it -> "${it.mediaType}:${it.id}" },
            ) { index, item ->
                CaptionedPoster(
                    url = TmdbImages.poster(item.posterPath),
                    fallbackUrls =
                        listOfNotNull(
                            TmdbImages.media(item.posterPath),
                            TmdbImages.poster(item.posterPath, "original"),
                            TmdbImages.media(item.posterPath, "original"),
                            TmdbImages.backdrop(item.backdropPath, "w780"),
                            TmdbImages.media(item.backdropPath, "w780"),
                            TmdbImages.backdrop(item.backdropPath, "original"),
                            TmdbImages.media(item.backdropPath, "original"),
                        ),
                    title = item.title,
                    rating = item.rating,
                    year =
                        "TMDB · " +
                            if (showReleaseDate) {
                                item.releaseDate?.let { "上映 $it" } ?: "上映日期待定"
                            } else {
                                item.year ?: "年份未知"
                            },
                    // The same title can appear in 热门 and 正在上映 at once.
                    // A shared-element key must be unique within a screen, so
                    // shelf posters use the route fade instead of competing for
                    // one shared element (which made the duplicate turn blank).
                    onClick = { onClick(item) },
                    onLongClick = { onQuickActions(item) },
                    modifier = Modifier.width(MediaSizing.posterRailWidth).then(arrival.item(index)),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
        }
    }
}

/**
 * The app mark, sized to the prototype's 30px header slot.
 *
 * The mark is now the shape alone on transparency, so there is nothing to mask: the
 * rounded clip that used to be here existed because the artwork was a square white tile,
 * and clipping a transparent ribbon only risks shaving its corners off.
 */
@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    CloudPlayerLogo(modifier)
}
