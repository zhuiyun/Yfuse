package com.yfuse.feature.library

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.data.FAVORITES_COLLECTION_ID
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkPageTheme
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.CarouselAutoAdvance
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HeroActionDock
import com.yfuse.core.designsystem.HeroPageFade
import com.yfuse.core.designsystem.HeroPageIndicator
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.LaunchWaveState
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LivingPosterAmbient
import com.yfuse.core.designsystem.LivingPosterDefaults
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalLaunchWave
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.LocalSkeletonArrival
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.MediaSizing
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.MotionSwap
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.RefreshIndicator
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SectionHeader
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SkeletonArrivalScope
import com.yfuse.core.designsystem.SkeletonBlock
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
import com.yfuse.core.designsystem.launchWaveImage
import com.yfuse.core.designsystem.launchWaveInterrupt
import com.yfuse.core.designsystem.launchWaveItem
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.livingPosterFrame
import com.yfuse.core.designsystem.livingPosterHeroHeight
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselSemantics
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.mediaLazyItemKey
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
import com.yfuse.core.designsystem.rememberArtworkPagePalette
import com.yfuse.core.designsystem.rememberCarouselCaptionProgress
import com.yfuse.core.designsystem.rememberCarouselPageColor
import com.yfuse.core.designsystem.rememberLaunchWave
import com.yfuse.core.designsystem.rememberLightFeedback
import com.yfuse.core.designsystem.rememberLoopingCarouselState
import com.yfuse.core.designsystem.rememberRefreshReveal
import com.yfuse.core.designsystem.rememberRetainedArtworkPageColor
import com.yfuse.core.designsystem.rememberScrolledPastHero
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.serverBadgeColor
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.skeletonSweep
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The caption clears the whole dissolve band — white copy cannot follow the artwork into
 * the page. Only the dots, whose ink is the page's, sit inside it.
 */
private val LibraryHeroContentBottom = LivingPosterDefaults.CAPTION_BOTTOM

/** How far the content column is pulled up over the lower edge of the hero. */
private val HeroLift = 52.dp

internal data class LibraryHeroPresentation(
    val playActionLabel: String,
)

internal val libraryHeroPresentation =
    LibraryHeroPresentation(
        playActionLabel = "播放影片",
    )

internal fun libraryHeroHeight(
    viewportHeight: androidx.compose.ui.unit.Dp,
    wideLayout: Boolean,
): androidx.compose.ui.unit.Dp = livingPosterHeroHeight(viewportHeight, wideLayout)

/** Poster rail column width, shared by the real rails and the loading skeleton. */
private val PosterWidth = MediaSizing.posterRailWidth

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val CLOCK_SKEW_GRACE_MS = 5 * MINUTE_MS

/**
 * Stable freshness copy with no clock or locale dependency, so boundary behavior is testable.
 * Older snapshots use an explicit UTC date rather than a device-locale string that can change
 * between recompositions.
 */
internal fun formatLibraryUpdatedAt(
    updatedAtEpochMs: Long?,
    nowEpochMs: Long,
): String {
    val updatedAt = updatedAtEpochMs?.takeIf { it > 0L } ?: return "时间未知"
    val ageMs = nowEpochMs - updatedAt
    return when {
        ageMs in -CLOCK_SKEW_GRACE_MS until MINUTE_MS -> "刚刚"
        ageMs in MINUTE_MS until HOUR_MS -> "${ageMs / MINUTE_MS} 分钟前"
        ageMs in HOUR_MS until DAY_MS -> "${ageMs / HOUR_MS} 小时前"
        else -> "${utcDate(updatedAt)} UTC"
    }
}

/** Gregorian civil date conversion for a non-negative Unix timestamp. */
private fun utcDate(epochMs: Long): String {
    var days = epochMs / DAY_MS + 719_468L
    val era = days / 146_097L
    val dayOfEra = days - era * 146_097L
    val yearOfEra =
        (
            dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L
        ) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    if (month <= 2L) year++
    return buildString {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
    }
}

