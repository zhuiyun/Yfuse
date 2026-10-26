package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ScrollToTopOnReselect
import com.yfuse.core.widget.WidgetSection

internal val LocalWidgetGalleryMaxHeight = staticCompositionLocalOf { Dp.Infinity }

internal fun widgetHomeColumns(
    width: Dp,
    fontScale: Float,
): Int {
    if (width < 840.dp) return 1
    val minimumColumn = 360.dp * fontScale.coerceAtLeast(1f)
    val fitting = ((width - 8.dp) / (minimumColumn + 24.dp)).toInt().coerceAtLeast(1)
    return minOf(fitting, if (width >= 1440.dp) 3 else 2)
}

internal fun widgetHomeGalleryHeight(
    width: Dp,
    height: Dp,
    bottomInset: Dp,
    fontScale: Float,
): Dp =
    if (width < 840.dp || !height.value.isFinite()) {
        Dp.Infinity
    } else {
        // Leave space for the section title, caption/actions and the actual floating dock.
        (height - bottomInset - 84.dp - 48.dp * fontScale.coerceAtLeast(1f)).coerceIn(180.dp, 420.dp)
    }

/** Preserve the artwork's proportions while fitting a finite landscape viewport. */
internal fun widgetGalleryWidth(
    availableWidth: Dp,
    compact: Boolean,
    maxHeight: Dp,
    heightRatio: Float,
    widthInset: Dp = 0.dp,
    heightInset: Dp = 0.dp,
): Dp =
    minOf(
        availableWidth,
        if (compact) 340.dp else 480.dp,
        (maxHeight - heightInset).coerceAtLeast(1.dp) / heightRatio + widthInset,
    ).coerceAtLeast(1.dp)

@Composable
internal fun WidgetHomeSections(
    sections: List<WidgetSection>,
    bottomInset: Dp,
    empty: @Composable () -> Unit,
    content: @Composable (WidgetSection) -> Unit,
) {
    val state = rememberLazyGridState()
    ScrollToTopOnReselect(state)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = widgetHomeColumns(maxWidth, fontScale)
        val galleryHeight = widgetHomeGalleryHeight(maxWidth, maxHeight, bottomInset, fontScale)
        CompositionLocalProvider(LocalWidgetGalleryMaxHeight provides galleryHeight) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, bottomInset),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (sections.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { empty() }
                items(sections, key = { it.id }) { section -> content(section) }
            }
        }
    }
}
