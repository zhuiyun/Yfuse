package com.yfuse.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.app.hideBottomBarOnScroll
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaElement
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 媒体库 — a 432px hero carousel above `padding:16px 18px 100px; gap:22px` of rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(component: LibraryHomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val baseUrl = state.currentServer?.baseUrl.orEmpty()
    val palette = LocalPalette.current

    val slides = state.content.featured.take(4)
    val pagerState = rememberPagerState(pageCount = { slides.size.coerceAtLeast(1) })
    val slideIndex = pagerState.currentPage.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    val slide = slides.getOrNull(slideIndex)
    val slideUrl = slide?.let { EmbyImages.backdrop(baseUrl, it) ?: EmbyImages.poster(baseUrl, it) }
    val accent = rememberDominantColor(slideUrl, Brand.Primary)

    var serverMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lightPageReached by remember(listState, density) {
        derivedStateOf {
            val switchOffset = with(density) { (432.dp - 56.dp).roundToPx() }
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset >= switchOffset
        }
    }
    StatusBarIconStyle(darkIcons = (slide == null || lightPageReached) && !palette.isDark)

    LaunchedEffect(slides.size, carouselDragging) {
        if (slides.size <= 1 || carouselDragging) return@LaunchedEffect
        while (true) {
            delay(6_000)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % slides.size)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.currentServer == null -> CenterHint(
                "还没有默认服务器，请到「我的」添加",
                Modifier.align(Alignment.Center),
            )

            state.error != null && state.content.isEmpty -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, style = sc(13f, 400), color = palette.sub, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { store.accept(LibraryIntent.Retry) },
                    modifier = Modifier.glass(
                        shape = GlassShapes.chip,
                        fill = palette.card2,
                        border = palette.border,
                    ),
                ) {
                    Text("重试", style = sc(13f, 700), color = Brand.Primary)
                }
            }

            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { store.accept(LibraryIntent.Retry) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().hideBottomBarOnScroll(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = TabBarInset),
                ) {
                    if (slide != null) {
                        item {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth().height(432.dp),
                                beyondViewportPageCount = 1,
                                key = { page -> slides[page].id },
                            ) { animatedIndex ->
                                val animatedItem = slides.getOrNull(animatedIndex) ?: slide
                                val animatedUrl = EmbyImages.backdrop(baseUrl, animatedItem)
                                    ?: EmbyImages.poster(baseUrl, animatedItem)
                                val animatedAccent =
                                    rememberDominantColor(animatedUrl, Brand.Primary)
                                HeroCarousel(
                                    item = animatedItem,
                                    url = animatedUrl,
                                    accent = animatedAccent,
                                    slideCount = slides.size,
                                    slideIndex = animatedIndex,
                                    onSelectSlide = {
                                        carouselScope.launch {
                                            pagerState.animateScrollToPage(it)
                                        }
                                    },
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
                        }
                    }

                    item {
                        // Content wash: `linear-gradient(180deg,{accent 12%} 0,transparent 320px)`.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .offset(y = (-52).dp)
                                .background(
                                    cssLinearGradient(
                                        180f,
                                        0f to Color.Transparent,
                                        0.16f to accent.copy(alpha = 0.10f),
                                        0.34f to palette.background.copy(alpha = 0.86f),
                                        1f to Color.Transparent,
                                    ),
                                )
                                .padding(top = 78.dp),
                            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                        ) {
                            if (state.loading && state.content.isEmpty) {
                                SkeletonRow()
                            }

                            if (state.content.rows.isNotEmpty()) {
                                CategoryCards(
                                    baseUrl = baseUrl,
                                    rows = state.content.rows,
                                    onOpen = {
                                        component.onSeeAll(it.libraryId, it.title)
                                    },
                                )
                            }

                            if (state.content.resume.isNotEmpty()) {
                                PlaybackHistory(
                                    baseUrl = baseUrl,
                                    accessToken = state.currentServer?.accessToken.orEmpty(),
                                    items = state.content.resume,
                                    onItemClick = { component.onOpenItem(it.id) },
                                )
                            }

                            state.content.rows.forEach { row ->
                                CategorySection(
                                    baseUrl = baseUrl,
                                    row = row,
                                    prominent = false,
                                    onSeeAll = {
                                        component.onSeeAll(row.libraryId, row.title)
                                    },
                                    onItemClick = { component.onOpenItem(it.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (serverMenuOpen) {
            ServerMenu(
                servers = state.servers.map { Triple(it.id, it.serverName, it.id == state.currentServer?.id) },
                onSelect = {
                    store.accept(LibraryIntent.SelectServer(it))
                    serverMenuOpen = false
                },
                onDismiss = { serverMenuOpen = false },
            )
        }

        if (state.loading && state.content.isEmpty && state.currentServer != null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/**
 * 432px hero — scrim `0deg rgba(10,14,26,.88) 0%, .55 42%, .05 62%, transparent`;
 * 正在流行 chip at `left/top 20/52`; server switcher at `right/top 20/52`;
 * copy block at `left/right 20`, `bottom 34`; dots at `left 20`, `bottom 14`.
 */
@Composable
private fun HeroCarousel(
    item: MediaItem,
    url: String?,
    accent: Color,
    slideCount: Int,
    slideIndex: Int,
    onSelectSlide: (Int) -> Unit,
    serverName: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleServerMenu: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(432.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = url,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .sharedMediaElement("media-backdrop-${item.id}"),
        )
        Box(
            Modifier.fillMaxSize().background(
                scrim(
                    0f to Color(0xFF0A0E1A).copy(alpha = 0.88f),
                    0.42f to Color(0xFF0A0E1A).copy(alpha = 0.55f),
                    0.62f to Color(0xFF0A0E1A).copy(alpha = 0.05f),
                    1f to Color.Transparent,
                ),
            ),
        )
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
                style = mr(10.5f, 500),
                color = Color.White,
                modifier = Modifier
                    .glass(
                        shape = RoundedCornerShape(20.dp),
                        fill = accent.copy(alpha = 0.38f),
                        border = Color.White.copy(alpha = 0.30f),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )

            // Server switcher — `rgba(20,24,38,.45)` over `rgba(255,255,255,.25)`,
            // `radius:14px`, `padding:7px 12px`, `gap:6px`.
            Row(
                Modifier
                    .glass(
                        shape = GlassShapes.chip,
                        fill = Color(0xFF141826).copy(alpha = 0.36f),
                        border = Color.White.copy(alpha = 0.30f),
                    )
                    .clickable(onClick = onToggleServerMenu)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Brand.Online))
                Text(
                    serverName,
                    style = sc(12f, 600),
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
                .padding(start = 20.dp, end = 20.dp, bottom = 46.dp),
        ) {
            Text(
                item.title,
                style = sc(26f, 800),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.subtitle,
                    style = mr(12f, 400),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.overview.isNullOrBlank()) {
                // `400 11.5px/1.6`, `rgba(255,255,255,.7)`, clamped to two lines.
                Spacer(Modifier.height(10.dp))
                Text(
                    item.overview,
                    style = sc(11.5f, 400, lineHeight = 11.5f * 1.6f),
                    color = Color.White.copy(alpha = 0.7f),
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
                        .height(42.dp)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = Color(0xFF101722).copy(alpha = 0.30f),
                            border = Color.White.copy(alpha = 0.40f),
                        )
                        .clickable(onClick = onClick)
                        .padding(start = 4.dp, end = 16.dp),
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
                            AppIcons.Play,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text("立即播放", style = sc(12f, 700), color = Color.White)
                }
                HeroCircleAction(
                    icon = if (item.isFavorite) AppIcons.HeartFilled else AppIcons.Heart,
                    description = if (item.isFavorite) "取消收藏" else "加入收藏",
                    onClick = onToggleFavorite,
                )
                HeroCircleAction(AppIcons.Info, "查看详情", onClick)
            }
        }

        // Dots — active `16×6`, idle `6×6`, `radius:3px`, `gap:4px`.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(slideCount) { index ->
                val active = index == slideIndex
                val width by animateFloatAsState(
                    targetValue = if (active) 16f else 6f,
                    animationSpec = tween(250),
                    label = "dot",
                )
                Box(
                    Modifier
                        .width(width.dp)
                        .height(6.dp)
                        .glass(
                            shape = RoundedCornerShape(3.dp),
                            fill = if (active) {
                                Color.White.copy(alpha = 0.88f)
                            } else {
                                Color.White.copy(alpha = 0.24f)
                            },
                            border = Color.White.copy(alpha = if (active) 0.84f else 0.28f),
                        )
                        .clickable { onSelectSlide(index) },
                )
            }
        }
    }
}

/**
 * Server dropdown — `top:96px; right:20px; width:180px`, `rgba(255,255,255,.95)`
 * over `rgba(255,255,255,.9)`, `radius:14px`, `padding:6px`,
 * `0 16px 36px -8px rgba(30,40,70,.3)`.
 */
@Composable
private fun BoxScope.ServerMenu(
    servers: List<Triple<String, String, Boolean>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 56.dp, end = 20.dp)
            .width(180.dp)
            .shadow(Shadows.menu, GlassShapes.chip)
            .glass(GlassShapes.chip, Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.9f))
            .padding(6.dp),
    ) {
        servers.forEach { (id, name, isCurrent) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(
                        shape = GlassShapes.thumb,
                        fill = if (isCurrent) {
                            Brand.Primary.copy(alpha = 0.13f)
                        } else {
                            Color.White.copy(alpha = 0.08f)
                        },
                        border = if (isCurrent) {
                            Brand.Primary.copy(alpha = 0.28f)
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        },
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isCurrent) Brand.Online else Brand.Offline),
                    )
                    Text(
                        name,
                        style = sc(12.5f, if (isCurrent) 700 else 500),
                        color = if (isCurrent) Brand.Primary else Color(0xFF151A22),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) {
                    Icon(AppIcons.Check, null, tint = Brand.Primary, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/**
 * Category cards — 148×88, using the library's own artwork cropped to fill
 * under the `0deg rgba(0,0,0,.35) → transparent 60%` scrim. Tapping one opens that
 * library's grid.
 */
@Composable
private fun CategoryCards(
    baseUrl: String,
    rows: List<HomeRow>,
    onOpen: (HomeRow) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.libraryId }) { row ->
            val cover = row.items.firstOrNull()
            val coverUrl = cover?.let {
                EmbyImages.backdrop(baseUrl, it, maxWidth = 480) ?: EmbyImages.poster(baseUrl, it)
            }
            Box(
                Modifier
                    .width(148.dp)
                    .height(88.dp)
                    .clip(GlassShapes.poster)
                    .clickable { onOpen(row) },
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = row.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
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
                    style = sc(13f, 700),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 10.dp, end = 12.dp),
                )
                Text(
                    "${row.totalCount}部",
                    style = mr(9.5f, 600),
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
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Text(
            "播放记录",
            style = sc(18f, 700),
            color = palette.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(bottom = 11.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                CaptionedPoster(
                    url = EmbyImages.backdrop(
                        baseUrl,
                        item,
                        maxWidth = 640,
                        accessToken = accessToken,
                    ),
                    fallbackUrl = EmbyImages.poster(
                        baseUrl,
                        item,
                        accessToken = accessToken,
                    ),
                    fallbackUrls = listOfNotNull(
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
                    sharedKey = "media-poster-${item.id}",
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(190.dp),
                    posterModifier = Modifier.fillMaxWidth().height(114.dp),
                )
            }
        }
    }
}

/**
 * Category block — header `700 15px` + `查看更多 ›` at `600 11px Manrope` `#3D64C9`,
 * over a 110×150 poster rail with title/year below each artwork.
 */
@Composable
private fun CategorySection(
    baseUrl: String,
    row: HomeRow,
    prominent: Boolean,
    onSeeAll: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
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
            Text(
                row.title,
                style = sc(if (prominent) 18f else 15f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "查看更多 ›",
                style = mr(11f, 600),
                color = Brand.Primary,
                modifier = Modifier
                    .glass(GlassShapes.chip, palette.card2, palette.border)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                CaptionedPoster(
                    url = EmbyImages.poster(baseUrl, item),
                    title = item.title,
                    year = item.year?.toString(),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    sharedKey = "media-poster-${item.id}",
                    onClick = { onItemClick(item) },
                    modifier = Modifier
                        .width(if (prominent) 136.dp else 104.dp),
                )
            }
        }
    }
}

/** Loading skeleton: a title bar then three poster-and-caption placeholders. */
@Composable
private fun SkeletonRow() {
    val palette = LocalPalette.current
    val fill = if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)
    Column(
        Modifier.padding(horizontal = Dimens.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(90.dp).height(16.dp).clip(RoundedCornerShape(6.dp)).background(fill))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                Column(Modifier.width(110.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(GlassShapes.poster)
                            .background(fill),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(fill))
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(42.dp).height(9.dp).clip(RoundedCornerShape(4.dp)).background(fill))
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = sc(13f, 400),
        color = LocalPalette.current.sub,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}

/** Shared poster tile with title/year below, reused by the library grid. */
@Composable
internal fun PosterCard(baseUrl: String, item: MediaItem, showProgress: Boolean, onClick: () -> Unit) {
    CaptionedPoster(
        url = EmbyImages.poster(baseUrl, item),
        title = item.title,
        year = item.year?.toString(),
        progress = if (showProgress) item.playedPercentage?.let { (it / 100.0).toFloat() } else null,
        sharedKey = "media-poster-${item.id}",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeroCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .glass(
                shape = CircleShape,
                fill = Color.White.copy(alpha = 0.14f),
                border = Color.White.copy(alpha = 0.34f),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}