/** 媒体库 — a viewport-aware hero carousel above the library rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(component: LibraryHomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val access by component.access.collectAsState()
    val serverHealth = component.serverHealth.collectAsState()
    val currentServerId = state.currentServer?.id
    // Derived, so a probe of some other server does not recompose the page.
    val sessionRefused by remember(currentServerId) {
        derivedStateOf {
            currentServerId != null &&
                serverHealth.value[currentServerId]?.status == ServerHealthStatus.AuthRequired
        }
    }
    // A refused session fails every request the page makes: what it needs is a new sign-in, not
    // another try. A child profile cannot sign in again, so retrying is all it is offered.
    val signInServer = state.currentServer?.takeIf { sessionRefused && access.canManageServers }
    val recover = {
        if (signInServer != null) {
            component.onReauthenticate(signInServer)
        } else {
            component.store.accept(LibraryIntent.Retry)
        }
    }
    val libraryCarousel by component.themePreferences.libraryCarousel.collectAsState()
    val store = component.store
    val baseUrl = state.currentServer?.baseUrl.orEmpty()
    // Image endpoints answer 401 without it on a server that requires authentication, so
    // the token travels with the base URL to every artwork on this page — not just to
    // 播放记录, which was the only row that had it.
    val accessToken = state.currentServer?.accessToken.orEmpty()

    val slides = state.content.featured.take(8)
    val pagerState = rememberLoopingCarouselState(slides.map { it.id })
    val carouselTouched = remember { mutableStateOf(false) }
    val slideIndex = loopingCarouselItemIndex(pagerState.settledPage, slides.size)
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    val carouselLight = rememberLightFeedback(enhancedOnly = true)
    LaunchedEffect(carouselDragging, carouselLight) {
        if (carouselDragging) carouselLight.emit(LightEffect.Dust)
    }
    // Same settle sweep as 首页's hero; see there.
    val carouselSweep = rememberLightFeedback()
    LaunchedEffect(pagerState, carouselSweep) {
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != previous) {
                carouselSweep.emit(LightEffect.Converge, fractionX = if (page > previous) 0.04f else 0.96f)
                previous = page
            }
        }
    }
    // Interaction restarts the reel's clock instead of stopping it; see 首页's hero.
    var interaction by remember { mutableStateOf(0) }
    val slide = slides.getOrNull(slideIndex)
    val slideUrls =
        slide
            ?.let {
                listOf(
                    EmbyImages.backdrop(baseUrl, it, accessToken = accessToken),
                    EmbyImages.poster(baseUrl, it, accessToken = accessToken),
                )
            }.orEmpty()
    val slideUrl = slideUrls.firstOrNull { it != null }
    val retainedPageColor =
        rememberRetainedArtworkPageColor("library:${state.currentServer?.id.orEmpty()}")
    val sampledPageColor = rememberCarouselPageColor(retainedPageColor.value)
    val showSmartPlaylists =
        com.yfuse.feature.search
            .hasPinnedSmartPlaylists()
    val palette = rememberArtworkPagePalette(retainedPageColor.value)
    val accent =
        rememberArtworkAccentTarget(
            url = slideUrl,
            fallback = Brand.Primary, // design-system: brand-identity
            darkTheme = palette.isDark,
            identity = slide?.id,
        )

    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = state.refreshing)
    // The sweep says new content arrived; a refresh that failed (the banner says so) or brought
    // back the same library has nothing new to sweep over.
    val contentRevision = remember(state.content) { libraryRefreshRevision(state.content) }
    val refreshArrival = rememberRefreshReveal(state.refreshing, revision = contentRevision)

    var serverMenuOpen by remember { mutableStateOf(false) }
    val listState = component.listState
    val density = LocalDensity.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    // 「水火潮涌」: armed by a cold start that opens on 库, played once when content is first on screen.
    val launchWave = rememberLaunchWave(contentVisible = routeVisible && !state.content.isEmpty)
    var freshnessNowEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.contentSource, state.updatedAtEpochMs, routeVisible) {
        freshnessNowEpochMs = System.currentTimeMillis()
        if (state.contentSource != LibraryContentSource.Cached || !routeVisible) {
            return@LaunchedEffect
        }
        while (true) {
            delay(MINUTE_MS)
            freshnessNowEpochMs = System.currentTimeMillis()
        }
    }
    val carouselVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress }
    }
    CarouselAutoAdvance(
        pagerState = pagerState,
        pageCount = slides.size,
        held = !libraryCarousel || !carouselVisible || serverMenuOpen || carouselDragging || carouselTouched.value,
        restartKey = interaction,
    )
    ScrollToTopOnReselect(listState)

    val bottomContentInset = floatingNavigationContentInset()
    ArtworkPageTheme(
        background = retainedPageColor.value,
        artworkAccent = accent,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = libraryHeroHeight(maxHeight, wideLayout = maxWidth >= 600.dp)
            // The reel is switched on, but the first load has not brought anything to show in it.
            val heroPending = libraryCarousel && state.loading && state.content.isEmpty
            Box(Modifier.fillMaxSize().drawBehind { drawRect(sampledPageColor.value) })
            val lightPageReached by rememberScrolledPastHero(listState, heroHeight)
            val showSidePreview = maxWidth >= 600.dp || maxWidth > maxHeight
            val artworkWidth =
                if (showSidePreview) {
                    (maxWidth - LivingPosterDefaults.LEADING_INSET - LivingPosterDefaults.TRAILING_PEEK)
                        .coerceAtLeast(1.dp)
                } else {
                    maxWidth
                }
            val artworkAspectRatio = artworkWidth.value / heroHeight.value.coerceAtLeast(1f)
            val artworkFadeFraction =
                (HeroPageFade.value / heroHeight.value.coerceAtLeast(1f)).coerceIn(0.02f, 1f)
            val indicatorStart =
                if (showSidePreview) LivingPosterDefaults.LEADING_INSET else 0.dp
            val indicatorEnd =
                if (showSidePreview) LivingPosterDefaults.TRAILING_PEEK else 0.dp
            StatusBarIconStyle(darkIcons = (!libraryCarousel || slide == null || lightPageReached) && !palette.isDark)
            when {
                state.currentServer == null && access.canManageServers ->
                    PageHint(
                        "还没有可用的服务器，添加一台就能在这里浏览媒体库",
                        Modifier.align(Alignment.Center),
                        actionLabel = "添加服务器",
                        onAction = component.onAddServer,
                    )

                state.currentServer == null ->
                    PageHint(
                        "当前资料没有可用服务器，请由家长关联",
                        Modifier.align(Alignment.Center),
                        // A child profile cannot add one itself; a refresh picks up a server a
                        // parent has linked since, without a restart.
                        actionLabel = "重试",
                        onAction = { store.accept(LibraryIntent.Retry) },
                    )

                state.error != null && state.content.isEmpty ->
                    ErrorState(
                        message = state.error!!,
                        onRetry = recover,
                        modifier = Modifier.align(Alignment.Center),
                        retryLabel = if (signInServer != null) "重新登录" else "重试",
                    )

                else ->
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = { store.accept(LibraryIntent.Retry) },
                        state = pullState,
                        indicator = {
                            RefreshIndicator(
                                pullState,
                                state.refreshing,
                                Modifier.align(Alignment.TopCenter),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LibraryArrivalScope(state.loading && state.content.isEmpty, launchWave) {
                            LazyColumn(
                                // One page-wide sweep over the loading shelves, while any is loading.
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .skeletonSweep()
                                        .arrivalSweep(refreshArrival)
                                        .launchWaveInterrupt(launchWave),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                                contentPadding = PaddingValues(bottom = bottomContentInset),
                            ) {
                                if (!libraryCarousel || slide == null) {
                                    waveItem(key = "library-header") {
                                        // While the first load runs with the reel switched on, the
                                        // header holds the reel's height: the rows under it start
                                        // where they will stay, instead of being pushed down a whole
                                        // hero when the reel lands on top of them.
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .then(if (heroPending) Modifier.height(heroHeight) else Modifier),
                                        ) {
                                            if (heroPending) {
                                                SkeletonBlock(
                                                    Modifier.matchParentSize().fadeIntoPage(),
                                                    shape = RectangleShape,
                                                )
                                            }
                                            Column(
                                                Modifier.fillMaxWidth().statusBarsPadding().padding(
                                                    horizontal = Dimens.pageHorizontal,
                                                    vertical = 12.dp,
                                                ),
                                            ) {
                                                SettingRow(
                                                    title = "媒体库",
                                                    value = state.currentServer?.serverName.orEmpty(),
                                                    icon = AppIcons.Server,
                                                    onClick = { serverMenuOpen = true },
                                                    // Without the reel this row is the page's title:
                                                    // 下拉刷新 lives here for someone who cannot pull.
                                                    modifier =
                                                        Modifier.refreshAction(enabled = !state.refreshing) {
                                                            store.accept(LibraryIntent.Retry)
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (libraryCarousel && slide != null) {
                                    motionItem {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(
                                                    heroHeight,
                                                ).heroScrollCollapse(
                                                    listState,
                                                    heroHeight,
                                                ).carouselTouchPause(carouselTouched)
                                                .lightFeedback(carouselLight)
                                                .lightFeedback(carouselSweep),
                                        ) {
                                            // No second full-bleed copy on phones: it would show through the dissolve.
                                            if (showSidePreview) {
                                                LivingPosterAmbient(
                                                    urls = slideUrls,
                                                    modifier = Modifier.fillMaxSize().fadeIntoPage(),
                                                )
                                            }
                                            HorizontalPager(
                                                state = pagerState,
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .loopingCarouselSemantics(pagerState.currentPage, slides.size),
                                                contentPadding =
                                                    if (showSidePreview) {
                                                        PaddingValues(
                                                            start = LivingPosterDefaults.LEADING_INSET,
                                                            end = LivingPosterDefaults.TRAILING_PEEK,
                                                        )
                                                    } else {
                                                        PaddingValues(0.dp)
                                                    },
                                                pageSpacing =
                                                    if (showSidePreview) LivingPosterDefaults.PAGE_SPACING else 0.dp,
                                                beyondViewportPageCount = if (showSidePreview) 1 else 0,
                                                key = { page -> page },
                                            ) { page ->
                                                val animatedIndex = loopingCarouselItemIndex(page, slides.size)
                                                val animatedItem = slides.getOrNull(animatedIndex) ?: slide
                                                // Backdrop first, poster as the understudy: an item can
                                                // carry a backdrop id whose image the server no longer has,
                                                // and the hero used to go blank rather than fall back.
                                                val animatedUrls =
                                                    listOf(
                                                        EmbyImages.backdrop(
                                                            baseUrl,
                                                            animatedItem,
                                                            accessToken = accessToken,
                                                        ),
                                                        EmbyImages.poster(
                                                            baseUrl,
                                                            animatedItem,
                                                            accessToken = accessToken,
                                                        ),
                                                    )
                                                HeroCarousel(
                                                    item = animatedItem,
                                                    urls = animatedUrls,
                                                    accent = accent,
                                                    serverId = state.currentServer?.id,
                                                    serverName = state.currentServer?.serverName.orEmpty(),
                                                    settled = page == pagerState.settledPage,
                                                    pageOffset = {
                                                        (pagerState.currentPage - page) +
                                                            pagerState.currentPageOffsetFraction
                                                    },
                                                    artworkAspectRatio = artworkAspectRatio,
                                                    artworkFadeFraction = artworkFadeFraction,
                                                    onPageColor = retainedPageColor::update,
                                                    framed = showSidePreview,
                                                    // The first stop TalkBack makes on this page, so 下拉刷新 lives
                                                    // here for someone who cannot pull.
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
                                                            }.refreshAction(enabled = !state.refreshing) {
                                                                store.accept(LibraryIntent.Retry)
                                                            },
                                                    onClick = { component.onOpenItem(animatedItem.id) },
                                                    onPlay = { component.onPlayItem(animatedItem.id) },
                                                    onToggleFavorite = {
                                                        store.accept(
                                                            LibraryIntent.ToggleFavorite(
                                                                itemId = animatedItem.id,
                                                                title = animatedItem.title,
                                                                favorite = !animatedItem.isFavorite,
                                                            ),
                                                        )
                                                    },
                                                    onToggleServerMenu = {
                                                        serverMenuOpen = !serverMenuOpen
                                                    },
                                                )
                                            }
                                            if (slides.size > 1) {
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
                                                        pageCount = slides.size,
                                                        selectedPage =
                                                            loopingCarouselItemIndex(
                                                                pagerState.currentPage,
                                                                slides.size,
                                                            ),
                                                        pageOffsetProvider = { pagerState.currentPageOffsetFraction },
                                                        onPageSelected = { targetIndex ->
                                                            if (targetIndex !=
                                                                loopingCarouselItemIndex(
                                                                    pagerState.currentPage,
                                                                    slides.size,
                                                                )
                                                            ) {
                                                                carouselLight.emit(LightEffect.Dust)
                                                            }
                                                            interaction++
                                                            carouselScope.launch {
                                                                val targetPage =
                                                                    loopingCarouselTargetPage(
                                                                        currentPage = pagerState.currentPage,
                                                                        targetIndex = targetIndex,
                                                                        itemCount = slides.size,
                                                                    )
                                                                if (reduceMotion) {
                                                                    pagerState.scrollToPage(targetPage)
                                                                } else {
                                                                    pagerState.animateScrollToPage(
                                                                        page = targetPage,
                                                                        animationSpec =
                                                                            tween(
                                                                                Motion.EMPHASIZED,
                                                                                easing = Motion.Curve,
                                                                            ),
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
                                }

                                if (state.contentSource == LibraryContentSource.Cached && !state.content.isEmpty) {
                                    waveItem(key = "library-freshness") {
                                        LibraryFreshnessBanner(
                                            updatedAtEpochMs = state.updatedAtEpochMs,
                                            nowEpochMs = freshnessNowEpochMs,
                                            error = state.error,
                                            loading = state.loading,
                                            signIn = signInServer != null,
                                            onRetry = recover,
                                        )
                                    }
                                }
                                if (state.content.rows.isNotEmpty() || state.currentServer != null) {
                                    if (showSmartPlaylists) {
                                        waveItem(key = "smart-playlists") {
                                            com.yfuse.feature.search
                                                .SmartPlaylistShelf()
                                        }
                                    }
                                    waveItem(key = "library-categories") {
                                        CategoryCards(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            rows = state.content.rows,
                                            playlists = state.content.playlists,
                                            onOpen = { component.onSeeAll(it.libraryId, it.title) },
                                            onRetry = { store.accept(LibraryIntent.Retry) },
                                            onOpenPlaylists = {
                                                state.currentServer?.id?.let { serverId ->
                                                    component.onSeeAll(
                                                        LibraryContainerDirectoryRoute(
                                                            serverId,
                                                            MediaContainerKind.Playlist,
                                                        ).encode(),
                                                        "播放列表",
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                                // Where 播放记录 and the shelves will land: under the category
                                // cards, not above them, so the page does not reshuffle on arrival.
                                if (state.loading && state.content.isEmpty) {
                                    motionItem(key = "library-loading") { SkeletonRow() }
                                }
                                if (state.content.resume.isNotEmpty()) {
                                    waveItem(key = "library-resume") {
                                        PlaybackHistory(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            serverId = state.currentServer?.id,
                                            items = state.content.resume,
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }
                                }
                                state.content.rows.libraryShelfRows().forEach { row ->
                                    waveItem(key = "library-shelf:${row.libraryId}:${row.title}") {
                                        CategorySection(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            serverId = state.currentServer?.id,
                                            row = row,
                                            onSeeAll = { component.onSeeAll(row.libraryId, row.title) },
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }
                                }
                                state.content.counts?.let { counts ->
                                    waveItem(key = "library-counts") {
                                        LibraryCountFooter(counts.movieCount, counts.seriesCount)
                                    }
                                }
                            }
                        }
                    }
            }

            // Once the hero has scrolled away, content must stop at the safe edge instead of
            // continuing underneath the clock and status icons. A page-coloured guard keeps
            // the full-bleed artwork at launch, then becomes the clipped top edge for shelves.
            if (!libraryCarousel || lightPageReached) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .drawBehind { drawRect(sampledPageColor.value) },
                )
            }

            if (serverMenuOpen) {
                ServerSheet(
                    servers = state.servers,
                    currentId = state.currentServer?.id,
                    onSelect = {
                        store.accept(LibraryIntent.SelectServer(it))
                        serverMenuOpen = false
                    },
                    onDismiss = { serverMenuOpen = false },
                )
            }
        }
    }
}

/** Non-blocking disclosure for content that is not currently verified live. */
@Composable
private fun LibraryFreshnessBanner(
    updatedAtEpochMs: Long?,
    nowEpochMs: Long,
    error: String?,
    loading: Boolean,
    /** The server refused its session, and [onRetry] signs in again instead of reloading. */
    signIn: Boolean,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val detail =
        buildList {
            when {
                error != null -> add(error)
                loading -> add("正在获取最新内容")
            }
            add("上次更新：${formatLibraryUpdatedAt(updatedAtEpochMs, nowEpochMs)}")
        }.joinToString(" · ")
    Column(
        Modifier
            .padding(horizontal = Dimens.pageHorizontal)
            .glass(
                shape = AppShapes.card,
                fill = palette.card2,
                border = palette.border,
            ).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        MotionSwap((error == null) to detail, Modifier.fillMaxWidth()) { (cached, message) ->
            Column(Modifier.fillMaxWidth()) {
                Text(if (cached) "缓存内容" else "离线内容", style = AppTypography.body.strong, color = palette.text)
                Text(
                    message,
                    style = AppTypography.caption.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (error != null && !loading) {
            Text(
                text = if (signIn) "重新登录" else "重试",
                style = AppTypography.body.strong,
                color = accent.accent,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .pressable(
                            role = Role.Button,
                            onClickLabel = if (signIn) "重新登录服务器" else "重新加载媒体库",
                            onClick = onRetry,
                        ).touchTarget()
                        .padding(horizontal = 10.dp),
            )
        }
    }
}

/** Quiet end-of-page summary backed by Emby's full-library counts, not preview rows. */
@Composable
private fun LibraryCountFooter(
    movieCount: Int,
    seriesCount: Int,
) {
    MotionSwap(movieCount to seriesCount, Modifier.fillMaxWidth()) { (movies, series) ->
        Text(
            text = "电影 $movies 部 · 剧集 $series 部",
            style = AppTypography.caption.medium,
            color = LocalPalette.current.sub2,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal, vertical = 20.dp),
        )
    }
}

/**
 * Viewport-aware hero — scrim `0deg rgba(10,14,26,.88) 0%, .55 42%, .05 62%, transparent`;
 * 正在流行 chip at `left/top 20/52`; server switcher at `right/top 20/52`;
 * Copy and actions reserve the indicator's complete 44dp hit lane plus breathing room.
 */
@Composable
private fun HeroCarousel(
    item: MediaItem,
    urls: List<String?>,
    accent: Color,
    serverId: String?,
    serverName: String,
    settled: Boolean,
    pageOffset: () -> Float,
    artworkAspectRatio: Float,
    artworkFadeFraction: Float,
    onPageColor: (Color) -> Unit,
    framed: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleServerMenu: () -> Unit,
) {
    val sharedKey = MediaSharedElementKey(serverId, item.id)
    val openDetail = sharedMediaOnClick(sharedKey, onClick)
    val captionProgress = rememberCarouselCaptionProgress(settled)
    var resolvedArtworkUrl by remember(item.id) { mutableStateOf<String?>(null) }
    val artworkPageColor =
        rememberArtworkPageColor(
            url = resolvedArtworkUrl,
            targetAspectRatio = artworkAspectRatio,
            fadeFraction = artworkFadeFraction,
        )
    LaunchedEffect(settled, artworkPageColor) {
        if (settled) artworkPageColor?.let(onPageColor)
    }
    Box(
        modifier
            .fillMaxSize()
            .then(if (framed) Modifier.livingPosterFrame() else Modifier)
            .pressable(onClick = openDetail),
    ) {
        Box(Modifier.fillMaxSize().fadeIntoPage().clipToBounds()) {
            FallbackImage(
                urls = urls,
                contentDescription = item.title,
                alphaOnly = false,
                onResolvedUrl = { resolvedArtworkUrl = it },
                modifier =
                    Modifier
                        .sharedMediaArtwork(sharedKey)
                        .playerArtworkSource(sharedKey, urls)
                        .fillMaxSize()
                        .carouselArtworkMotion(pageOffset, LocalAccessibilityOptions.current.reduceMotion)
                        .launchWaveImage(hero = true),
            )
        }
        // Contrast only. The image itself owns the lower transition through fadeIntoPage().
        Box(Modifier.fillMaxSize().background(heroTopScrim()))
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 正在流行 — `500 10.5px Manrope`, white, `{accent}45%`, `padding:4px 10px`.
            Text(
                "正在流行",
                style = AppTypography.caption.medium,
                color = Color.White,
                modifier =
                    Modifier
                        .glass(
                            shape = AppShapes.pill,
                            fill = accent.copy(alpha = 0.38f),
                            border = Color.White.copy(alpha = 0.30f),
                        ).padding(horizontal = 10.dp, vertical = 4.dp),
            )

            // Server switcher — `rgba(20,24,38,.45)` over `rgba(255,255,255,.25)`,
            // `radius:14px`, `padding:7px 12px`, `gap:6px`.
            Row(
                Modifier
                    .pressable(onClickLabel = "切换媒体服务器", onClick = onToggleServerMenu)
                    .touchTarget()
                    .glass(
                        shape = AppShapes.chip,
                        fill = Color(0xFF141826).copy(alpha = 0.36f),
                        border = Color.White.copy(alpha = 0.30f),
                    ).padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Brand.Online))
                Text(
                    serverName,
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    AppIcons.ChevronDown,
                    null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = LibraryHeroContentBottom,
                ).launchWaveItem(),
        ) {
            Text(
                item.title,
                modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 0),
                style = AppTypography.display.strong.copy(shadow = HeroTextShadow),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.subtitle,
                    modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 1),
                    style = AppTypography.caption.regular.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val facts =
                listOfNotNull(
                    item.year?.toString(),
                    heroMediaTypeLabel(item.type),
                    heroDurationLabel(item.runtimeMinutes),
                )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = facts.joinToString(" · "),
                    modifier = Modifier.carouselCaptionEntry(captionProgress, stage = 1),
                    style = AppTypography.caption.medium.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                favorite = item.isFavorite,
                playActionLabel = if ((item.playedPercentage ?: 0.0) > 0.0) "继续播放" else "播放",
                onPlay = playerArtworkOnClick(sharedKey, onPlay),
                onFavorite = onToggleFavorite,
            )
        }
    }
}

