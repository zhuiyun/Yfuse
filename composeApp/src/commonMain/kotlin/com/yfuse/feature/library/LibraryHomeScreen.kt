package com.yfuse.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.delay

/** Carousel dwell time — the prototype advances every 4s. */
private const val CAROUSEL_INTERVAL_MS = 4_000L

/** Height of the hero's fade into the page colour; the copy block clears it. */
private val HERO_FADE = 96.dp

/** 媒体库 — a 432px hero carousel above `padding:16px 18px 100px; gap:22px` of rows. */
@Composable
fun LibraryHomeScreen(component: LibraryHomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val baseUrl = state.currentServer?.baseUrl.orEmpty()
    val palette = LocalPalette.current

    val slides = state.content.featured.take(4)
    var slideIndex by remember(slides.size) { mutableStateOf(0) }
    LaunchedEffect(slides.size) {
        if (slides.size > 1) {
            while (true) {
                delay(CAROUSEL_INTERVAL_MS)
                slideIndex = (slideIndex + 1) % slides.size
            }
        }
    }
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
    StatusBarIconStyle(darkIcons = slide == null || lightPageReached)

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
                TextButton(onClick = { store.accept(LibraryIntent.Retry) }) {
                    Text("重试", style = sc(13f, 700), color = Brand.Primary)
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().hideBottomBarOnScroll(),
                state = listState,
                contentPadding = PaddingValues(bottom = TabBarInset),
            ) {
                if (slide != null) {
                    item {
                        HeroCarousel(
                            item = slide,
                            url = slideUrl,
                            accent = accent,
                            slideCount = slides.size,
                            slideIndex = slideIndex,
                            onSelectSlide = { slideIndex = it },
                            serverName = state.currentServer?.serverName.orEmpty(),
                            onClick = { component.onOpenItem(slide.id) },
                            onToggleServerMenu = { serverMenuOpen = !serverMenuOpen },
                        )
                    }
                }

                item {
                    // Content wash: `linear-gradient(180deg,{accent 12%} 0,transparent 320px)`.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                cssLinearGradient(
                                    180f,
                                    0f to accent.copy(alpha = 0.12f),
                                    1f to Color.Transparent,
                                ),
                            )
                            .padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
                    ) {
                        if (state.content.rows.isNotEmpty()) {
                            CategoryCards(
                                baseUrl = baseUrl,
                                rows = state.content.rows,
                                onOpen = { component.onSeeAll(it.libraryId, it.title) },
                            )
                        }

                        if (state.loading && state.content.isEmpty) {
                            SkeletonRow()
                        }

                        state.content.rows.forEach { row ->
                            CategorySection(
                                baseUrl = baseUrl,
                                row = row,
                                onSeeAll = { component.onSeeAll(row.libraryId, row.title) },
                                onItemClick = { component.onOpenItem(it.id) },
                            )
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
    onToggleServerMenu: () -> Unit,
) {
    // Exactly what the content area starts on: the page's mid stop under the
    // carousel's 12% accent wash. Matching it makes the seam disappear.
    val pageColor = accent.copy(alpha = 0.12f)
        .compositeOver(LocalPalette.current.backgroundStops[1].second)
    Box(Modifier.fillMaxWidth().height(432.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = url,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
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
        // The prototype's hero ends on a hard dark edge against the light page.
        // This band fades that edge into the page colour instead.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(HERO_FADE)
                .background(
                    cssLinearGradient(
                        180f,
                        0f to pageColor.copy(alpha = 0f),
                        1f to pageColor,
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
                    .clip(RoundedCornerShape(20.dp))
                    .background(accent.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )

            // Server switcher — `rgba(20,24,38,.45)` over `rgba(255,255,255,.25)`,
            // `radius:14px`, `padding:7px 12px`, `gap:6px`.
            Row(
                Modifier
                    .clip(GlassShapes.chip)
                    .background(Color(0xFF141826).copy(alpha = 0.45f))
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
                .padding(start = 20.dp, end = 20.dp, bottom = 34.dp + HERO_FADE),
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
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.92f))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Play, null, tint = Color(0xFF1B2436), modifier = Modifier.size(12.dp))
                Text("立即播放", style = sc(12f, 700), color = Color(0xFF1B2436))
            }
        }

        // Dots — active `16×6`, idle `6×6`, `radius:3px`, `gap:4px`.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 14.dp + HERO_FADE),
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
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) Color.White else Color.White.copy(alpha = 0.4f))
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
                    .clip(GlassShapes.chipSmall)
                    .background(if (isCurrent) Brand.Primary.copy(alpha = 0.1f) else Color.Transparent)
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
 * Category chips — 118×70, `radius:14px`, the library's own artwork cropped to fill
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
                    .width(118.dp)
                    .height(70.dp)
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
                    style = sc(12f, 700),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 8.dp, end = 10.dp),
                )
                Text(
                    "${row.totalCount}部",
                    style = mr(9f, 600),
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 8.dp),
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
                style = sc(15f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "查看更多 ›",
                style = mr(11f, 600),
                color = Brand.Primary,
                modifier = Modifier.clickable(onClick = onSeeAll),
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
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(110.dp),
                    posterModifier = Modifier.fillMaxWidth().height(150.dp),
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        posterModifier = Modifier.fillMaxWidth().height(150.dp),
    )
}
