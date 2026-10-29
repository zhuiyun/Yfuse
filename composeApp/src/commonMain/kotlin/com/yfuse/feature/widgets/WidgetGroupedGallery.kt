package com.yfuse.feature.widgets

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.overlayDismiss
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.designsystem.ThemeText as Text

/** Each page has at most four films. More items add horizontal pages, never more rows. */
@Composable
internal fun WidgetGroupedGallery(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    val pageSize = if (style == WidgetStyle.PosterWaterfall) 4 else 2
    val pages = remember(items, pageSize) { items.chunked(pageSize) }
    var collection by remember(style, items) { mutableStateOf<List<WidgetMedia>?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = widgetRailItemWidth(if (compact) 270.dp else 320.dp, maxWidth)
        val copy = galleryCopyHeight(metadata)
        val columnWidth = (width - 12.dp) / 2
        val image = columnWidth / .667f
        val buttonHeight =
            with(LocalDensity.current) {
                maxOf(
                    48.dp,
                    AppTypography.body.strong.lineHeight
                        .toDp() + 24.dp,
                )
            }
        val height =
            when (style) {
                WidgetStyle.PosterWaterfall -> image * 1.38f + copy * 2 + 32.dp
                WidgetStyle.DoubleFeature -> image + copy + buttonHeight + 36.dp
                else -> image + copy + 42.dp
            }
        LazyRow(
            Modifier.fillMaxWidth().height(height + 12.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(pages, key = { index, page -> "$index:${page.first().id}" }) { index, page ->
                Box(Modifier.width(width).height(height)) {
                    when (style) {
                        WidgetStyle.PosterWaterfall -> GalleryWaterfall(page, image, copy, metadata, onOpen)
                        WidgetStyle.StaggeredColumns -> GalleryOffsetPair(page, image, metadata, index, onOpen)
                        WidgetStyle.DoubleFeature ->
                            GalleryDoubleFeature(page, image, copy, buttonHeight, metadata, onOpen) {
                                collection = page
                            }
                        else -> Unit
                    }
                }
            }
        }
    }
    collection?.let { selected ->
        GalleryCollectionDialog(
            items = selected,
            metadata = metadata,
            onDismiss = { collection = null },
            onOpen = { item ->
                collection = null
                onOpen(item)
            },
        )
    }
}

@Composable
private fun GalleryOffsetPair(
    items: List<WidgetMedia>,
    image: Dp,
    metadata: Boolean,
    pageIndex: Int,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { index, item ->
            Column(Modifier.weight(1f)) {
                val stagger = if ((index + pageIndex) % 2 == 0) 0.dp else 30.dp
                Spacer(Modifier.height(stagger))
                GalleryPoster(item, image, metadata, Modifier.fillMaxWidth(), onOpen)
            }
        }
        if (items.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GalleryWaterfall(
    items: List<WidgetMedia>,
    image: Dp,
    copy: Dp,
    metadata: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Column-major order matches visual scan order and accessibility traversal.
        repeat(2) { column ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { row ->
                    val item = items.getOrNull(column * 2 + row)
                    val artworkHeight = image * if ((column + row) % 2 == 0) .82f else .56f
                    if (item != null) {
                        GalleryPoster(item, artworkHeight, metadata, Modifier.fillMaxWidth(), onOpen)
                    } else {
                        Spacer(Modifier.height(artworkHeight + copy + 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryDoubleFeature(
    items: List<WidgetMedia>,
    image: Dp,
    copy: Dp,
    buttonHeight: Dp,
    metadata: Boolean,
    onOpen: (WidgetMedia) -> Unit,
    onCollection: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().galleryPlate().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Box(Modifier.weight(1f).height(image + copy + 8.dp)) {
                    GalleryPoster(item, image, metadata, Modifier.fillMaxWidth(), onOpen)
                }
            }
            if (items.size == 1) Spacer(Modifier.weight(1f))
        }
        WidgetTextButton(
            onClick = onCollection,
            modifier = Modifier.fillMaxWidth().height(buttonHeight),
        ) {
            Text(if (items.size == 2) "查看双片片单" else "查看片单")
        }
    }
}

@Composable
private fun GalleryPoster(
    item: WidgetMedia,
    image: Dp,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    Column(
        modifier.pressable(onClickLabel = "查看详情", onClick = { onOpen(item) }),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Artwork(item, Modifier.fillMaxWidth().height(image).clip(AppShapes.thumb), poster = true)
        MediaCopy(item, metadata = metadata, dense = true)
    }
}

@Composable
private fun GalleryCollectionDialog(
    items: List<WidgetMedia>,
    metadata: Boolean,
    onDismiss: () -> Unit,
    onOpen: (WidgetMedia) -> Unit,
) {
    GlassDialog(
        onDismiss = onDismiss,
        scrollable = false,
        modifier = Modifier.semantics { paneTitle = "双片片单" },
    ) {
        val dismiss = overlayDismiss(onDismiss)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "双片片单",
                Modifier.weight(1f),
                style = AppTypography.section.strong,
                color = LocalPalette.current.text,
            )
            WidgetTextButton(onClick = dismiss) { Text("关闭") }
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumWidth = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minimumWidth),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * .75f),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(items, key = { index, item -> "$index:${item.id}" }) { _, item ->
                    val open = overlayAction { onOpen(item) }
                    WidgetGridCard(item, metadata) { open() }
                }
            }
        }
    }
}
