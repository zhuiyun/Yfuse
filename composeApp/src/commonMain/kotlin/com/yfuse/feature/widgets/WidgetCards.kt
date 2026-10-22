package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.controlBorder
import com.yfuse.core.designsystem.controlSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.feature.widgets.WidgetProgress as LinearProgressIndicator

/** Thirty-six stable presets built from shared artwork/text primitives and distinct arrangements. */
@Composable
fun WidgetCards(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean = true,
    compact: Boolean = false,
    onOpen: (WidgetMedia) -> Unit,
) {
    val display =
        remember(items, metadata) {
            if (metadata) {
                items
            } else {
                items.map {
                    it.copy(year = "", rating = "", description = "", season = null, episode = null)
                }
            }
        }
    WidgetCardLayout(style, display, metadata, compact) { selected ->
        onOpen(items.firstOrNull { it.id == selected.id } ?: selected)
    }
}

@Composable
private fun WidgetCardLayout(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean = true,
    compact: Boolean = false,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    val gap = if (compact) 8.dp else 12.dp
    when (style) {
        WidgetStyle.Landscape, WidgetStyle.Cinema ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items, key = { it.id }) { item ->
                    MediaTile(
                        item,
                        Modifier.width(if (compact) 188.dp else 240.dp),
                        if (style ==
                            WidgetStyle.Cinema
                        ) {
                            2.33f
                        } else {
                            1.78f
                        },
                        metadata = metadata,
                        onOpen = onOpen,
                    )
                }
            }
        WidgetStyle.SideLeft, WidgetStyle.SideRight ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach {
                    InfoRow(
                        it,
                        reverse = style == WidgetStyle.SideRight,
                        metadata = metadata,
                        onOpen = onOpen,
                    )
                }
            }
        WidgetStyle.ImageTop, WidgetStyle.TextTop ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(
                                AppShapes.card,
                            ).glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.controlBorder)
                            .clickable {
                                onOpen(item)
                            },
                    ) {
                        if (style == WidgetStyle.TextTop) MediaCopy(item, Modifier.padding(14.dp), metadata, 3)
                        Artwork(item, Modifier.fillMaxWidth().aspectRatio(1.78f))
                        if (style == WidgetStyle.ImageTop) MediaCopy(item, Modifier.padding(14.dp), metadata, 3)
                    }
                }
            }
        WidgetStyle.Poster, WidgetStyle.ContinuePoster ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items, key = { it.id }) { item ->
                    MediaTile(
                        item,
                        Modifier.width(if (compact) 108.dp else 136.dp),
                        .667f,
                        metadata,
                        progress =
                            style == WidgetStyle.ContinuePoster,
                        onOpen = onOpen,
                    )
                }
            }
        WidgetStyle.DoublePoster ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items.chunked(2)) { pair ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        pair.forEach { MediaTile(it, Modifier.width(120.dp), .667f, metadata, onOpen = onOpen) }
                    }
                }
            }
        WidgetStyle.GridTwo, WidgetStyle.GridThree, WidgetStyle.RankGrid ->
            CardGrid(
                items,
                if (style ==
                    WidgetStyle.GridTwo
                ) {
                    2
                } else {
                    3
                },
                gap,
            ) { item, index ->
                MediaTile(
                    item,
                    Modifier.fillMaxWidth(),
                    .667f,
                    metadata,
                    badge =
                        if (style ==
                            WidgetStyle.RankGrid
                        ) {
                            "${index + 1}"
                        } else {
                            null
                        },
                    onOpen = onOpen,
                )
            }
        WidgetStyle.PosterDetail ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { InfoRow(it, poster = true, metadata = metadata, onOpen = onOpen) }
            }
        WidgetStyle.PosterStack ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items.chunked(3)) { group ->
                    Column(Modifier.width(190.dp).clickable { onOpen(group.first()) }) {
                        Box(Modifier.height(210.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            group.reversed().forEachIndexed { index, media ->
                                Artwork(
                                    media,
                                    Modifier
                                        .width(126.dp)
                                        .aspectRatio(.667f)
                                        .graphicsLayer {
                                            rotationZ =
                                                (index - 1) * 9f
                                            ; translationX = (index - 1) * 20f
                                        }.clip(AppShapes.thumb),
                                    poster = true,
                                )
                            }
                        }
                        MediaCopy(group.first(), metadata = metadata)
                    }
                }
            }
        WidgetStyle.Hero ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { HeroTile(it, Modifier.fillMaxWidth().height(300.dp), onOpen) }
            }
        WidgetStyle.PeekCarousel, WidgetStyle.CenterPoster -> {
            val pager = rememberPagerState(pageCount = { items.size })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalPager(
                    pager,
                    contentPadding =
                        PaddingValues(
                            horizontal =
                                if (style ==
                                    WidgetStyle.CenterPoster
                                ) {
                                    64.dp
                                } else {
                                    20.dp
                                },
                        ),
                    pageSpacing = 12.dp,
                ) { index ->
                    if (style ==
                        WidgetStyle.CenterPoster
                    ) {
                        MediaTile(
                            items[index],
                            Modifier.fillMaxWidth().graphicsLayer {
                                val distance =
                                    kotlin.math
                                        .abs(
                                            pager.currentPage - index + pager.currentPageOffsetFraction,
                                        ).coerceIn(0f, 1f)
                                scaleX = 1f - distance * .12f
                                scaleY = scaleX
                            },
                            .667f,
                            metadata,
                            onOpen = onOpen,
                        )
                    } else {
                        HeroTile(items[index], Modifier.fillMaxWidth().height(230.dp), onOpen)
                    }
                }
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    color = LocalPalette.current.sub2,
                    style = AppTypography.body.regular,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        WidgetStyle.FeatureThree ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.chunked(3).forEach { group ->
                    HeroTile(group.first(), Modifier.fillMaxWidth().height(240.dp), onOpen)
                    CardGrid(
                        group.drop(1),
                        2,
                        gap,
                    ) { item, _ -> MediaTile(item, Modifier.fillMaxWidth(), 1.78f, metadata, onOpen = onOpen) }
                }
            }
        WidgetStyle.FeatureList ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.chunked(4).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        MediaTile(group.first(), Modifier.weight(1f), .667f, metadata, onOpen = onOpen)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            group.drop(1).forEach { TextEntry(it, onOpen = onOpen) }
                        }
                    }
                }
            }
        WidgetStyle.Magazine ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.chunked(3).forEachIndexed { index, group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        val feature: @Composable () -> Unit = {
                            HeroTile(
                                group.first(),
                                Modifier.weight(1.25f).height(280.dp),
                                onOpen,
                            )
                        }
                        if (index % 2 == 0) feature()
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                            group
                                .drop(
                                    1,
                                ).forEach {
                                    MediaTile(
                                        it,
                                        Modifier.fillMaxWidth(),
                                        1.5f,
                                        metadata = false,
                                        onOpen = onOpen,
                                    )
                                }
                        }
                        if (index % 2 != 0) feature()
                    }
                }
            }
        WidgetStyle.RankRail ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items.size) { index ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${index + 1}",
                            style = AppTypography.display.strong,
                            fontWeight = FontWeight.Black,
                            color = LocalPalette.current.sub2,
                        )
                        MediaTile(items[index], Modifier.width(116.dp), .667f, metadata, onOpen = onOpen)
                    }
                }
            }
        WidgetStyle.RankList ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Rank(index + 1)
                        Box(Modifier.weight(1f)) { InfoRow(item, metadata = metadata, onOpen = onOpen) }
                    }
                }
            }
        WidgetStyle.Podium ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(1, 0, 2).filter { it < items.size }.forEach { index ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaTile(
                                items[index],
                                Modifier.fillMaxWidth(),
                                if (index ==
                                    0
                                ) {
                                    .55f
                                } else {
                                    .75f
                                },
                                metadata = false,
                                badge = "${index + 1}",
                                onOpen = onOpen,
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        if (index ==
                                            0
                                        ) {
                                            38.dp
                                        } else {
                                            22.dp
                                        },
                                    ).glass(
                                        AppShapes.chip,
                                        LocalPalette.current.controlSurface,
                                        LocalPalette.current.controlBorder,
                                    ),
                            )
                        }
                    }
                }
                items.drop(3).forEachIndexed { index, item -> TextEntry(item, "${index + 4}", onOpen) }
            }
        WidgetStyle.RankFeature ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                HeroTile(items.first(), Modifier.fillMaxWidth().height(260.dp), onOpen, "TOP 1")
                items.drop(1).forEachIndexed { index, item -> TextEntry(item, "${index + 2}", onOpen) }
            }
        WidgetStyle.GroupRanks ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items.groupBy { it.group.ifBlank { "全部内容" } }.toList()) { (group, media) ->
                    Column(
                        Modifier
                            .width(
                                260.dp,
                            ).clip(AppShapes.card)
                            .glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.controlBorder)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(group, fontWeight = FontWeight.Bold, color = LocalPalette.current.text)
                        media.forEachIndexed { index, item -> TextEntry(item, "${index + 1}", onOpen) }
                    }
                }
            }
        WidgetStyle.ContinueLandscape ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items, key = {
                    it.id
                }) { MediaTile(it, Modifier.width(254.dp), 1.78f, metadata, progress = true, onOpen = onOpen) }
            }
        WidgetStyle.Episodes ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { InfoRow(it, episode = true, metadata = metadata, onOpen = onOpen) }
            }
        WidgetStyle.NextEpisode ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                HeroTile(
                    items.first(),
                    Modifier.fillMaxWidth().height(240.dp),
                    onOpen,
                    episodeLabel(items.first()).ifBlank { "下一集" },
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    items(items.drop(1)) { MediaTile(it, Modifier.width(180.dp), 1.78f, metadata, onOpen = onOpen) }
                }
            }
        WidgetStyle.Calendar ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items.groupBy { it.date.take(10).ifBlank { "日期未提供" } }.toSortedMap().toList()) { (date, media) ->
                    Column(
                        Modifier
                            .width(
                                170.dp,
                            ).clip(AppShapes.card)
                            .glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.controlBorder)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(date, fontWeight = FontWeight.Bold, color = LocalPalette.current.text)
                        media.forEach { MediaTile(it, Modifier.fillMaxWidth(), 1.78f, metadata, onOpen = onOpen) }
                    }
                }
            }
        WidgetStyle.Timeline ->
            Column {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(LocalPalette.current.sub))
                            Box(Modifier.width(2.dp).height(100.dp).background(LocalPalette.current.controlBorder))
                        }
                        Column(Modifier.weight(1f).padding(bottom = 16.dp)) {
                            if (item.date.isNotBlank()) {
                                Text(
                                    item.date,
                                    style = AppTypography.body.regular,
                                    color = LocalPalette.current.sub2,
                                )
                            }
                            InfoRow(item, metadata = metadata, onOpen = onOpen)
                        }
                    }
                }
            }
        WidgetStyle.Categories ->
            CardGrid(
                items,
                2,
                gap,
            ) { item, _ -> HeroTile(item, Modifier.fillMaxWidth().aspectRatio(1f), onOpen) }
        WidgetStyle.People ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items, key = { it.id }) { item ->
                    Column(
                        Modifier.width(94.dp).clickable { onOpen(item) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(item, Modifier.size(90.dp).clip(CircleShape), poster = true)
                        Text(
                            item.title,
                            color = LocalPalette.current.text,
                            maxLines = 2,
                            style = AppTypography.body.regular,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        WidgetStyle.Tags ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    Text(
                        item.title,
                        color = LocalPalette.current.text,
                        modifier =
                            Modifier
                                .clip(
                                    AppShapes.chip,
                                ).glass(
                                    AppShapes.chip,
                                    LocalPalette.current.controlSurface,
                                    LocalPalette.current.controlBorder,
                                ).clickable {
                                    onOpen(item)
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        WidgetStyle.Collection ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.width(184.dp).clickable { onOpen(item) }) {
                        val covers = item.children.take(4).ifEmpty { listOf(item) }
                        Column(Modifier.clip(AppShapes.card)) {
                            covers.chunked(2).forEach { row ->
                                Row {
                                    row.forEach { Artwork(it, Modifier.weight(1f).aspectRatio(.75f), poster = true) }
                                }
                            }
                        }
                        MediaCopy(item, metadata = metadata)
                    }
                }
            }
        WidgetStyle.Expandable ->
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { item ->
                    var expanded by remember(item.id) { mutableStateOf(false) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(
                                AppShapes.card,
                            ).glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.controlBorder)
                            .padding(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
                            Text(
                                item.title,
                                Modifier.weight(1f),
                                color = LocalPalette.current.text,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(if (expanded) "收起 −" else "展开 ＋", color = LocalPalette.current.sub)
                        }
                        if (expanded) {
                            if (item.children.isEmpty()) {
                                InfoRow(item, metadata = metadata, onOpen = onOpen)
                            } else {
                                item.children.forEach { TextEntry(it, onOpen = onOpen) }
                            }
                        }
                    }
                }
            }
        WidgetStyle.TextList ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { TextEntry(it, onOpen = onOpen) }
            }
    }
}

