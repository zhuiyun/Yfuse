package com.yfuse.feature.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HeroCaptionClearance
import com.yfuse.core.designsystem.HeroTextShadow
import com.yfuse.core.designsystem.HeroPageIndicator
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSizing
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.designsystem.PrimaryGradient
import com.yfuse.core.designsystem.SkeletonRail
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.CloudPlayerLogo
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.pageTint
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselPageCount
import com.yfuse.core.designsystem.loopingCarouselSemantics
import com.yfuse.core.designsystem.loopingCarouselStartPage
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.TmdbGenres
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HomeHeroIndicatorBottom = 7.dp

/**
 * The caption clears the whole dissolve band.
 *
 * White copy is legible on artwork and on the scrim over it; it is not legible on the page
 * the artwork is turning into. Every line of the caption therefore stays above the band,
 * and only the dots — whose ink is the page's — sit inside it.
 */
private val HomeHeroContentBottom = HeroCaptionClearance

/**
 * 首页 — the prototype's `isHome` screen:
 * `padding:52px 18px 100px; gap:22px`, greeting, search entry, hero, 继续观看, 为你推荐.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val themeAccent = LocalAccentColors.current.accent
    val listState = component.listState
    val heroVisible by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < 330
        }
    }
    StatusBarIconStyle(darkIcons = !heroVisible && !palette.isDark)
    // The shelf opened out into a grid, or null. Held here rather than in the store: it is
    // which page is on screen, not anything about the data.
    var expandedRow by remember { mutableStateOf<TmdbRow?>(null) }
    // The carousel owns which slide is settled, so it reports the colour up rather than the
    // page trying to work it out from an index it does not hold.
    var heroAccent by remember { mutableStateOf<Color?>(null) }
    val ground = pageTint(heroAccent ?: Brand.Primary) // design-system: brand-identity

    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing = state.refreshing)
    // 首页's search, calendar and account entries live inside a hero that scrolls away, so
    // this tab is the one where tapping the tab again matters most.
    ScrollToTopOnReselect(listState)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Continue Watching is the highest-value shelf once it exists, so the hero gives it
        // enough room to peek into the first viewport. Empty accounts keep the more cinematic
        // treatment. Bounds protect compact phones and tablets from extreme proportions.
        val widthTier = windowWidthTier(maxWidth)
        val heroHeight = when (widthTier) {
            WindowWidthTier.Compact ->
                (maxHeight * if (state.resume.isNotEmpty()) 0.43f else 0.48f)
                    .coerceIn(320.dp, 390.dp)

            WindowWidthTier.Medium ->
                (maxHeight * if (state.resume.isNotEmpty()) 0.46f else 0.50f)
                    .coerceIn(360.dp, 440.dp)

            WindowWidthTier.Expanded ->
                (maxHeight * if (state.resume.isNotEmpty()) 0.48f else 0.52f)
                    .coerceIn(390.dp, 480.dp)
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { component.store.accept(HomeIntent.Refresh) },
            state = pullState,
            modifier = Modifier.fillMaxSize().background(ground),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = TabBarInset),
                verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
            ) {
                // Navigation in the hero header must remain available even when the remote
                // recommendation feed is loading or unavailable.
                item {
                    HomeHeroCarousel(
                        items = state.featuredSlides.take(5),
                        userName = state.server?.userName,
                        height = heroHeight,
                        visible = heroVisible,
                        onOpenProfile = component.onOpenProfile,
                        onOpenSearch = component.onOpenSearch,
                        onOpenCalendar = component.onOpenCalendar,
                        onPlay = { component.store.accept(HomeIntent.Open(it)) },
                        onFavorite = { component.store.accept(HomeIntent.Favorite(it)) },
                        onAccent = { heroAccent = it },
                    )
                }

                if (state.loading && state.content.isEmpty) {
                    // Two shelves' worth of placeholders rather than one spinner: the page
                    // this becomes is a stack of rails, and a skeleton that is the wrong
                    // shape moves the content once it arrives.
                    items(2, key = { "recommendations-loading-$it" }) {
                        SkeletonRail(
                            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                        )
                    }
                } else if (state.error != null && state.content.isEmpty) {
                    item(key = "recommendations-error") {
                        ErrorState(
                            message = state.error!!,
                            onRetry = { component.store.accept(HomeIntent.Retry) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                state.recommendationNotice?.let { notice ->
                    item(key = "recommendations-cache-notice") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.pageHorizontal)
                                .glass(GlassShapes.chip, palette.card2, palette.border)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = notice,
                                style = AppTypography.body.medium,
                                color = palette.sub,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "重新刷新",
                                style = AppTypography.body.strong,
                                color = themeAccent,
                                modifier = Modifier
                                    .pressable(
                                        onClickLabel = "重新刷新首页",
                                        onClick = { component.store.accept(HomeIntent.Retry) },
                                    )
                                    .touchTarget(),
                            )
                        }
                    }
                }

                if (state.nextUp.isNotEmpty()) {
                    item(key = "next-up") {
                        NextUpShelf(
                            items = state.nextUp,
                            onSeeAll = component.onOpenCalendar,
                            onClick = { component.store.accept(HomeIntent.OpenResume(it)) },
                        )
                    }
                }

                if (state.resume.isNotEmpty()) {
                    item {
                        ContinueWatching(
                            items = state.resume,
                            onSeeAll = component.onOpenLibrary,
                            onClick = { component.store.accept(HomeIntent.OpenResume(it)) },
                        )
                    }
                }

                state.content.rows.forEach { row ->
                    if (row.items.isNotEmpty()) {
                        item(key = "tmdb-${row.title}") {
                            Recommended(
                                title = row.title,
                                items = row.items,
                                showReleaseDate = row.title == "即将上映" || row.title == "最新上线",
                                // Opens this shelf, not the 库 tab. These come from TMDB and
                                // most are not in the library at all, so the old destination
                                // showed none of what the chip had just offered.
                                onSeeAll = { expandedRow = row },
                                onClick = { component.store.accept(HomeIntent.Open(it)) },
                            )
                        }
                    }
                }
            }
        }

        // Floats over the page rather than sitting in it: as a list item this pushed the
        // whole feed down and then let it snap back, and it never cleared itself.
        ActionToast(
            message = state.actionMessage,
            onDismiss = { component.store.accept(HomeIntent.DismissMessage) },
            modifier = Modifier.padding(bottom = TabBarInset),
        )

        if (state.resolving) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        expandedRow?.let { row ->
            TmdbRowPage(
                title = row.title,
                items = row.items,
                showReleaseDate = row.title == "即将上映" || row.title == "最新上线",
                onOpen = {
                    component.store.accept(HomeIntent.Open(it))
                    expandedRow = null
                },
                onDismiss = { expandedRow = null },
            )
        }
    }
}

/**
 * 首屏大图 — 390px, edge to edge, starting behind the status bar (§2 首页).
 *
 * The greeting row floats on the artwork rather than sitting above it, and the search
 * entry is a circular button in that row — the full-width search field is gone.
 */
