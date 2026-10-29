package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

/** Align the page header and every section, with at most 1200dp of readable content. */
internal fun widgetHomeHorizontalPadding(width: Dp): Dp =
    maxOf(
        when {
            width < 600.dp -> 16.dp
            width < 840.dp -> 24.dp
            else -> 32.dp
        },
        (width - 1200.dp) / 2,
    )

internal fun widgetGalleryViewportWidth(
    available: Dp,
    artwork: Dp,
): Dp = if (available >= 600.dp) available else artwork

/** Wide rows reveal more cards; individual tiles grow only modestly. */
internal fun widgetRailItemWidth(
    preferred: Dp,
    available: Dp,
): Dp = minOf(preferred * if (available >= 600.dp) 1.15f else 1f, available).coerceAtLeast(1.dp)

internal fun widgetPagerCardWidth(
    available: Dp,
    compact: Boolean,
    portrait: Boolean,
    maxHeight: Dp,
): Dp =
    if (available < 600.dp) {
        available - if (portrait) minOf(52.dp, available * .15f) * 2 else 32.dp
    } else if (portrait) {
        minOf(if (compact) 240.dp else 280.dp, maxHeight * .667f)
    } else {
        if (compact) 400.dp else 480.dp
    }.coerceIn(1.dp, available.coerceAtLeast(1.dp))

internal fun widgetHomeGalleryHeight(
    width: Dp,
    height: Dp,
    bottomInset: Dp,
    fontScale: Float,
): Dp =
    if (width < 600.dp || !height.value.isFinite()) {
        Dp.Infinity
    } else {
        // Leave space for the section title, caption/actions and the actual floating dock.
        (height - bottomInset - 84.dp - 48.dp * fontScale.coerceAtLeast(1f)).coerceIn(120.dp, 420.dp)
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
    val state = rememberLazyListState()
    ScrollToTopOnReselect(state)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val galleryHeight = widgetHomeGalleryHeight(maxWidth, maxHeight, bottomInset, fontScale)
        CompositionLocalProvider(LocalWidgetGalleryMaxHeight provides galleryHeight) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = widgetHomeHorizontalPadding(maxWidth),
                        top = 8.dp,
                        end = widgetHomeHorizontalPadding(maxWidth),
                        bottom = bottomInset,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (maxWidth >= 600.dp) 28.dp else 18.dp),
            ) {
                if (sections.isEmpty()) item { empty() }
                items(sections, key = { it.id }) { section -> content(section) }
            }
        }
    }
}