/**
 * 切换服务器 — a [GlassDialog], centred like every overlay outside the player.
 *
 * It started at the bottom edge, on the rule that picking one value out of a short
 * reversible list belongs within thumb reach. It was the first to be centred instead,
 * because of where it is opened from — the switcher chip sits at the top right of the
 * hero, and answering it from the bottom of a tall hero sends the eye the length of the
 * screen and back — and the rest of the app has since followed.
 *
 * What it replaces: a 180dp menu anchored under the hero's switcher chip, hand-rolled
 * out of a hard-coded `rgba(255,255,255,.95)` plate with `#151A22` text. That is exactly
 * the "hand-rolled anchored menu" [com.yfuse.core.designsystem.GlassDialog]'s docs call
 * out as the thing the overlay system exists to replace — the library was simply missed
 * in that pass. It also broke twice over: a white plate under the dark theme, and 180dp
 * of width for names that routinely need more.
 *
 * The rows carry the same identity as 「我的」's server list (colour tile + initial,
 * name, account) so the same server looks like itself in both places. Only the current
 * row gets a status dot: the old menu painted every other row with [Brand.Offline],
 * which read as "unreachable" when it only ever meant "not selected".
 */
@Composable
private fun ServerSheet(
    servers: List<SavedServer>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "切换服务器",
            subtitle = "已登录 ${servers.size} 个 · 切换后重新载入媒体库",
            onClose = onDismiss,
        )
        // A centred panel has no edge to grow against, so a long server list scrolls inside
        // the dialog instead of running off both ends of the screen. [GlassDialog] does that
        // itself now, against the screen it is actually on rather than a fixed maximum.
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            servers.forEach { server ->
                val isCurrent = server.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(
                            enabled = !isCurrent,
                            role = Role.RadioButton,
                            onClickLabel = "切换到${server.serverName}",
                            onClick = overlayAction { onSelect(server.id) },
                        ).semantics { this.selected = isCurrent }
                        .glass(
                            shape = AppShapes.chip,
                            fill = selectionColor(if (isCurrent) themeAccent.container else palette.card2),
                            border =
                                selectionColor(
                                    if (isCurrent) themeAccent.border.copy(alpha = 0.30f) else palette.border,
                                ),
                        ).padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(AppShapes.thumb)
                            .background(serverBadgeColor(server.id)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            server.serverName.take(1).uppercase(),
                            style = AppTypography.caption.strong,
                            color = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.serverName,
                            style = AppTypography.body.strong,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isCurrent) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Brand.Online),
                                )
                            }
                            Text(
                                if (isCurrent) "当前使用 · ${server.userName}" else server.userName,
                                style = AppTypography.caption.regular,
                                color = palette.sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isCurrent) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = "当前服务器",
                            tint = themeAccent.accent,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        Text("切换", style = AppTypography.caption.strong, color = themeAccent.accent)
                    }
                }
            }
        }
    }
}

