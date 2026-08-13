package com.yfuse.feature.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.data.FAVORITES_COLLECTION_ID
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkAccent
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.HeroCaptionClearance
import com.yfuse.core.designsystem.HeroPageIndicator
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.MediaSizing
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.SkeletonRail
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroReelScrim
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselPageCount
import com.yfuse.core.designsystem.loopingCarouselSemantics
import com.yfuse.core.designsystem.loopingCarouselStartPage
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.pageTint
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedDominantColor
import com.yfuse.core.designsystem.rememberScrolledPastHero
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hero carousel height. The status-bar switch threshold used to repeat this as a literal,
 * so resizing the hero silently moved the point where the status bar flips its icons.
 */
private val HeroHeight = MediaSizing.heroHeight

private val LibraryHeroIndicatorBottom = 12.dp

/**
 * The caption clears the whole dissolve band — white copy cannot follow the artwork into
 * the page. Only the dots, whose ink is the page's, sit inside it.
 */
private val LibraryHeroContentBottom = HeroCaptionClearance

/** How far the content column is pulled up over the lower edge of the hero. */
private val HeroLift = 52.dp

/** Poster rail column width, shared by the real rails and the loading skeleton. */
private val PosterWidth = MediaSizing.posterRailWidth

/** `transparent 320px` — how far the artwork's tint reaches into the content. */
private val ContentWashHeight = 320.dp

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

