package com.yfuse.feature.library

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.core.data.FAVORITES_COLLECTION_ID
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.SkeletonRail
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.loopingCarouselItemIndex
import com.yfuse.core.designsystem.loopingCarouselPageCount
import com.yfuse.core.designsystem.loopingCarouselStartPage
import com.yfuse.core.designsystem.loopingCarouselTargetPage
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedDominantColor
import com.yfuse.core.designsystem.rememberScrolledPastHero
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.sharedMediaElement
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hero carousel height. The status-bar switch threshold used to repeat this as a literal,
 * so resizing the hero silently moved the point where the status bar flips its icons.
 */
private val HeroHeight = 432.dp

/** How far the content column is pulled up over the lower edge of the hero. */
private val HeroLift = 52.dp

/** Poster rail column width, shared by the real rails and the loading skeleton. */
private val PosterWidth = 104.dp

/** `transparent 320px` — how far the artwork's tint reaches into the content. */
private val ContentWashHeight = 320.dp

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
    val pagerState = rememberPagerState(
        pageCount = { loopingCarouselPageCount(slides.size) },
    )
    val slideIndex = loopingCarouselItemIndex(pagerState.currentPage, slides.size)
    val carouselDragging by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselScope = rememberCoroutineScope()
    val slide = slides.getOrNull(slideIndex)
    val slideUrl = slide?.let {
        EmbyImages.backdrop(baseUrl, it, accessToken = accessToken)
            ?: EmbyImages.poster(baseUrl, it, accessToken = accessToken)
    }
    val accent = rememberAnimatedDominantColor(slideUrl, Brand.Primary)

    var serverMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lightPageReached by rememberScrolledPastHero(listState, HeroHeight)
    StatusBarIconStyle(darkIcons = (slide == null || lightPageReached) && !palette.isDark)

    LaunchedEffect(slides.map { it.id }) {
        pagerState.scrollToPage(loopingCarouselStartPage(slides.size))
    }
    LaunchedEffect(slides.size, carouselDragging) {
        if (slides.size <= 1 || carouselDragging) return@LaunchedEffect
        while (true) {
            delay(6_000)
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.currentServer == null -> PageHint(
                "还没有默认服务器，请到「我的」添加",
                Modifier.align(Alignment.Center),
            )

            state.error != null && state.content.isEmpty -> ErrorState(
                message = state.error!!,
                onRetry = { store.accept(LibraryIntent.Retry) },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { store.accept(LibraryIntent.Retry) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = TabBarInset),
                ) {
                    if (slide != null) {
                        item {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth().height(HeroHeight),
                                beyondViewportPageCount = 1,
                                key = { page -> page },
                            ) { page ->
                                val animatedIndex = loopingCarouselItemIndex(page, slides.size)
                                val animatedItem = slides.getOrNull(animatedIndex) ?: slide
                                // Backdrop first, poster as the understudy: an item can
                                // carry a backdrop id whose image the server no longer has,
                                // and the hero used to go blank rather than fall back.
                                val animatedUrls = listOf(
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
                                val animatedAccent = rememberAnimatedDominantColor(
                                    animatedUrls.firstOrNull { it != null },
                                    Brand.Primary,
                                )
                                HeroCarousel(
                                    item = animatedItem,
                                    urls = animatedUrls,
                                    accent = animatedAccent,
                                    slideCount = slides.size,
                                    slideIndex = animatedIndex,
                                    onSelectSlide = { targetIndex ->
                                        carouselScope.launch {
                                            pagerState.animateScrollToPage(
                                                loopingCarouselTargetPage(
                                                    currentPage = pagerState.currentPage,
                                                    targetIndex = targetIndex,
                                                    itemCount = slides.size,
                                                ),
                                            )
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
                        val liftPx = with(density) { HeroLift.roundToPx() }
                        // Content wash `…transparent 320px`: the blend from the artwork into
                        // the page has to be a fixed height. Fractional stops made it a
                        // fraction of the whole content column, so a server with two
                        // libraries got a thin smear and one with a dozen got a wash halfway
                        // down the page — the same gradient reading differently per server.
                        val wash = remember(accent, palette.background, density) {
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.16f to accent.copy(alpha = 0.10f),
                                    0.34f to palette.background.copy(alpha = 0.86f),
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
                                }
                                .background(wash)
                                .padding(top = 78.dp),
                            verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                        ) {
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

                            if (state.content.resume.isNotEmpty()) {
                                PlaybackHistory(
                                    baseUrl = baseUrl,
                                    accessToken = accessToken,
                                    items = state.content.resume,
                                    onItemClick = { component.onOpenItem(it.id) },
                                )
                            }

                            state.content.rows.filter { it.items.isNotEmpty() }.forEach { row ->
                                CategorySection(
                                    baseUrl = baseUrl,
                                    accessToken = accessToken,
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

        if (state.loading && state.content.isEmpty && state.currentServer != null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Quiet end-of-page summary backed by Emby's full-library counts, not preview rows. */
@Composable
private fun LibraryCountFooter(movieCount: Int, seriesCount: Int) {
    Text(
        text = "电影 $movieCount 部 · 剧集 $seriesCount 部",
        style = mr(11f, 500),
        color = LocalPalette.current.sub2,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal, vertical = 20.dp),
    )
}

/**
 * 432px hero — scrim `0deg rgba(10,14,26,.88) 0%, .55 42%, .05 62%, transparent`;
 * 正在流行 chip at `left/top 20/52`; server switcher at `right/top 20/52`;
 * copy block at `left/right 20`, `bottom 34`; dots at `left 20`, `bottom 14`.
 */
@Composable
private fun HeroCarousel(
    item: MediaItem,
    urls: List<String?>,
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
            .height(HeroHeight)
            .pressable(onClick = onClick),
    ) {
        FallbackImage(
            urls = urls,
            contentDescription = item.title,
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
                    .pressable(onClick = onToggleServerMenu)
                    .glass(
                        shape = GlassShapes.chip,
                        fill = Color(0xFF141826).copy(alpha = 0.36f),
                        border = Color.White.copy(alpha = 0.30f),
                    )
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
                        .pressable(onClick = onClick)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = Color(0xFF101722).copy(alpha = 0.30f),
                            border = Color.White.copy(alpha = 0.40f),
                        )
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
                    active = item.isFavorite,
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
                        .pressable { onSelectSlide(index) }
                        .glass(
                            shape = RoundedCornerShape(3.dp),
                            fill = if (active) {
                                Color.White.copy(alpha = 0.88f)
                            } else {
                                Color.White.copy(alpha = 0.24f)
                            },
                            border = Color.White.copy(alpha = if (active) 0.84f else 0.28f),
                        ),
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
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "切换服务器",
            subtitle = "已登录 ${servers.size} 个 · 切换后重新载入媒体库",
            onClose = onDismiss,
        )
        // A centred panel has no edge to grow against, so a long server list scrolls
        // inside the dialog instead of running off both ends of the screen.
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            servers.forEach { server ->
                val isCurrent = server.id == currentId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(enabled = !isCurrent) { onSelect(server.id) }
                        .glass(
                            shape = GlassShapes.chip,
                            fill = if (isCurrent) {
                                Brand.Primary.copy(alpha = 0.10f)
                            } else {
                                palette.card2
                            },
                            border = if (isCurrent) {
                                Brand.Primary.copy(alpha = 0.30f)
                            } else {
                                palette.border
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(serverTileColor(server.id)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            server.serverName.take(1).uppercase(),
                            style = mr(12f, 700),
                            color = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            server.serverName,
                            style = sc(12.5f, 700),
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
                                style = mr(10f, 400),
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
                            tint = Brand.Primary,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        Text("切换", style = mr(11f, 600), color = Brand.Primary)
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

private val ServerTileColors = listOf(
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
            val personalIcon = when (row.libraryId) {
                FAVORITES_COLLECTION_ID -> AppIcons.Heart
                WATCH_LATER_COLLECTION_ID -> AppIcons.Bookmark
                else -> null
            }
            val coverUrl = cover?.let {
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
                        if (coverUrl == null && personalIcon != null) Color(0xFF4C5F83)
                        else palette.card2,
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
    Column {
        SectionHeader("播放记录")
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
                    // Scoped to this rail: a film that was just watched is also a film
                    // that was just added, so the same id is on screen twice — see
                    // [CategorySection] for what that costs.
                    sharedKey = "media-poster-resume-${item.id}",
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(190.dp),
                    posterModifier = Modifier.fillMaxWidth().height(114.dp),
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
            style = sc(15f, 700),
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
                    .pressable(onClick = onSeeAll)
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("更多", style = mr(11f, 500), color = palette.sub2)
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
                    sharedKey = "media-poster-${row.libraryId}-${item.id}",
                    onClick = { onItemClick(item) },
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
internal fun PosterCard(
    baseUrl: String,
    accessToken: String,
    item: MediaItem,
    showProgress: Boolean,
    onClick: () -> Unit,
) {
    CaptionedPoster(
        url = EmbyImages.poster(baseUrl, item, accessToken = accessToken),
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
    /** Non-null for a toggle, so the glyph can answer being switched on. */
    active: Boolean? = null,
) {
    Box(
        Modifier
            .size(34.dp)
            .pressable(
                haptic = if (active != null) HapticSignal.Confirm else null,
                onClick = onClick,
            )
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