/**
 * Category cards — 148×88, using the library's own artwork cropped to fill
 * under the `0deg rgba(0,0,0,.35) → transparent 60%` scrim. Tapping one opens that
 * library's grid — or, when that category failed to load, retries instead of opening a
 * grid that would just come up empty.
 */
@Composable
private fun CategoryCards(
    baseUrl: String,
    accessToken: String,
    rows: List<HomeRow>,
    playlists: List<MediaContainer>,
    onOpen: (HomeRow) -> Unit,
    onOpenPlaylists: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        motionItems(rows.distinctBy { it.libraryId }, key = { it.libraryId }) { row ->
            val cover = row.items.firstOrNull()
            val personalIcon =
                when (row.libraryId) {
                    FAVORITES_COLLECTION_ID -> AppIcons.Heart
                    WATCH_LATER_COLLECTION_ID -> AppIcons.Bookmark
                    else -> null
                }
            val coverUrl =
                cover?.let {
                    EmbyImages.backdrop(baseUrl, it, maxWidth = 480, accessToken = accessToken)
                        ?: EmbyImages.poster(baseUrl, it, accessToken = accessToken)
                }
            LibraryCategoryCard(
                title = row.title,
                countLabel = if (row.loadFailed) "加载失败，点击重试" else "${row.totalCount}部",
                coverUrl = coverUrl,
                fallbackIcon = personalIcon,
                onClick = { if (row.loadFailed) onRetry() else onOpen(row) },
            )
        }

        motionItem(key = "library-playlists") {
            val playlist = playlists.distinctBy { it.id }.firstOrNull()
            LibraryCategoryCard(
                title = "播放列表",
                countLabel = null,
                coverUrl =
                    playlist?.let {
                        EmbyImages.primary(
                            baseUrl = baseUrl,
                            itemId = it.id,
                            tag = it.posterTag,
                            maxHeight = 360,
                            accessToken = accessToken,
                        )
                    },
                fallbackIcon = AppIcons.TabLibrary,
                onClick = onOpenPlaylists,
            )
        }
    }
}