/** 媒体库 — a 432px hero carousel above `padding:16px 18px 100px; gap:22px` of rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(component: LibraryHomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val baseUrl = state.currentServer?.baseUrl.orEmpty()
    // Image endpoints answer 401 without it on a server that requires authentication, so
    // the token travels with the base URL to every artwork on this page — not just to
    // 播放记录, which was the only row that had it.
    val accessToken = state.currentServer?.accessToken.orEmpty()
    val palette = LocalPalette.current

    val slides = state.content.featured.take(4)
    val pagerState =
        rememberPagerState(
            pageCount = { loopingCarouselPageCount(slides.size) },
        )
    val slideIndex = loopingCarouselItemIndex(pagerState.currentPage, slides.size)
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    // Interaction restarts the reel's clock instead of stopping it; see 首页's hero.
    var interaction by remember { mutableStateOf(0) }
    val slide = slides.getOrNull(slideIndex)
    val slideUrl =
        slide?.let {
            EmbyImages.backdrop(baseUrl, it, accessToken = accessToken)
                ?: EmbyImages.poster(baseUrl, it, accessToken = accessToken)
        }
    val accent =
        rememberAnimatedDominantColor(
            slideUrl,
            Brand.Primary, // design-system: brand-identity
        )

    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = state.loading)

    var serverMenuOpen by remember { mutableStateOf(false) }
    val listState = component.listState
    val density = LocalDensity.current
    val lightPageReached by rememberScrolledPastHero(listState, HeroHeight)
    StatusBarIconStyle(darkIcons = (slide == null || lightPageReached) && !palette.isDark)

    LaunchedEffect(slides.map { it.id }) {
        pagerState.scrollToPage(loopingCarouselStartPage(slides.size))
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
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
    LaunchedEffect(slides.size, carouselDragging, reduceMotion, routeVisible, interaction) {
        // Same reasoning as 首页's reel: the largest moving thing on the page, and the one
        // 减弱动态效果 was not reaching.
        if (!routeVisible || slides.size <= 1 || carouselDragging || reduceMotion) {
            return@LaunchedEffect
        }
        while (true) {
            delay(6_000)
            pagerState.animateScrollToPage(
                page = pagerState.currentPage + 1,
                animationSpec = tween(Motion.CAROUSEL, easing = Motion.Curve),
            )
        }
    }
    ScrollToTopOnReselect(listState)

    // The room the shelves sit in, taken from whatever the carousel is showing. `accent` is
    // already animated, so the page changes colour with the slide rather than on a cut — and
    // under 跟随封面 the page's controls travel with it too.
    val ground = pageTint(accent)
    val bottomContentInset = floatingNavigationContentInset()
    ArtworkAccent(accent) {
        Box(Modifier.fillMaxSize().background(ground)) {
            when {
                state.currentServer == null ->
                    PageHint(
                        "还没有默认服务器，请到「我的」添加",
                        Modifier.align(Alignment.Center),
                    )

                state.error != null && state.content.isEmpty ->
                    ErrorState(
                        message = state.error!!,
                        onRetry = { store.accept(LibraryIntent.Retry) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                else ->
                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = { store.accept(LibraryIntent.Retry) },
                        state = pullState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = bottomContentInset),
                        ) {
                            if (slide != null) {
                                item {
                                    Box(Modifier.fillMaxWidth().height(HeroHeight)) {
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .loopingCarouselSemantics(pagerState.currentPage, slides.size),
                                            beyondViewportPageCount = 1,
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
                                            val animatedAccent =
                                                rememberAnimatedDominantColor(
                                                    animatedUrls.firstOrNull { it != null },
                                                    Brand.Primary, // design-system: brand-identity
                                                )
                                            HeroCarousel(
                                                item = animatedItem,
                                                urls = animatedUrls,
                                                accent = animatedAccent,
                                                serverId = state.currentServer?.id,
                                                serverName = state.currentServer?.serverName.orEmpty(),
                                                onClick = { component.onOpenItem(animatedItem.id) },
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
                                            HeroPageIndicator(
                                                pageCount = slides.size,
                                                selectedPage = slideIndex,
                                                onPageSelected = { targetIndex ->
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
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .padding(bottom = LibraryHeroIndicatorBottom),
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                val liftPx = with(density) { HeroLift.roundToPx() }
                                // Artwork tint only. This used to carry a `palette.background` stop
                                // at 86% as well, to blend the hero into the page — a job
                                // [Modifier.fadeIntoPage] now does at the hero itself, by removing
                                // the artwork's alpha so the real page shows through. Painting a
                                // *flat* background over that was the one thing guaranteed to break
                                // it: the page is not flat, it is [appBackdropBrushes]' gradient, so
                                // the band met it at a visible seam whose colour was almost, but
                                // never quite, the page's own.
                                val wash =
                                    remember(accent, density) {
                                        Brush.verticalGradient(
                                            colorStops =
                                                arrayOf(
                                                    0f to Color.Transparent,
                                                    0.20f to accent.copy(alpha = 0.10f),
                                                    1f to Color.Transparent,
                                                ),
                                            startY = 0f,
                                            endY = with(density) { ContentWashHeight.toPx() },
                                        )
                                    }
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        // `offset` moves the drawing but keeps the measured height,
                                        // so the lift used to leave 52dp of blank page hanging off
                                        // the end of the list. Shrink the slot instead.
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            layout(
                                                placeable.width,
                                                (placeable.height - liftPx).coerceAtLeast(0),
                                            ) {
                                                placeable.place(0, -liftPx)
                                            }
                                        }.background(wash)
                                        .padding(top = 78.dp),
                                    verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                                ) {
                                    if (
                                        state.contentSource == LibraryContentSource.Cached &&
                                        !state.content.isEmpty
                                    ) {
                                        LibraryFreshnessBanner(
                                            updatedAtEpochMs = state.updatedAtEpochMs,
                                            nowEpochMs = freshnessNowEpochMs,
                                            error = state.error,
                                            loading = state.loading,
                                            onRetry = { store.accept(LibraryIntent.Retry) },
                                        )
                                    }

                                    if (state.loading && state.content.isEmpty) {
                                        SkeletonRow()
                                    }

                                    if (state.content.rows.isNotEmpty()) {
                                        CategoryCards(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            rows = state.content.rows,
                                            onOpen = {
                                                component.onSeeAll(it.libraryId, it.title)
                                            },
                                        )
                                    }

                                    // 合集 is deliberately not a shelf here. The collections a server
                                    // holds are still loaded and still reachable — 详情页 → 加入合集或
                                    // 播放列表 works on them, and the directory route below opens one
                                    // by id — they just no longer take a row of the library root.
                                    if (state.content.playlists.isNotEmpty()) {
                                        MediaContainerSection(
                                            title = "播放列表",
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            containers = state.content.playlists,
                                            onOpen = { container ->
                                                component.onSeeAll(
                                                    LibraryContainerRoute.from(container).encode(),
                                                    container.title,
                                                )
                                            },
                                            onSeeAll = {
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

                                    if (state.content.resume.isNotEmpty()) {
                                        PlaybackHistory(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            serverId = state.currentServer?.id,
                                            items = state.content.resume,
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }

                                    state.content.rows.filter { it.items.isNotEmpty() }.forEach { row ->
                                        CategorySection(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            serverId = state.currentServer?.id,
                                            row = row,
                                            onSeeAll = {
                                                component.onSeeAll(row.libraryId, row.title)
                                            },
                                            onItemClick = { component.onOpenItem(it.id) },
                                        )
                                    }

                                    state.content.counts?.let { counts ->
                                        LibraryCountFooter(
                                            movieCount = counts.movieCount,
                                            seriesCount = counts.seriesCount,
                                        )
                                    }
                                }
                            }
                        }
                    }
            }

            // Once the hero has scrolled away, content must stop at the safe edge instead of
            // continuing underneath the clock and status icons. A page-coloured guard keeps
            // the full-bleed artwork at launch, then becomes the clipped top edge for shelves.
            if (lightPageReached) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(ground),
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
                shape = GlassShapes.card,
                fill = palette.card2,
                border = palette.border,
            ).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = if (error == null) "缓存内容" else "离线内容",
            style = AppTypography.body.strong,
            color = palette.text,
        )
        Text(
            text = detail,
            style = AppTypography.caption.regular,
            color = palette.sub,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (error != null && !loading) {
            Text(
                text = "重试",
                style = AppTypography.body.strong,
                color = accent.accent,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .pressable(
                            role = Role.Button,
                            onClickLabel = "重新加载媒体库",
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
    Text(
        text = "电影 $movieCount 部 · 剧集 $seriesCount 部",
        style = AppTypography.caption.medium,
        color = LocalPalette.current.sub2,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal, vertical = 20.dp),
    )
}

/**
 * 432px hero — scrim `0deg rgba(10,14,26,.88) 0%, .55 42%, .05 62%, transparent`;
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
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleServerMenu: () -> Unit,
) {
    val sharedKey = MediaSharedElementKey(serverId, item.id)
    val openDetail = sharedMediaOnClick(sharedKey, onClick)
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .pressable(onClick = openDetail),
    ) {
        FallbackImage(
            urls = urls,
            contentDescription = item.title,
            modifier =
                Modifier
                    .sharedMediaArtwork(sharedKey)
                    .fillMaxSize(),
        )
        // Its own slide's tint rather than the page's: mid-swipe the neighbour dissolves
        // into the colour it is about to give the page, so the two arrive together instead
        // of the scrim stepping to the new ground after the pager settles. Same rule as
        // 首页's hero.
        Box(Modifier.fillMaxSize().background(heroReelScrim(pageTint(accent))))
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                        shape = GlassShapes.chip,
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
                ),
        ) {
            Text(
                item.title,
                style = AppTypography.display.strong.copy(shadow = HeroTextShadow),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.subtitle,
                    style = AppTypography.caption.regular.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.overview.isNullOrBlank()) {
                // `400 11.5px/1.6`, clamped to two lines. Opacity is up from .7: without a
                // scrim under it, 70% white on a pale still is not copy any more.
                Spacer(Modifier.height(10.dp))
                Text(
                    item.overview,
                    style =
                        AppTypography.body.regular.copy(
                            lineHeight = 20.8.sp,
                            shadow = HeroTextShadow,
                        ),
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            // `rgba(255,255,255,.92)`, `radius:18px`, `padding:8px 18px`, `700 12px`.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .pressable(onClickLabel = "查看详情", onClick = openDetail)
                        .touchTarget()
                        .height(42.dp)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = Color(0xFF101722).copy(alpha = 0.30f),
                            border = Color.White.copy(alpha = 0.40f),
                        ).padding(start = 4.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .glass(
                                shape = CircleShape,
                                fill = Color.White.copy(alpha = 0.22f),
                                border = Color.White.copy(alpha = 0.54f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Info,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Text("查看详情", style = AppTypography.body.strong, color = Color.White)
                }
                HeroCircleAction(
                    active = item.isFavorite,
                    icon = if (item.isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                    description = if (item.isFavorite) "取消收藏" else "加入收藏",
                    onClick = onToggleFavorite,
                )
            }
        }
    }
}

/**
 * 切换服务器 — a [GlassDialog], centred like every overlay outside the player.
 *
 * It started at the bottom edge, on the rule that picking one value out of a short
 * reversible list belongs within thumb reach. It was the first to be centred instead,
 * because of where it is opened from — the switcher chip sits at the top right of the
 * hero, and answering it from the bottom of a 432px hero sends the eye the length of the
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
                            onClick = { onSelect(server.id) },
                        ).semantics { this.selected = isCurrent }
                        .glass(
                            shape = GlassShapes.chip,
                            fill =
                                if (isCurrent) {
                                    themeAccent.container
                                } else {
                                    palette.card2
                                },
                            border =
                                if (isCurrent) {
                                    themeAccent.border.copy(alpha = 0.30f)
                                } else {
                                    palette.border
                                },
                        ).padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(AppShapes.thumb)
                            .background(serverTileColor(server.id)),
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
 * Stable identity colour per server, matching 「我的」's server list. Kept local to the
 * media library rather than shared, because the profile page owns the same four-colour
 * ramp; if a third surface ever needs it, that is the moment to lift it into the
 * design system.
 */
