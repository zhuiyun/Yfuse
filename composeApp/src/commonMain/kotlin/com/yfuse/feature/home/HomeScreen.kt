package com.yfuse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.PrimaryGradient
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.CloudPlayerLogo
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselPageCount
import com.yfuse.core.designsystem.loopingCarouselStartPage
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.sharedMediaElement
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页 — the prototype's `isHome` screen:
 * `padding:52px 18px 100px; gap:22px`, greeting, search entry, hero, 继续观看, 为你推荐.
 */
@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
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

    Box(Modifier.fillMaxSize()) {
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
                    onOpenProfile = component.onOpenProfile,
                    onOpenSearch = component.onOpenSearch,
                    onOpenCalendar = component.onOpenCalendar,
                    onPlay = { component.store.accept(HomeIntent.Open(it)) },
                    onFavorite = { component.store.accept(HomeIntent.Favorite(it)) },
                )
            }

            if (state.loading && state.content.isEmpty) {
                item(key = "recommendations-loading") {
                    Box(Modifier.fillMaxWidth().height(96.dp)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
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
                            style = sc(11.5f, 550),
                            color = palette.sub,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "重新刷新",
                            style = sc(11.5f, 700),
                            color = Brand.Primary,
                            modifier = Modifier.clickable {
                                component.store.accept(HomeIntent.Retry)
                            },
                        )
                    }
                }
            }

            state.actionMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = sc(11.5f, 650),
                        color = Brand.Primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.pageHorizontal)
                            .glass(GlassShapes.chip)
                            .padding(10.dp),
                    )
                }
            }

            if (state.resume.isNotEmpty()) {
                item {
                    ContinueWatching(
                        // From the same state as the items themselves — see
                        // [HomeState.server].
                        baseUrl = state.server?.baseUrl.orEmpty(),
                        accessToken = state.server?.accessToken.orEmpty(),
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
    onOpenProfile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    onPlay: (TmdbItem) -> Unit,
    onFavorite: (TmdbItem) -> Unit,
) {
    val pagerState = rememberPagerState(
        pageCount = { loopingCarouselPageCount(items.size) },
    )
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()

    LaunchedEffect(items.map { it.id }) {
        pagerState.scrollToPage(loopingCarouselStartPage(items.size))
    }
    LaunchedEffect(items.size, carouselDragging) {
        if (items.size <= 1 || carouselDragging) return@LaunchedEffect
        while (true) {
            delay(6_000)
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    Box(Modifier.fillMaxWidth().height(390.dp)) {
        if (items.isEmpty()) {
            HeroSlide(item = null, onPlay = {}, onFavorite = {})
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { page -> page },
            ) { page ->
                val item = items[loopingCarouselItemIndex(page, items.size)]
                HeroSlide(
                    item = item,
                    onPlay = { onPlay(item) },
                    onFavorite = { onFavorite(item) },
                )
            }
        }

        HeroHeader(
            onOpenProfile = onOpenProfile,
            onOpenSearch = onOpenSearch,
            onOpenCalendar = onOpenCalendar,
            modifier = Modifier.align(Alignment.TopStart),
        )

        if (items.size > 1) {
            HeroPageIndicator(
                slideCount = items.size,
                slideIndex = loopingCarouselItemIndex(pagerState.currentPage, items.size),
                onSelectSlide = { targetIndex ->
                    carouselScope.launch {
                        pagerState.animateScrollToPage(
                            loopingCarouselTargetPage(
                                currentPage = pagerState.currentPage,
                                targetIndex = targetIndex,
                                itemCount = items.size,
                            ),
                        )
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
            )
        }
    }
}

@Composable
private fun HeroSlide(
    item: TmdbItem?,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (item != null) {
            // The shelves below already walk image.tmdb.org → media.themoviedb.org; the hero
            // was the one artwork on this page still betting everything on the first host,
            // so it was the one that came up blank.
            FallbackImage(
                urls = listOf(
                    TmdbImages.backdrop(item.backdropPath),
                    TmdbImages.media(item.backdropPath, "w1280"),
                    TmdbImages.poster(item.posterPath, "w780"),
                    TmdbImages.media(item.posterPath, "w780"),
                ),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .sharedMediaElement("tmdb-backdrop-${item.id}"),
            )
        }
        // 底部 90% → 透明的深色渐变压暗，保证任意剧照上标题都可读 (§4.1).
        Box(
            Modifier.fillMaxSize().background(
                scrim(
                    0f to Color(0xFF0A0E1A).copy(alpha = 0.90f),
                    0.55f to Color(0xFF0A0E1A).copy(alpha = 0.10f),
                    1f to Color(0xFF0A0E1A).copy(alpha = 0.35f),
                ),
            ),
        )

        if (item != null) {
            HeroCaption(
                item = item,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onInfo = onPlay,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun HeroPageIndicator(
    slideCount: Int,
    slideIndex: Int,
    onSelectSlide: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(slideCount) { index ->
            val width by animateFloatAsState(
                targetValue = if (index == slideIndex) 16f else 6f,
                animationSpec = tween(250),
                label = "home-hero-dot",
            )
            Box(
                Modifier
                    .width(width.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (index == slideIndex) 0.92f else 0.42f))
                    .clickable { onSelectSlide(index) },
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
                Text("下午好", style = mr(11f, 400), color = Color.White.copy(alpha = 0.75f))
                Text(
                    "继续你的旅程",
                    style = sc(17f, 800),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier.size(44.dp).clickable(onClick = onOpenCalendar),
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
            Modifier.size(44.dp).clickable(onClick = onOpenSearch),
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
            Modifier.size(44.dp).clickable(onClick = onOpenProfile),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(PrimaryGradient))
        }
    }
}

/**
 * Hero caption — ✦今日精选 badge, Display 片名, 类型 · 年份, then the action row:
 * 主按钮「立即播放」+ 两个次级玻璃圆钮（收藏 / 详情）, per §4.1.
 */
@Composable
private fun HeroCaption(
    item: TmdbItem,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = Dimens.pageHorizontal, end = Dimens.pageHorizontal, bottom = 22.dp),
    ) {
        Text(
            "✦ 今日精选",
            style = mr(10f, 500),
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
            style = sc(26f, 800),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(item.year, item.rating?.let { "评分 ${(it * 10).toInt() / 10.0}" })
                .joinToString(" · "),
            style = mr(11f, 400),
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .height(44.dp)
                    .glass(
                        shape = GlassShapes.chip,
                        fill = Color(0xFF101722).copy(alpha = 0.30f),
                        border = Color.White.copy(alpha = 0.40f),
                    )
                    .clickable(onClick = onPlay)
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
                        AppIcons.Play,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text("立即播放", style = sc(13f, 700), color = Color.White)
            }
            HeroCircleButton(AppIcons.Add, "加入收藏", onFavorite)
            HeroCircleButton(AppIcons.Info, "查看详情", onInfo)
        }
    }
}

/** 次级玻璃圆钮 beside the hero's main CTA. */
@Composable
private fun HeroCircleButton(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .size(42.dp)
            .glass(
                shape = CircleShape,
                fill = Color(0xFF11151F).copy(alpha = 0.38f),
                border = Color.White.copy(alpha = 0.34f),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** 继续观看 — 150×90 artwork with title/year below and a 3px progress bar. */
@Composable
private fun ContinueWatching(
    baseUrl: String,
    accessToken: String,
    items: List<MediaItem>,
    onSeeAll: () -> Unit,
    onClick: (MediaItem) -> Unit,
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
            Text("继续观看", style = sc(14f, 700), color = palette.text)
            Text(
                "全部 ›",
                style = mr(11f, 500),
                color = palette.sub2,
                modifier = Modifier
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(items, key = { it.id }) { item ->
                CaptionedPoster(
                    url = EmbyImages.backdrop(
                        baseUrl,
                        item,
                        maxWidth = 480,
                        accessToken = accessToken,
                    ),
                    fallbackUrl = EmbyImages.poster(
                        baseUrl,
                        item,
                        accessToken = accessToken,
                    ),
                    title = item.title,
                    year = item.year?.toString(),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    sharedKey = "media-poster-${item.id}",
                    onClick = { onClick(item) },
                    modifier = Modifier.width(150.dp),
                    posterModifier = Modifier.fillMaxWidth().height(90.dp),
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
            Text(title, style = sc(14.5f, 700), color = palette.text)
            Text(
                "全部 ›",
                style = mr(11f, 500),
                color = palette.sub2,
                modifier = Modifier
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
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
                    year = if (showReleaseDate) {
                        item.releaseDate?.let { "上映 $it" } ?: "上映日期待定"
                    } else {
                        item.year
                    },
                    // The same title can appear in 热门 and 正在上映 at once.
                    // A shared-element key must be unique within a screen, so
                    // shelf posters use the route fade instead of competing for
                    // one shared element (which made the duplicate turn blank).
                    sharedKey = null,
                    onClick = { onClick(item) },
                    modifier = Modifier.width(132.dp),
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
            Text("最近添加", style = sc(14.5f, 700), color = palette.text)
            Text(
                "全部 ›",
                style = mr(11f, 500),
                color = palette.sub2,
                modifier = Modifier
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
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
                        sharedKey = null,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f).aspectRatio(2f / 3f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The app mark, sized to the prototype's 30px header slot. */
@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    CloudPlayerLogo(modifier)
}