@Composable
private fun LibraryCategoryCard(
    title: String,
    countLabel: String?,
    coverUrl: String?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .width(148.dp)
            .height(88.dp)
            .pressable(onClick = onClick)
            .clip(AppShapes.card)
            .background(
                if (coverUrl == null && fallbackIcon != null) {
                    // A guaranteed-dark plate in both themes, for the white glyph below —
                    // `palette.card2` alone would wash out under 浅色 mode.
                    palette.reducedFill.control
                } else {
                    palette.card2
                },
            ),
    ) {
        if (coverUrl != null) {
            FallbackImage(
                urls = listOf(coverUrl),
                // The title below already names the card as visible text; repeating it here
                // made a screen reader announce it twice.
                contentDescription = null,
                modifier = Modifier.fillMaxSize().launchWaveImage(),
            )
        } else if (fallbackIcon != null) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.48f),
                modifier = Modifier.align(Alignment.Center).size(27.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                // Deepened from 0.35 — the title below was reading as barely-there on light
                // covers at this card's small caption size.
                scrim(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.6f to Color.Transparent,
                ),
            ),
        )
        Text(
            title,
            style = AppTypography.body.strong,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp, end = 12.dp),
        )
        if (countLabel != null) {
            Text(
                countLabel,
                style = AppTypography.caption.strong,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 10.dp),
            )
        }
    }
}

