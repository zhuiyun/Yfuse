package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.designsystem.ThemeText as Text

/** Art direction stays inside one horizontal viewport, including reflections and overlapping plates. */
@Composable
internal fun WidgetGalleryLayouts(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (items.isEmpty()) return
    if (style == WidgetStyle.StaggeredColumns ||
        style == WidgetStyle.PosterWaterfall ||
        style == WidgetStyle.DoubleFeature
    ) {
        WidgetGroupedGallery(style, items, metadata, compact, onOpen)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val preferred =
            when (style) {
                WidgetStyle.BigRank -> if (compact) 174.dp else 206.dp
                WidgetStyle.Borderless -> if (compact) 272.dp else 326.dp
                WidgetStyle.MirrorPoster -> if (compact) 140.dp else 164.dp
                WidgetStyle.OverlapInfo -> if (compact) 228.dp else 282.dp
                WidgetStyle.FloatingPoster -> if (compact) 272.dp else 316.dp
                WidgetStyle.StaggeredRail -> if (compact) 132.dp else 158.dp
                else -> if (compact) 244.dp else 294.dp
            }
        val width = minOf(preferred, maxWidth).coerceAtLeast(48.dp)
        val copy = galleryCopyHeight(metadata)
        val image =
            when (style) {
                WidgetStyle.BigRank -> (width - 36.dp) / .667f
                WidgetStyle.MirrorPoster, WidgetStyle.StaggeredRail -> width / .667f
                WidgetStyle.FloatingPoster -> maxOf(146.dp, copy + 36.dp)
                else -> width / 1.6f
            }
        val height =
            when (style) {
                WidgetStyle.MirrorPoster -> image + 50.dp + copy
                WidgetStyle.Borderless -> image + copy + 12.dp
                WidgetStyle.OverlapInfo -> image + copy + 12.dp
                WidgetStyle.FloatingPoster -> image + 16.dp
                WidgetStyle.BannerStack -> image + 46.dp + copy
                WidgetStyle.StaggeredRail -> image + copy + 12.dp
                else -> image + copy + 12.dp
            }
        LazyRow(
            Modifier.fillMaxWidth().height(height + 12.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$index:${item.id}" }) { index, item ->
                Box(
                    Modifier
                        .width(width)
                        .height(height)
                        .pressable(onClickLabel = "查看详情", onClick = { onOpen(item) }),
                ) {
                    when (style) {
                        WidgetStyle.BigRank -> GalleryBigRank(item, index + 1, image, metadata)
                        WidgetStyle.Borderless -> GalleryBorderless(item, image, metadata)
                        WidgetStyle.MirrorPoster -> GalleryMirror(item, image, metadata)
                        WidgetStyle.OverlapInfo -> GalleryOverlap(item, image, copy, metadata)
                        WidgetStyle.FloatingPoster -> GalleryFloating(item, metadata)
                        WidgetStyle.StaggeredRail -> GalleryStaggered(item, index, image, metadata)
                        WidgetStyle.BannerStack -> GalleryBanner(item, image, metadata)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Titles keep their full two-line budget even when an adjacent title is short. */
@Composable
internal fun galleryCopyHeight(metadata: Boolean): Dp =
    with(LocalDensity.current) {
        AppTypography.body.medium.lineHeight
            .toDp() * 2 +
            (
                if (metadata) {
                    AppTypography.caption.regular.lineHeight
                        .toDp() + 2.dp
                } else {
                    0.dp
                }
            ) + 6.dp
    }

@Composable
internal fun Modifier.galleryPlate(overArtwork: Boolean = false): Modifier {
    val palette = LocalPalette.current
    return glass(
        shape = AppShapes.card,
        fill = if (overArtwork) palette.glassStrong.copy(alpha = .94f) else palette.card,
        border = palette.border,
    )
}

@Composable
private fun GalleryBigRank(
    item: WidgetMedia,
    rank: Int,
    image: Dp,
    metadata: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(image)) {
            Artwork(item, Modifier.padding(start = 36.dp).fillMaxSize().clip(AppShapes.card), poster = true)
            // This is a decorative ordinal; its physical size should not take space from accessible copy.
            Text(
                rank.toString().padStart(2, '0'),
                Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp),
                color = LocalAccentColors.current.accent,
                style =
                    AppTypography.display.medium.copy(
                        fontSize = ((if (rank < 100) 64f else 46f) / LocalDensity.current.fontScale).sp,
                        lineHeight = (72f / LocalDensity.current.fontScale).sp,
                        fontWeight = FontWeight.Black,
                    ),
                maxLines = 1,
                softWrap = false,
            )
        }
        MediaCopy(item, Modifier.padding(start = 4.dp), metadata = metadata, dense = true)
    }
}

@Composable
private fun GalleryBorderless(
    item: WidgetMedia,
    image: Dp,
    metadata: Boolean,
) {
    val background = LocalPalette.current.background
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(image)) {
            Artwork(item, Modifier.fillMaxSize().clip(AppShapes.card))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .45f to Color.Transparent,
                        1f to background,
                    ),
                ),
            )
        }
        MediaCopy(item, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), metadata = metadata, dense = true)
    }
}

