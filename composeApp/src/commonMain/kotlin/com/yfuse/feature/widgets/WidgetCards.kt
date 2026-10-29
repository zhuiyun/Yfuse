package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.controlBorder
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.feature.widgets.WidgetProgress as LinearProgressIndicator

/** Persistent style IDs share one horizontal lane; item count never determines its height. */
@Composable
fun WidgetCards(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean = true,
    compact: Boolean = false,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    when (style) {
        WidgetStyle.OrbitResume -> {
            key(style) { WidgetOrbitResume(items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.BreakoutPoster -> {
            key(style) { WidgetBreakoutPoster(items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.PrismGallery -> {
            key(style) { WidgetPrismGallery(items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.RibbonGallery -> {
            key(style) { WidgetRibbonGallery(items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.LayeredPoster, WidgetStyle.Accordion, WidgetStyle.ArcPoster,
        WidgetStyle.BookSpine, WidgetStyle.DepthGallery, WidgetStyle.FoldingGallery,
        -> {
            key(style) { WidgetKineticLayouts(style, items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.BigRank, WidgetStyle.StaggeredColumns, WidgetStyle.Borderless,
        WidgetStyle.PosterWaterfall, WidgetStyle.MirrorPoster, WidgetStyle.OverlapInfo,
        WidgetStyle.DoubleFeature, WidgetStyle.FloatingPoster, WidgetStyle.StaggeredRail,
        WidgetStyle.BannerStack,
        -> {
            key(style) { WidgetGalleryLayouts(style, items, metadata, compact, onOpen) }
            return
        }
        WidgetStyle.NextDirect, WidgetStyle.SeasonShowcase, WidgetStyle.EpisodePreview,
        WidgetStyle.EpisodeMatrix, WidgetStyle.DecadeShelf,
        -> {
            key(style) { WidgetEpisodeLayouts(style, items, metadata, compact, onOpen) }
            return
        }
        else -> Unit
    }
    val display =
        remember(items, metadata, style) {
            val media =
                if (metadata) {
                    items
                } else {
                    items.map {
                        it.copy(year = "", rating = "", description = "", season = null, episode = null)
                    }
                }
            when (style) {
                WidgetStyle.Calendar -> media.sortedBy { it.date.take(10) }
                WidgetStyle.GroupRanks -> media.groupBy { it.group }.values.flatten()
                else -> media
            }
        }
    if (display.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val metrics = widgetRailMetrics(style, compact, metadata, maxWidth)
        val groupRanks =
            remember(display) {
                val ranks = mutableMapOf<String, Int>()
                display.map { item ->
                    val rank = ranks.getOrElse(item.group) { 0 } + 1
                    ranks[item.group] = rank
                    rank
                }
            }
        val open: (WidgetMedia) -> Unit = { selected ->
            onOpen(items.firstOrNull { it.id == selected.id } ?: selected)
        }
        key(style) {
            if (style == WidgetStyle.PeekCarousel || style == WidgetStyle.CenterPoster) {
                val pager = rememberPagerState(pageCount = { display.size })
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxWidth().height(metrics.height),
                        contentPadding = PaddingValues(horizontal = metrics.pageInset),
                        pageSpacing = 12.dp,
                        key = { index -> "$index:${display[index].id}" },
                    ) { index ->
                        val distance = kotlin.math.abs(pager.currentPage - index + pager.currentPageOffsetFraction)
                        WidgetRailCard(
                            style,
                            display[index],
                            index,
                            groupRanks[index],
                            metadata,
                            metrics,
                            Modifier.fillMaxSize().graphicsLayer {
                                val scale = 1f - distance.coerceIn(0f, 1f) * .06f
                                scaleX = scale
                                scaleY = scale
                            },
                            open,
                        )
                    }
                    Text(
                        "${pager.currentPage + 1} / ${display.size}",
                        color = LocalPalette.current.sub2,
                        style = AppTypography.caption.regular,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(metrics.height),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    itemsIndexed(display, key = { index, item -> "$index:${item.id}" }) { index, item ->
                        val width =
                            when (style) {
                                WidgetStyle.FeatureThree -> if (index % 3 == 0) metrics.width else metrics.width * .72f
                                WidgetStyle.Magazine -> if (index % 2 == 0) metrics.width else metrics.width * .76f
                                WidgetStyle.RankFeature -> if (index == 0) metrics.width else metrics.width * .72f
                                else -> metrics.width
                            }
                        WidgetRailCard(
                            style,
                            item,
                            index,
                            groupRanks[index],
                            metadata,
                            metrics,
                            Modifier.width(width).fillMaxHeight(),
                            open,
                        )
                    }
                }
            }
        }
    }
}

private data class WidgetRailMetrics(
    val width: Dp,
    val imageHeight: Dp,
    val height: Dp,
    val pageInset: Dp = 0.dp,
)

/** Sizes depend only on presentation and typography, never on media count or visible text. */
@Composable
private fun widgetRailMetrics(
    style: WidgetStyle,
    compact: Boolean,
    metadata: Boolean,
    available: Dp,
): WidgetRailMetrics {
    val bodyLine =
        with(LocalDensity.current) {
            AppTypography.body.regular.lineHeight
                .toDp()
        }
    val sectionLine =
        with(LocalDensity.current) {
            AppTypography.section.strong.lineHeight
                .toDp()
        }

    fun copy(lines: Int = 0): Dp {
        val title = bodyLine * 2
        val details = if (metadata) bodyLine + 3.dp else 0.dp
        val description = if (metadata && lines > 0) bodyLine * lines + 3.dp else 0.dp
        // Leave room for font rounding and custom system-font descenders at the rail edge.
        return title + details + description + 4.dp
    }
    val pagerWidth =
        widgetPagerCardWidth(available, compact, style == WidgetStyle.CenterPoster, LocalWidgetGalleryMaxHeight.current)
    val pageInset = (available - pagerWidth) / 2
    val preferred =
        when (style) {
            WidgetStyle.Landscape, WidgetStyle.Cinema, WidgetStyle.ContinueLandscape -> if (compact) 200.dp else 244.dp
            WidgetStyle.Poster, WidgetStyle.ContinuePoster ->
                if (available >= 600.dp) {
                    if (compact) 140.dp else 168.dp
                } else {
                    if (compact) 108.dp else 136.dp
                }
            WidgetStyle.DoublePoster -> if (compact) 132.dp else 156.dp
            WidgetStyle.GridTwo -> if (compact) 140.dp else 160.dp
            WidgetStyle.GridThree, WidgetStyle.RankGrid -> if (compact) 96.dp else 116.dp
            WidgetStyle.PosterStack -> if (compact) 164.dp else 190.dp
            WidgetStyle.RankRail -> if (compact) 152.dp else 172.dp
            WidgetStyle.Podium -> if (compact) 132.dp else 154.dp
            WidgetStyle.People -> if (compact) 110.dp else 128.dp
            WidgetStyle.Tags -> if (compact) 144.dp else 164.dp
            WidgetStyle.Categories -> if (compact) 164.dp else 194.dp
            WidgetStyle.Collection -> if (compact) 188.dp else 220.dp
            WidgetStyle.Calendar, WidgetStyle.GroupRanks -> if (compact) 204.dp else 236.dp
            WidgetStyle.TextList -> if (compact) 218.dp else 258.dp
            WidgetStyle.Spotlight, WidgetStyle.CinemaTicket, WidgetStyle.SplitFocus -> if (compact) 280.dp else 320.dp
            WidgetStyle.CaptionPoster -> if (compact) 144.dp else 176.dp
            WidgetStyle.DateStamp -> if (compact) 180.dp else 208.dp
            WidgetStyle.CompactRow -> if (compact) 248.dp else 282.dp
            WidgetStyle.CenterPoster, WidgetStyle.PeekCarousel -> available - pageInset * 2
            else -> if (compact) 270.dp else 310.dp
        }
    val width =
        if (style in
            setOf(WidgetStyle.CenterPoster, WidgetStyle.PeekCarousel, WidgetStyle.Poster, WidgetStyle.ContinuePoster)
        ) {
            minOf(preferred, available).coerceAtLeast(1.dp)
        } else {
            widgetRailItemWidth(preferred, available)
        }
    val image =
        when (style) {
            WidgetStyle.Cinema -> width / 2.33f
            WidgetStyle.Poster, WidgetStyle.ContinuePoster, WidgetStyle.GridTwo,
            WidgetStyle.GridThree, WidgetStyle.RankGrid, WidgetStyle.CenterPoster,
            -> width / .667f
            WidgetStyle.DoublePoster -> (width - 20.dp) / .8f
            WidgetStyle.Podium -> (width - 20.dp) / .667f
            WidgetStyle.RankRail -> (width - 40.dp) / .667f
            WidgetStyle.PosterStack -> if (compact) 182.dp else 210.dp
            WidgetStyle.Collection -> if (compact) 156.dp else 180.dp
            WidgetStyle.People -> 90.dp
            WidgetStyle.ImageTop, WidgetStyle.TextTop, WidgetStyle.FeatureList -> (width - 24.dp) / 1.78f
            WidgetStyle.Calendar, WidgetStyle.GroupRanks -> (width - 24.dp) / 1.78f
            WidgetStyle.CaptionPoster -> width / .667f
            WidgetStyle.DateStamp -> (width - 24.dp) / 1.78f
            else -> width / 1.78f
        }
    val heroHeight = maxOf(if (compact) 212.dp else 250.dp, sectionLine * 2 + copy(2) + 64.dp)
    val infoHeight = maxOf(84.dp, copy(2)) + 24.dp
    val height =
        when (style) {
            WidgetStyle.SideLeft, WidgetStyle.SideRight -> infoHeight
            WidgetStyle.PosterDetail -> maxOf(142.dp, copy(3)) + 24.dp
            WidgetStyle.Episodes, WidgetStyle.RankList -> maxOf(84.dp, copy(2) + bodyLine + 8.dp) + 24.dp
            WidgetStyle.Expandable -> maxOf(120.dp, copy(2) + bodyLine + 8.dp) + 24.dp
            WidgetStyle.Timeline -> infoHeight + bodyLine + 18.dp
            WidgetStyle.ImageTop, WidgetStyle.TextTop, WidgetStyle.FeatureList -> image + copy(2) + 32.dp
            WidgetStyle.Hero, WidgetStyle.PeekCarousel, WidgetStyle.FeatureThree, WidgetStyle.Magazine,
            WidgetStyle.RankFeature, WidgetStyle.NextEpisode,
            -> heroHeight
            WidgetStyle.Categories -> maxOf(width, sectionLine * 2 + copy(2) + 40.dp)
            WidgetStyle.DoublePoster -> image + copy() + 28.dp
            WidgetStyle.Podium -> image + copy() + bodyLine + 36.dp
            WidgetStyle.GroupRanks, WidgetStyle.Calendar -> image + copy() + bodyLine + 40.dp
            WidgetStyle.People -> image + copy(1) + 12.dp
            WidgetStyle.Tags -> maxOf(48.dp, bodyLine * 2 + 20.dp)
            WidgetStyle.TextList -> copy(2) + 32.dp
            WidgetStyle.ContinueLandscape, WidgetStyle.ContinuePoster -> image + copy() + bodyLine + 16.dp
            WidgetStyle.Spotlight -> maxOf(if (compact) 218.dp else 256.dp, copy() + 160.dp)
            WidgetStyle.CinemaTicket -> maxOf(148.dp, copy() + sectionLine + 48.dp)
            WidgetStyle.SplitFocus -> maxOf(182.dp, copy() + 138.dp)
            WidgetStyle.CaptionPoster -> maxOf(image, copy() + 190.dp)
            WidgetStyle.DateStamp -> image + copy() + sectionLine * 2 + 48.dp
            WidgetStyle.CompactRow -> maxOf(108.dp, copy() + 24.dp)
            WidgetStyle.Landscape, WidgetStyle.Cinema, WidgetStyle.Poster, WidgetStyle.GridTwo,
            WidgetStyle.GridThree, WidgetStyle.PosterStack, WidgetStyle.CenterPoster,
            WidgetStyle.RankRail, WidgetStyle.RankGrid, WidgetStyle.Collection,
            -> image + copy() + 8.dp
            else -> image + copy() + 8.dp
        }
    return WidgetRailMetrics(width, image, height, pageInset)
}

@Composable
private fun WidgetRailCard(
    style: WidgetStyle,
    item: WidgetMedia,
    index: Int,
    groupRank: Int,
    metadata: Boolean,
    metrics: WidgetRailMetrics,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    // Keep one click target, but let artwork/surfaces own their rounded clips.
    // Clipping the whole tile cuts the first and last metadata glyphs at its bottom corners.
    Box(modifier.clickable { onOpen(item) }) {
        when (style) {
            WidgetStyle.Spotlight -> WidgetSpotlightCard(item, metadata)
            WidgetStyle.CinemaTicket -> WidgetTicketCard(item, metadata, index)
            WidgetStyle.SplitFocus -> WidgetSplitCard(item, metadata)
            WidgetStyle.CaptionPoster -> WidgetCaptionPoster(item, metadata)
            WidgetStyle.DateStamp -> WidgetDateCard(item, metadata)
            WidgetStyle.CompactRow -> WidgetCompactRow(item, metadata)
            WidgetStyle.Landscape, WidgetStyle.Cinema ->
                MediaTile(item, metrics.imageHeight, metadata)
            WidgetStyle.Poster, WidgetStyle.GridTwo, WidgetStyle.GridThree, WidgetStyle.CenterPoster ->
                MediaTile(item, metrics.imageHeight, metadata, poster = true)
            WidgetStyle.ContinueLandscape, WidgetStyle.ContinuePoster ->
                MediaTile(
                    item,
                    metrics.imageHeight,
                    metadata,
                    progress = true,
                    poster =
                        style == WidgetStyle.ContinuePoster,
                )
            WidgetStyle.SideLeft, WidgetStyle.SideRight ->
                InfoCard(item, metadata, reverse = style == WidgetStyle.SideRight)
            WidgetStyle.PosterDetail -> InfoCard(item, metadata, poster = true, descriptionLines = 3)
            WidgetStyle.Episodes -> InfoCard(item, metadata, label = episodeLabel(item).ifBlank { "分集内容" })
            WidgetStyle.ImageTop, WidgetStyle.TextTop, WidgetStyle.FeatureList -> {
                Column(
                    Modifier.fillMaxSize().quietCard().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (style == WidgetStyle.TextTop) MediaCopy(item, metadata = metadata, lines = 2)
                    Box(Modifier.fillMaxWidth().height(metrics.imageHeight).clip(AppShapes.thumb)) {
                        Artwork(item, Modifier.fillMaxSize())
                        if (style ==
                            WidgetStyle.FeatureList
                        ) {
                            Badge(if (index == 0) "主推" else "精选", Modifier.padding(8.dp))
                        }
                    }
                    if (style != WidgetStyle.TextTop) MediaCopy(item, metadata = metadata, lines = 2)
                }
            }
            WidgetStyle.DoublePoster -> {
                Column(Modifier.fillMaxSize().quietCard().padding(10.dp)) {
                    MediaTile(item, metrics.imageHeight, metadata, poster = true)
                }
            }
            WidgetStyle.PosterStack -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverStack(item, metrics.imageHeight, fan = true)
                    MediaCopy(item, metadata = metadata)
                }
            }
            WidgetStyle.Hero, WidgetStyle.PeekCarousel, WidgetStyle.FeatureThree,
            WidgetStyle.Magazine, WidgetStyle.RankFeature, WidgetStyle.NextEpisode,
            WidgetStyle.Categories,
            -> {
                val badge =
                    when (style) {
                        WidgetStyle.RankFeature -> "TOP ${index + 1}"
                        WidgetStyle.NextEpisode -> if (index == 0) "下一集" else episodeLabel(item).ifBlank { "后续剧集" }
                        WidgetStyle.FeatureThree -> if (index % 3 == 0) "焦点推荐" else null
                        WidgetStyle.Magazine -> "精选 · ${(index + 1).toString().padStart(2, '0')}"
                        else -> null
                    }
                HeroTile(item, metadata, badge)
            }
            WidgetStyle.RankRail -> {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        Modifier.width(40.dp).padding(bottom = 4.dp),
                        color = LocalAccentColors.current.accent,
                        style = AppTypography.section.strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.weight(1f)) { MediaTile(item, metrics.imageHeight, metadata, poster = true) }
                }
            }
            WidgetStyle.RankList -> InfoCard(item, metadata, label = "TOP ${index + 1}")
            WidgetStyle.Podium -> {
                Column(
                    Modifier.fillMaxSize().quietCard().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RailLabel(if (index == 0) "01 · 榜首" else "${(index + 1).toString().padStart(2, '0')} · 榜单")
                    MediaTile(item, metrics.imageHeight, metadata, poster = true)
                }
            }
            WidgetStyle.RankGrid ->
                MediaTile(
                    item,
                    metrics.imageHeight,
                    metadata,
                    badge = "${index + 1}",
                    poster = true,
                )
            WidgetStyle.GroupRanks, WidgetStyle.Calendar -> {
                Column(
                    Modifier.fillMaxSize().quietCard().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RailLabel(
                        if (style == WidgetStyle.Calendar) {
                            item.date.take(10).ifBlank { "日期未提供" }
                        } else {
                            "${item.group.ifBlank { "全部内容" }} · TOP $groupRank"
                        },
                    )
                    MediaTile(item, metrics.imageHeight, metadata)
                }
            }
            WidgetStyle.Timeline -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LocalAccentColors.current.accent))
                        RailLabel(item.date.take(10).ifBlank { "最近更新" })
                        Box(Modifier.weight(1f).height(1.dp).background(LocalPalette.current.controlBorder))
                    }
                    Box(Modifier.weight(1f)) { InfoCard(item, metadata) }
                }
            }
            WidgetStyle.People -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Artwork(item, Modifier.size(90.dp).clip(CircleShape), poster = true)
                    MediaCopy(item, metadata = metadata, lines = 1, centered = true)
                }
            }
            WidgetStyle.Tags -> {
                Box(
                    Modifier.fillMaxSize().quietCard(AppShapes.chip).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.title,
                        color = LocalPalette.current.text,
                        style = AppTypography.body.medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            WidgetStyle.Collection -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverStack(item, metrics.imageHeight)
                    MediaCopy(item, metadata = metadata)
                }
            }
            WidgetStyle.Expandable ->
                InfoCard(
                    item,
                    metadata,
                    poster = true,
                    label =
                        if (item.children.isEmpty()) {
                            item.group.ifBlank {
                                "内容合集"
                            }
                        } else {
                            "${item.children.size} 项内容 · 查看全部"
                        },
                )
            WidgetStyle.TextList -> {
                Row(
                    Modifier.fillMaxSize().quietCard().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaCopy(item, Modifier.weight(1f), metadata, lines = 2)
                    Text("›", color = LocalPalette.current.sub2, style = AppTypography.section.medium)
                }
            }
            else -> MediaTile(item, metrics.imageHeight, metadata)
        }
    }
}

@Composable
private fun Modifier.quietCard(shape: androidx.compose.ui.graphics.Shape = AppShapes.card): Modifier =
    clip(shape).glass(shape, LocalPalette.current.card, LocalPalette.current.controlBorder)

@Composable
private fun RailLabel(text: String) {
    Text(
        text,
        style = AppTypography.body.medium,
        color = LocalAccentColors.current.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Badge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier
            .clip(
                AppShapes.chip,
            ).glass(AppShapes.chip, LocalPalette.current.controlSurface, LocalPalette.current.controlBorder)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = LocalAccentColors.current.accent,
        style = AppTypography.caption.strong,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Overlapping cover decoration remains inside one fixed-size, singly clickable collection card. */
@Composable
private fun CoverStack(
    item: WidgetMedia,
    height: Dp,
    fan: Boolean = false,
) {
    val covers = remember(item) { (listOf(item) + item.children).take(3) }
    Box(Modifier.fillMaxWidth().height(height).clip(AppShapes.thumb)) {
        if (fan) {
            repeat(3) { layer ->
                val cover = covers.getOrElse(2 - layer) { item }
                Artwork(
                    cover,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxHeight(.9f)
                        .fillMaxWidth(.68f)
                        .graphicsLayer {
                            rotationZ = (1 - layer) * 7f
                            translationX = (1 - layer) * 15.dp.toPx()
                        }.clip(AppShapes.thumb),
                    poster = true,
                )
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    Artwork(covers.getOrElse(index) { item }, Modifier.weight(1f).fillMaxHeight(), poster = true)
                }
            }
        }
    }
}

@Composable
internal fun Artwork(
    item: WidgetMedia,
    modifier: Modifier = Modifier,
    poster: Boolean = false,
) {
    val colors = listOf(Color(0xFF274C65), Color(0xFF744B61), Color(0xFF3D6255), Color(0xFF635B7C))
    val tint = colors[(item.id.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    tint,
                    tint.copy(
                        red = tint.red * .4f,
                        green = tint.green * .4f,
                        blue =
                            tint.blue * .4f,
                    ),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (item.id.startsWith("preview-") || item.id.startsWith("demo-")) {
            WidgetPreviewArtwork(item.id, Modifier.matchParentSize())
        } else {
            Text(
                item.title.take(12),
                color = Color.White.copy(alpha = .7f),
                style = AppTypography.section.strong,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(12.dp),
                maxLines = 2,
            )
        }
        val url =
            (if (poster) item.poster.ifBlank { item.backdrop } else item.backdrop.ifBlank { item.poster }).takeIf {
                it.startsWith("https://") ||
                    it.startsWith("http://")
            }
        if (url !=
            null
        ) {
            WidgetArtworkImage(
                model = url,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun MediaTile(
    item: WidgetMedia,
    imageHeight: Dp,
    metadata: Boolean,
    progress: Boolean = false,
    badge: String? = null,
    poster: Boolean = false,
    dense: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 8.dp)) {
        Box(Modifier.fillMaxWidth().height(imageHeight).clip(AppShapes.thumb)) {
            Artwork(item, Modifier.fillMaxSize(), poster = poster)
            badge?.let { Badge(it, Modifier.padding(7.dp)) }
            if (progress && item.progress != null) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                )
            }
        }
        MediaCopy(item, metadata = metadata, dense = dense)
        if (progress) {
            Text(
                item.progress?.let { "已看 ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: "未记录进度",
                style = AppTypography.body.regular,
                color = LocalPalette.current.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun WidgetGridCard(
    item: WidgetMedia,
    metadata: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clickable { onOpen(item) }) {
        val poster = item.mediaType !in setOf("video", "live")
        MediaTile(item, if (poster) maxWidth / .667f else maxWidth / 1.3f, metadata, poster = poster, dense = true)
    }
}

@Composable
internal fun MediaCopy(
    item: WidgetMedia,
    modifier: Modifier = Modifier,
    metadata: Boolean = true,
    lines: Int = 0,
    centered: Boolean = false,
    dense: Boolean = false,
    minTitleLines: Int = 2,
    metadataText: String? = null,
) {
    val alignment = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 3.dp)) {
        Text(
            item.title,
            Modifier.fillMaxWidth(),
            color = LocalPalette.current.text,
            style = AppTypography.body.medium,
            minLines = minTitleLines.coerceIn(1, 2),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = alignment,
        )
        if (metadata) {
            val meta =
                metadataText
                    ?: listOf(
                        item.year.take(4),
                        item.rating
                            .takeIf(String::isNotBlank)
                            ?.let { "★ $it" }
                            .orEmpty(),
                        episodeLabel(item),
                    ).filter(String::isNotBlank)
                        .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    Modifier.fillMaxWidth(),
                    color = LocalPalette.current.sub2,
                    style = if (dense) AppTypography.caption.regular else AppTypography.body.regular,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = alignment,
                )
            }
            if (lines > 0) {
                Text(
                    item.description,
                    Modifier.fillMaxWidth(),
                    color = LocalPalette.current.sub,
                    style = AppTypography.body.regular,
                    minLines = lines,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = alignment,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    item: WidgetMedia,
    metadata: Boolean,
    reverse: Boolean = false,
    poster: Boolean = false,
    label: String? = null,
    descriptionLines: Int = 2,
) {
    Row(
        Modifier.fillMaxSize().quietCard().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture: @Composable () -> Unit = {
            Artwork(
                item,
                Modifier
                    .width(
                        if (poster) 80.dp else 100.dp,
                    ).height(if (poster) 120.dp else 70.dp)
                    .clip(AppShapes.thumb),
                poster,
            )
        }
        if (!reverse) picture()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label?.let { RailLabel(it) }
            MediaCopy(item, metadata = metadata, lines = descriptionLines)
        }
        if (reverse) picture()
    }
}

@Composable
private fun HeroTile(
    item: WidgetMedia,
    metadata: Boolean,
    badge: String?,
) {
    Box(Modifier.fillMaxSize().clip(AppShapes.card)) {
        Artwork(item, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            badge?.let { Badge(it) }
            Text(
                item.title,
                color = Color.White,
                style = AppTypography.section.strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata) {
                val meta =
                    listOf(
                        item.year.take(4),
                        item.rating
                            .takeIf(String::isNotBlank)
                            ?.let { "★ $it" }
                            .orEmpty(),
                        episodeLabel(item),
                    ).filter(String::isNotBlank)
                        .joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        color = Color.White.copy(alpha = .85f),
                        style = AppTypography.body.regular,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        color = Color.White.copy(alpha = .82f),
                        maxLines = 2,
                        style = AppTypography.body.regular,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun episodeLabel(item: WidgetMedia): String =
    item.episode?.let { "${item.season?.let { s -> "S$s · " }.orEmpty()}第 $it 集" }.orEmpty()

internal val widgetPreviewItems =
    (1..6).map { index ->
        WidgetMedia(
            id = "preview-$index",
            title = listOf("暮色航线", "山海之间", "星际来信", "雨后小镇", "漫长夏日", "远方的光")[index - 1],
            description = "示例内容，用于预览卡片布局与信息层次。",
            year = listOf("2026", "2018", "2007", "1998", "1986", "2024")[index - 1],
            rating = "8.$index",
            date = "2026-10-${20 + index}",
            group =
                if (index % 2 ==
                    0
                ) {
                    "电影"
                } else {
                    "剧集"
                },
            progress = index / 8f,
            season = 1,
            episode = index,
            mediaType = if (index % 2 == 1) "tv" else "movie",
            nextEpisodeId = if (index % 2 == 1) "preview-$index-episode-4" else null,
            children =
                if (index % 2 == 1) {
                    (1..8).map { number ->
                        WidgetMedia(
                            id = "preview-$index-episode-$number",
                            title = "第 $number 集 · 示例剧情",
                            mediaType = "tv",
                            season = if (number <= 4) 1 else 2,
                            episode = if (number <= 4) number else number - 4,
                            played = number < 3,
                            progress = if (number == 3) .45f else null,
                            available = true,
                        )
                    }
                } else {
                    emptyList()
                },
        )
    }