@Composable
private fun CardGrid(
    items: List<WidgetMedia>,
    columns: Int,
    gap: androidx.compose.ui.unit.Dp,
    content: @Composable (WidgetMedia, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEachIndexed { row, group ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { column ->
                    Box(Modifier.weight(1f)) {
                        group.getOrNull(column)?.let {
                            content(
                                it,
                                row * columns + column,
                            )
                        }
                    }
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
            AsyncImage(
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
    modifier: Modifier = Modifier,
    ratio: Float,
    metadata: Boolean = true,
    progress: Boolean = false,
    badge: String? = null,
    onOpen: (WidgetMedia) -> Unit,
) {
    Column(modifier.clickable { onOpen(item) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(AppShapes.thumb)) {
            Artwork(item, Modifier.fillMaxSize(), poster = ratio < 1f)
            badge?.let {
                Text(
                    it,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .padding(
                                6.dp,
                            ).clip(CircleShape)
                            .background(Color.Black.copy(alpha = .65f))
                            .padding(8.dp),
                )
            }
            if (progress &&
                item.progress != null
            ) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                )
            }
        }
        MediaCopy(item, metadata = metadata)
        if (progress &&
            item.progress != null
        ) {
            Text(
                "已看 ${(item.progress * 100).toInt()}%",
                style = AppTypography.body.regular,
                color = LocalPalette.current.sub2,
            )
        }
    }
}

@Composable
private fun MediaCopy(
    item: WidgetMedia,
    modifier: Modifier = Modifier,
    metadata: Boolean = true,
    lines: Int = 0,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            item.title,
            color = LocalPalette.current.text,
            style = AppTypography.body.regular,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (metadata) {
            val meta =
                listOf(
                    item.year.take(4),
                    item.rating
                        .takeIf(String::isNotBlank)
                        ?.let {
                            "★ $it"
                        }.orEmpty(),
                    episodeLabel(item),
                ).filter(String::isNotBlank).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    color = LocalPalette.current.sub2,
                    style = AppTypography.body.regular,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (lines > 0 &&
                item.description.isNotBlank()
            ) {
                Text(
                    item.description,
                    color = LocalPalette.current.sub,
                    style = AppTypography.body.regular,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    item: WidgetMedia,
    reverse: Boolean = false,
    poster: Boolean = false,
    episode: Boolean = false,
    metadata: Boolean = true,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .glass(AppShapes.card, LocalPalette.current.card, LocalPalette.current.controlBorder)
            .clickable {
                onOpen(item)
            }.padding(
                10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture: @Composable () -> Unit = {
            Artwork(
                item,
                Modifier
                    .width(
                        if (poster) 94.dp else 116.dp,
                    ).aspectRatio(if (poster) .667f else 1.78f)
                    .clip(AppShapes.thumb),
                poster,
            )
        }
        if (!reverse) picture()
        Column(Modifier.weight(1f)) {
            if (episode &&
                episodeLabel(item).isNotBlank()
            ) {
                Text(episodeLabel(item), style = AppTypography.body.regular, color = LocalPalette.current.sub2)
            }
            MediaCopy(item, metadata = metadata, lines = if (poster) 5 else 2)
        }
        if (reverse) picture()
    }
}

@Composable
private fun HeroTile(
    item: WidgetMedia,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
    badge: String? = null,
) {
    Box(modifier.clip(AppShapes.card).clickable { onOpen(item) }) {
        Artwork(item, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f))),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            badge?.let { Text(it, color = Color.White.copy(alpha = .8f), style = AppTypography.body.regular) }
            Text(
                item.title,
                color = Color.White,
                style = AppTypography.display.strong,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    color = Color.White.copy(alpha = .8f),
                    maxLines = 2,
                    style = AppTypography.body.regular,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TextEntry(
    item: WidgetMedia,
    prefix: String? = null,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                onOpen(item)
            }.padding(
                vertical = 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix?.let {
            Text(
                it,
                color = LocalPalette.current.sub2,
                fontWeight = FontWeight.Bold,
                style = AppTypography.section.strong,
            )
        }
        Box(Modifier.weight(1f)) { MediaCopy(item) }
        Text("›", color = LocalPalette.current.hint, style = AppTypography.section.strong)
    }
}

@Composable
private fun Rank(value: Int) {
    Text(
        value.toString().padStart(2, '0'),
        style = AppTypography.display.strong,
        fontWeight = FontWeight.Bold,
        color = LocalPalette.current.sub2,
    )
}

private fun episodeLabel(item: WidgetMedia): String =
    item.episode
        ?.let {
            "${item.season?.let { s -> "S$s · " }.orEmpty()}第 $it 集"
        }.orEmpty()

internal val widgetPreviewItems =
    (1..6).map { index ->
        WidgetMedia(
            id = "preview-$index",
            title = listOf("暮色航线", "山海之间", "星际来信", "雨后小镇", "漫长夏日", "远方的光")[index - 1],
            description = "示例内容，用于预览卡片布局与信息层次。",
            year = "2026",
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
        )
    }