private fun serverTileColor(id: String): Color {
    val index = id.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    return ServerTileColors[index % ServerTileColors.size]
}

private val ServerTileColors =
    listOf(
        Color(0xFF6689D3),
        Color(0xFFC98F5B),
        Color(0xFF8298C1),
        Color(0xFF7198CB),
    )

/**
 * Category cards — 148×88, using the library's own artwork cropped to fill
 * under the `0deg rgba(0,0,0,.35) → transparent 60%` scrim. Tapping one opens that
 * library's grid.
 */
@Composable
private fun CategoryCards(
    baseUrl: String,
    accessToken: String,
    rows: List<HomeRow>,
    onOpen: (HomeRow) -> Unit,
) {
    val palette = LocalPalette.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.libraryId }) { row ->
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
            Box(
                Modifier
                    .width(148.dp)
                    .height(88.dp)
                    .pressable { onOpen(row) }
                    .clip(GlassShapes.poster)
                    .background(
                        if (coverUrl == null && personalIcon != null) {
                            Color(0xFF4C5F83)
                        } else {
                            palette.card2
                        },
                    ),
            ) {
                if (coverUrl != null) {
                    FallbackImage(
                        urls = listOf(coverUrl),
                        contentDescription = row.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (personalIcon != null) {
                    Icon(
                        imageVector = personalIcon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.48f),
                        modifier = Modifier.align(Alignment.Center).size(27.dp),
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        scrim(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.6f to Color.Transparent,
                        ),
                    ),
                )
                Text(
                    row.title,
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 10.dp, end = 12.dp),
                )
                Text(
                    "${row.totalCount}部",
                    style = AppTypography.caption.strong,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 10.dp),
                )
            }
        }
    }
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
            items(items, key = { it.id }) { item ->
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
 * One heading scale for the whole page. 播放记录 used to be 18sp while every category
 * below it was 15sp, so the first row read as a level above its siblings for no reason.
 * 15sp/700 is the section step used by the detail page and the design's `font:700 15px`.
 */
@Composable
private fun SectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTypography.section.strong,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onSeeAll != null) {
            // 「影视详情页 优化」spells this as `更多 ›` in the secondary ink, not a chip.
            // The chip it replaces was filled `palette.card2` over a `palette.border`
            // hairline — both pure white on this page's white, so all it contributed was
            // a floating label with a smudge behind it.
            Row(
                Modifier
                    .pressable(onClickLabel = "查看${title}的全部内容", onClick = onSeeAll)
                    .touchTarget()
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("更多", style = AppTypography.caption.medium, color = palette.sub2)
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = palette.hint,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/**
 * Category block — a 15sp header with `更多 ›`, over a 104×150 poster rail with
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
        SectionHeader(row.title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                CaptionedPoster(
                    url = EmbyImages.poster(baseUrl, item, accessToken = accessToken),
                    title = item.title,
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

/** Real Emby organization containers; empty sections are omitted by the caller. */
@Composable
private fun MediaContainerSection(
    title: String,
    baseUrl: String,
    accessToken: String,
    containers: List<MediaContainer>,
    onOpen: (MediaContainer) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column {
        SectionHeader(title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(containers, key = { "${it.kind}-${it.serverId}-${it.id}" }) { container ->
                CaptionedPoster(
                    url =
                        EmbyImages.primary(
                            baseUrl = baseUrl,
                            itemId = container.id,
                            tag = container.posterTag,
                            maxHeight = 450,
                            accessToken = accessToken,
                        ),
                    title = container.title,
                    year = container.itemCount?.let { "$it 项" },
                    progress = null,
                    onClick = { onOpen(container) },
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

@Composable
private fun CenterHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = AppTypography.body.regular,
        color = LocalPalette.current.sub,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
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
) {
    CaptionedPoster(
        url = EmbyImages.poster(baseUrl, item, accessToken = accessToken),
        title = item.title,
        year = item.year?.toString(),
        progress = if (showProgress) item.playedPercentage?.let { (it / 100.0).toFloat() } else null,
        onClick = onClick,
        sharedTransitionKey = MediaSharedElementKey(serverId, item.id),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeroCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    /** Non-null for a toggle, so the glyph can answer being switched on. */
    active: Boolean? = null,
) {
    Box(
        Modifier
            .pressable(
                haptic = if (active != null) HapticSignal.Confirm else null,
                role = if (active == null) Role.Button else Role.Checkbox,
                onClickLabel = description,
                onClick = onClick,
            ).then(
                if (active == null) {
                    Modifier
                } else {
                    Modifier.semantics { toggleableState = ToggleableState(active) }
                },
            ).touchTarget()
            .size(34.dp)
            .glass(
                shape = CircleShape,
                fill = Color.White.copy(alpha = 0.14f),
                border = Color.White.copy(alpha = 0.34f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active == null) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(14.dp))
        } else {
            BurstIcon(
                icon = icon,
                active = active,
                contentDescription = description,
                tint = Color.White,
                burstColor = Color.White,
            )
        }
    }
}