/** Personal shortcuts and playlists live in the category rail, not in repeated shelves. */
internal fun List<HomeRow>.libraryShelfRows(): List<HomeRow> =
    asSequence()
        .filter { it.libraryId != FAVORITES_COLLECTION_ID }
        .filter { it.libraryId != WATCH_LATER_COLLECTION_ID }
        .filter { it.items.isNotEmpty() || it.loadFailed }
        .distinctBy { it.libraryId }
        .toList()

/**
 * Which titles the hero, 播放记录 and each shelf hold, in order: what a pull-to-refresh can
 * visibly change on this page. Equal before and after a refresh that failed or found nothing new.
 */
internal fun libraryRefreshRevision(content: HomeContent): List<List<String>> =
    buildList {
        add(content.featured.map { it.id })
        add(content.resume.map { it.id })
        content.rows.forEach { row -> add(listOf(row.libraryId) + row.items.map { it.id }) }
    }

/**
 * 播放记录 replaces the former category shortcut rail. The 190×114 landscape
 * artwork gives viewing history more visual weight and keeps progress readable.
 */
@Composable
private fun PlaybackHistory(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    Column {
        SectionHeader("播放记录")
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItemsIndexed(
                items = items,
                key = { index, item ->
                    mediaLazyItemKey("library-history:${serverId.orEmpty()}", index, item.id)
                },
            ) { _, item ->
                CaptionedPoster(
                    url =
                        EmbyImages.backdrop(
                            baseUrl,
                            item,
                            maxWidth = 640,
                            accessToken = accessToken,
                        ),
                    fallbackUrl =
                        EmbyImages.poster(
                            baseUrl,
                            item,
                            accessToken = accessToken,
                        ),
                    fallbackUrls =
                        listOfNotNull(
                            EmbyImages.primary(
                                baseUrl,
                                item.id,
                                tag = null,
                                maxHeight = 450,
                                accessToken = accessToken,
                            ),
                        ),
                    title = item.title,
                    rating = item.communityRating,
                    year = item.year?.toString(),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    // Scoped to this rail: a film that was just watched is also a film
                    // that was just added, so the same id is on screen twice — see
                    // [CategorySection] for what that costs.
                    onClick = { onItemClick(item) },
                    sharedTransitionKey = MediaSharedElementKey(serverId, item.id),
                    modifier = Modifier.width(MediaSizing.landscapeCardWidth),
                    posterModifier = Modifier.fillMaxWidth().height(MediaSizing.landscapeCardHeight),
                )
            }
        }
    }
}

