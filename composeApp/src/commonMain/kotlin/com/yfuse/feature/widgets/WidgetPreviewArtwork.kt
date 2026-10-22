package com.yfuse.feature.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** Original bundled raster artwork works offline in previews and the example component. */
@Composable
internal fun WidgetPreviewArtwork(
    id: String,
    modifier: Modifier = Modifier,
) {
    val index = id.substringAfterLast('-').toIntOrNull() ?: (id.hashCode() and Int.MAX_VALUE)
    val name = listOf("lake", "space", "rain")[index % 3]
    AsyncImage(
        model = "file:///android_asset/widget-preview/$name.png",
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