@Composable
private fun HomeHeroCarousel(
    items: List<TmdbItem>,
    userName: String?,
    height: androidx.compose.ui.unit.Dp,
    visible: Boolean,
    onOpenProfile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    onPlay: (TmdbItem) -> Unit,
    onFavorite: (TmdbItem) -> Unit,
    onAccent: (Color) -> Unit,
) {
    val pagerState = rememberPagerState(
        pageCount = { loopingCarouselPageCount(items.size) },
    )
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    // Touching the reel restarts its clock rather than stopping it for good. The pause
    // control this replaces could only be undone by finding it again, so a single swipe
    // left the hero permanently still with a play glyph as the only clue why.
    var interaction by remember { mutableStateOf(0) }

    LaunchedEffect(items.map { it.id }) {
        pagerState.scrollToPage(loopingCarouselStartPage(items.size))
    }
    LaunchedEffect(items.size, carouselDragging, reduceMotion, routeVisible, visible, interaction) {
        // 390dp of artwork moving on its own is the largest single piece of motion in the
        // app, and it was the one thing 减弱动态效果 did not switch off — the setting was
        // honoured in fifteen places and not in the most conspicuous one.
        if (!routeVisible || !visible || items.size <= 1 || carouselDragging || reduceMotion) {
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

    Box(Modifier.fillMaxWidth().height(height)) {
        if (items.isEmpty()) {
            HeroSlide(item = null, onPlay = {}, onFavorite = {})
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .loopingCarouselSemantics(pagerState.currentPage, items.size),
                beyondViewportPageCount = 1,
                key = { page -> page },
            ) { page ->
                val item = items[loopingCarouselItemIndex(page, items.size)]
                val settled = page == pagerState.currentPage
                HeroSlide(
                    item = item,
                    onPlay = { onPlay(item) },
                    onFavorite = { onFavorite(item) },
                    settled = settled,
                    onAccent = onAccent,
                )
            }
        }

        HeroHeader(
            userName = userName,
            onOpenProfile = onOpenProfile,
            onOpenSearch = onOpenSearch,
            onOpenCalendar = onOpenCalendar,
            modifier = Modifier.align(Alignment.TopStart),
        )

        if (items.size > 1) {
            HeroPageIndicator(
                pageCount = items.size,
                selectedPage = loopingCarouselItemIndex(pagerState.currentPage, items.size),
                onPageSelected = { targetIndex ->
                    interaction++
                    carouselScope.launch {
                        val targetPage = loopingCarouselTargetPage(
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = HomeHeroIndicatorBottom),
            )
        }
    }
}

@Composable
private fun HeroSlide(
    item: TmdbItem?,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    settled: Boolean = false,
    onAccent: (Color) -> Unit = {},
) {
    val palette = LocalPalette.current
    val artworkUrls: List<String?> = remember(item) {
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
    }
    val artworkAccent = rememberAnimatedArtworkAccent(
        url = artworkUrls.firstOrNull { it != null },
        fallback = Brand.Primary, // design-system: brand-identity
        darkTheme = palette.isDark,
        identity = item?.id,
    )
    val slideSurface = remember(artworkAccent, palette.isDark) {
        heroSurface(artworkAccent, palette.isDark)
    }
    // Only the slide the reader is actually on gets to colour the page; the pager keeps its
    // neighbours composed, and letting those report would tint the page from a slide that is
    // off screen.
    LaunchedEffect(settled, artworkAccent) {
        if (settled) onAccent(artworkAccent)
    }
    Box(
        Modifier
            .fillMaxSize()
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
                        onClick = onPlay,
                    )
                },
            ),
    ) {
        if (item != null) {
            FallbackImage(
                urls = artworkUrls,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Share the detail hero's exact bottom-to-top colour stops and surface treatment.
        Box(
            Modifier.fillMaxSize().background(
                heroScrim(surface = slideSurface, bottomSurface = pageTint(artworkAccent)),
            ),
        )

        if (item != null) {
            HeroCaption(
                item = item,
                onPlay = onPlay,
                onFavorite = onFavorite,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * Header row over the hero — `gap:10px`; left cluster `gap:9px` with the 30px mark,
 * `下午好` at `400 11px Manrope`, `继续你的旅程` at `800 17px`; 36px search + avatar.
 * Text is white here because it sits on the darkened artwork, not the page.
 */
@Composable
private fun HeroHeader(
    userName: String?,
    onOpenProfile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    "下午好",
                    style = AppTypography.caption.regular.copy(shadow = HeroTextShadow),
                    color = Color.White.copy(alpha = 0.82f),
                )
                Text(
                    "继续你的旅程",
                    style = AppTypography.section.strong.copy(shadow = HeroTextShadow),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        fill = Color.White.copy(alpha = 0.14f),
                        border = Color.White.copy(alpha = 0.34f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Bookmark,
                    "追剧日历",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Box(
            Modifier.size(48.dp).pressable(onClick = onOpenSearch),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .glass(
                        shape = CircleShape,
                        fill = Color.White.copy(alpha = 0.14f),
                        border = Color.White.copy(alpha = 0.34f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Search, "搜索", tint = Color.White, modifier = Modifier.size(17.dp))
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
 * 主按钮「查看详情」+ 收藏。TMDB picks are resolved only after the tap, so promising
 * immediate playback here was inaccurate whenever the title was not in the user's library.
 */
@Composable
private fun HeroCaption(
    item: TmdbItem,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            modifier = Modifier
                .glass(
                    shape = GlassShapes.chip,
                    fill = Color.White.copy(alpha = 0.14f),
                    border = Color.White.copy(alpha = 0.30f),
                )
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = AppTypography.display.strong.copy(shadow = HeroTextShadow),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        // ★ 6.2 · 2012 · 喜剧 · 电影 — the four things a person uses to decide whether to
        // open something, in the order they weigh them. The line used to read
        // 「2012 · 评分 6.2」: the word 评分 spends four characters saying what a star says,
        // and the genre — the single most useful of the four when the title is unfamiliar —
        // was not there at all.
        Row(
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
            val facts = listOfNotNull(item.year, TmdbGenres.labelFor(item.genreIds))
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
            // Boxed rather than another dot-separated word: it is a classification, not
            // another fact about this title, and the reference frames its rating the same way.
            Text(
                if (item.mediaType == "tv") "剧集" else "电影",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                modifier = Modifier
                    .border(
                        width = Dimens.hairline,
                        color = Color.White.copy(alpha = 0.42f),
                        shape = GlassShapes.chip,
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .height(48.dp)
                    .pressable(onClick = onPlay)
                    .glass(
                        shape = GlassShapes.chip,
                        fill = Color(0xFF101722).copy(alpha = 0.30f),
                        border = Color.White.copy(alpha = 0.40f),
                    )
                    .padding(start = 5.dp, end = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
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
            HeroCircleButton(AppIcons.Add, "加入收藏", onFavorite)
        }
    }
}

/** 次级玻璃圆钮 beside the hero's main CTA. */
@Composable
private fun HeroCircleButton(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .pressable(onClick = onClick)
            .size(48.dp)
            .glass(
                shape = CircleShape,
                fill = Color(0xFF11151F).copy(alpha = 0.38f),
                border = Color.White.copy(alpha = 0.34f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

internal fun homeUserInitial(userName: String?): String = userName
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
        modifier = Modifier
            .glass(
                shape = GlassShapes.chip,
                fill = accent.container,
                border = accent.border,
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun NextUpShelf(
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下一集", style = AppTypography.section.strong, color = palette.text)
                    HomeSourceBadge("Emby")
                }
                Text(
                    "继续追你正在看的剧集",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            Text(
                "追剧中心 ›",
                style = AppTypography.caption.strong,
                color = accent.accent,
                modifier = Modifier
                    .pressable(onClickLabel = "打开追剧中心", onClick = onSeeAll)
                    .touchTarget(),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "next-${it.server.id}-${it.item.id}" }) { entry ->
                val item = entry.item
                CaptionedPoster(
                    url = EmbyImages.primary(entry.server.baseUrl, item.posterItemId, item.posterTag, accessToken = entry.server.accessToken),
                    fallbackUrls = emptyList(),
                    title = item.title,
                    year = listOfNotNull(
                        "Emby",
                        entry.server.serverName.takeIf(String::isNotBlank),
                        item.subtitle ?: item.year?.toString(),
                    ).joinToString(" · "),
                    onClick = { onClick(entry) },
                    sharedTransitionKey = MediaSharedElementKey(entry.server.id, item.id),
                    modifier = Modifier.width(MediaSizing.landscapeCardWidth),
                    posterModifier = Modifier.fillMaxWidth().height(MediaSizing.landscapeCardHeight),
                )
            }
        }
    }
}

/** 继续观看 — 150×90 artwork with title/year below and a 3px progress bar. */
@Composable
private fun ContinueWatching(
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("继续观看", style = AppTypography.section.strong, color = palette.text)
                HomeSourceBadge("Emby")
            }
            Text(
                "全部 ›",
                style = AppTypography.caption.medium,
                color = palette.sub2,
                modifier = Modifier
                    .pressable(onClick = onSeeAll)
                    .touchTarget()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(items, key = { "${it.server.id}:${it.item.id}" }) { entry ->
                val item = entry.item
                CaptionedPoster(
                    url = EmbyImages.backdrop(
                        entry.server.baseUrl,
                        item,
                        maxWidth = 480,
                        accessToken = entry.server.accessToken,
                    ),
                    fallbackUrl = EmbyImages.poster(
                        entry.server.baseUrl,
                        item,
                        accessToken = entry.server.accessToken,
                    ),
                    title = item.title,
                    year = listOfNotNull(
                        "Emby",
                        entry.server.serverName.takeIf(String::isNotBlank),
                        item.year?.toString(),
                    ).joinToString(" · "),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    // Keep the home content continuously rendered on pop. A shared-media
                    // overlay can briefly outlive the disposed detail image and flash blank.
                    onClick = { onClick(entry) },
                    sharedTransitionKey = MediaSharedElementKey(entry.server.id, item.id),
                    modifier = Modifier.width(MediaSizing.landscapeCardWidth),
                    posterModifier = Modifier.fillMaxWidth().height(MediaSizing.landscapeCardHeight),
                )
            }
        }
    }
}

/** 为你推荐 — horizontal 2:3 rail; the next card remains visible as a scroll cue. */
@Composable
private fun Recommended(
    title: String,
    items: List<TmdbItem>,
    showReleaseDate: Boolean,
    onSeeAll: () -> Unit,
    onClick: (TmdbItem) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = AppTypography.section.strong, color = palette.text)
                HomeSourceBadge("TMDB")
            }
            Text(
                "全部 ›",
                style = AppTypography.caption.medium,
                color = palette.sub2,
                modifier = Modifier
                    .pressable(onClick = onSeeAll)
                    .touchTarget()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items.take(12), key = { "${it.mediaType}:${it.id}" }) { item ->
                CaptionedPoster(
                    url = TmdbImages.poster(item.posterPath),
                    fallbackUrls = listOfNotNull(
                        TmdbImages.media(item.posterPath),
                        TmdbImages.poster(item.posterPath, "original"),
                        TmdbImages.media(item.posterPath, "original"),
                        TmdbImages.backdrop(item.backdropPath, "w780"),
                        TmdbImages.media(item.backdropPath, "w780"),
                        TmdbImages.backdrop(item.backdropPath, "original"),
                        TmdbImages.media(item.backdropPath, "original"),
                    ),
                    title = item.title,
                    year = "TMDB · " + if (showReleaseDate) {
                        item.releaseDate?.let { "上映 $it" } ?: "上映日期待定"
                    } else {
                        item.year ?: "年份未知"
                    },
                    // The same title can appear in 热门 and 正在上映 at once.
                    // A shared-element key must be unique within a screen, so
                    // shelf posters use the route fade instead of competing for
                    // one shared element (which made the duplicate turn blank).
                    onClick = { onClick(item) },
                    modifier = Modifier.width(MediaSizing.posterRailWidth),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
        }
    }
}

/** 最近添加 — three-column poster wall matching the bottom shelf in the prototype. */
@Composable
private fun RecentAdded(
    items: List<TmdbItem>,
    onSeeAll: () -> Unit,
    onClick: (TmdbItem) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("最近添加", style = AppTypography.section.strong, color = palette.text)
            Text(
                "全部 ›",
                style = AppTypography.caption.medium,
                color = palette.sub2,
                modifier = Modifier
                    .pressable(onClick = onSeeAll)
                    .touchTarget()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        items.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    Poster(
                        url = TmdbImages.poster(item.posterPath),
                        fallbackUrls = listOfNotNull(
                            TmdbImages.media(item.posterPath),
                            TmdbImages.backdrop(item.backdropPath, "w780"),
                            TmdbImages.media(item.backdropPath, "w780"),
                        ),
                        title = item.title,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f).aspectRatio(2f / 3f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