@Composable
private fun GalleryMirror(
    item: WidgetMedia,
    image: Dp,
    metadata: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Artwork(item, Modifier.fillMaxWidth().height(image).clip(AppShapes.card), poster = true)
        Spacer(Modifier.height(4.dp))
        // Crop the lower part of the same poster after flipping it, then fade to transparency.
        // Offscreen composition confines DstIn to the reflection, never the page behind it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(AppShapes.thumb)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = .28f), Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                }.clearAndSetSemantics { },
        ) {
            Artwork(
                item,
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .height(image)
                    .graphicsLayer { scaleY = -1f },
                poster = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        MediaCopy(item, metadata = metadata, dense = true)
    }
}

@Composable
private fun GalleryOverlap(
    item: WidgetMedia,
    image: Dp,
    copy: Dp,
    metadata: Boolean,
) {
    Box(Modifier.fillMaxSize()) {
        Artwork(item, Modifier.fillMaxWidth().height(image).clip(AppShapes.card))
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .offset(y = image - 24.dp)
                .fillMaxWidth()
                .height(copy + 36.dp)
                .galleryPlate(overArtwork = true)
                .padding(16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            MediaCopy(item, metadata = metadata, dense = true)
        }
    }
}

@Composable
private fun GalleryFloating(
    item: WidgetMedia,
    metadata: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverWidth = minOf(96.dp, maxWidth * .32f)
        Box(Modifier.padding(top = 26.dp).fillMaxSize().galleryPlate())
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                item,
                Modifier
                    .width(coverWidth)
                    .height(coverWidth / .667f)
                    .align(Alignment.Top)
                    .clip(AppShapes.thumb),
                poster = true,
            )
            MediaCopy(item, Modifier.weight(1f).padding(top = 18.dp), metadata = metadata, dense = true)
        }
    }
}

@Composable
private fun GalleryStaggered(
    item: WidgetMedia,
    index: Int,
    image: Dp,
    metadata: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(image), contentAlignment = Alignment.BottomCenter) {
            Artwork(
                item,
                Modifier.fillMaxWidth().height(if (index % 2 == 0) image else image * .82f).clip(AppShapes.card),
                poster = true,
            )
        }
        MediaCopy(item, metadata = metadata, dense = true)
    }
}

@Composable
private fun GalleryBanner(
    item: WidgetMedia,
    image: Dp,
    metadata: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(image + 34.dp)) {
            Artwork(item, Modifier.fillMaxWidth().height(image).clip(AppShapes.card))
            val posterWidth = minOf(82.dp, maxWidth * .30f)
            Artwork(
                item,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp)
                    .width(posterWidth)
                    .height(posterWidth / .667f)
                    .clip(AppShapes.thumb),
                poster = true,
            )
        }
        MediaCopy(item, Modifier.padding(horizontal = 4.dp), metadata = metadata, dense = true)
    }
}
