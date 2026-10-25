package com.yfuse.core.widget

import androidx.compose.runtime.Composable

/**
 * An aligned, bounded copy of the source and its foreground in the same pixel coordinates.
 *
 * Both layers must use the same crop and transform. Moving only the foreground would expose the
 * subject still present in [backgroundModel]. Bounds are fractions of the uncropped source.
 */
data class WidgetCutoutArtwork(
    val backgroundModel: String,
    val foregroundModel: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val subjectTop: Float,
    val subjectBottom: Float,
)

/**
 * Returns null while loading or when a conservative quality check fails. [enabled] controls new
 * processing; an existing result remains stable while the card moves out of the active position.
 */
@Composable
expect fun rememberWidgetCutoutArtwork(
    posterUrl: String?,
    enabled: Boolean,
): WidgetCutoutArtwork?
