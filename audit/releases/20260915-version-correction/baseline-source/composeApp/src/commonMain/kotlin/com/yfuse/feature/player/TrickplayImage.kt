package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun TrickplayImage(
    storyboard: TrickplayStoryboard,
    frame: TrickplayFrame,
    description: String,
    modifier: Modifier,
)

internal data class TrickplayRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Compute in Long so malicious dimensions cannot wrap into an unrelated crop. */
internal fun trickplayRegion(
    width: Int,
    height: Int,
    columns: Int,
    rows: Int,
    column: Int,
    row: Int,
): TrickplayRegion {
    require(width > 0 && height > 0 && columns in 1..256 && rows in 1..256)
    require(column in 0 until columns && row in 0 until rows)
    val region =
        TrickplayRegion(
            (width.toLong() * column / columns).toInt(),
            (height.toLong() * row / rows).toInt(),
            (width.toLong() * (column + 1) / columns).toInt(),
            (height.toLong() * (row + 1) / rows).toInt(),
        )
    require(region.right > region.left && region.bottom > region.top)
    return region
}