/**
 * Category block — a 15sp header with `全部`, over a 104×150 poster rail with
 * title/year below each artwork.
 */
@Composable
private fun CategorySection(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    row: HomeRow,
    onSeeAll: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    Column {
        SectionHeader(row.title, actionLabel = "全部", onAction = onSeeAll)
        if (row.loadFailed) {
            Text(
                text = "暂时无法加载，点击“全部”查看或下拉重试",
                style = AppTypography.caption.regular,
                color = LocalPalette.current.sub,
                modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            motionItemsIndexed(
                items = row.items,
                key = { index, item ->
                    mediaLazyItemKey(
                        "library-category:${serverId.orEmpty()}:${row.libraryId}",
                        index,
                        item.id,
                    )
                },
            ) { _, item ->
                CaptionedPoster(
                    url = EmbyImages.poster(baseUrl, item, accessToken = accessToken),
                    title = item.title,
                    rating = item.communityRating,
                    year = item.year?.toString(),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    // A shared-element key has to be unique within a screen. Keyed on the
                    // item alone, a film sitting in both 播放记录 and its category rail —
                    // or in two libraries at once — registered the same key twice, and
                    // only one of the two copies was ever drawn: the other went blank
                    // until a route transition released the key, which is what made the
                    // poster flash into place for one frame on the way out. The library
                    // id scopes the key to this rail; 首页 hit the same thing and answered
                    // it by dropping the key entirely (see HomeScreen's shelves).
                    onClick = { onItemClick(item) },
                    sharedTransitionKey = MediaSharedElementKey(serverId, item.id),
                    modifier = Modifier.width(PosterWidth),
                )
            }
        }
    }
}

/**
 * Loading skeleton: a title bar then three poster-and-caption placeholders, sized to the
 * rail it becomes ([PosterWidth]) so the content does not jump a few dp sideways once
 * the real rows arrive.
 */
@Composable
private fun SkeletonRow() {
    SkeletonRail(
        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        posterWidth = PosterWidth,
    )
}

/** Shared poster tile with title/year below, reused by the library grid. */
@Composable
internal fun PosterCard(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    item: MediaItem,
    showProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    CaptionedPoster(
        url = EmbyImages.poster(baseUrl, item, accessToken = accessToken),
        onLongClick = onLongClick,
        title = item.title,
        rating = item.communityRating,
        year = item.year?.toString(),
        progress = if (showProgress) item.playedPercentage?.let { (it / 100.0).toFloat() } else null,
        onClick = onClick,
        sharedTransitionKey = MediaSharedElementKey(serverId, item.id),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The skeleton arrival, with the cold-start wave layered in. While the wave runs it replaces the
 * row-by-row skeleton lift for this one appearance; both at once would move every row twice.
 */
@Composable
private fun LibraryArrivalScope(
    loading: Boolean,
    wave: LaunchWaveState,
    content: @Composable () -> Unit,
) {
    SkeletonArrivalScope(loading) {
        val skeletonArrival = LocalSkeletonArrival.current.takeUnless { wave.animating }
        CompositionLocalProvider(
            // Only while it runs. Once it has settled, every row and picture on the page kept a
            // position callback and a layer of its own that no longer did anything.
            LocalLaunchWave provides wave.takeIf { it.animating },
            LocalSkeletonArrival provides skeletonArrival,
            content = content,
        )
    }
}

/**
 * A page section that rises, sinks and settles as one row when the cold-start wave reaches it.
 * The hero is not one of these: a full-bleed picture that moved would open a gap under the status
 * bar, so it sways inside its own frame instead (see [HeroCarousel]).
 */
private fun LazyListScope.waveItem(
    key: Any?,
    content: @Composable LazyItemScope.() -> Unit,
) {
    motionItem(key = key) {
        val itemScope = this
        Box(Modifier.launchWaveItem()) { itemScope.content() }
    }
}
