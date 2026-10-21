package com.yfuse.feature.player

import kotlin.math.roundToInt

enum class SubtitleRegion(
    val label: String,
) {
    PreferBlackBars("自动优先黑边"),
    Picture("画面内"),
}

internal data class SubtitlePlacement(
    val top: Int,
    val inBlackBar: Boolean,
)

/** All dimensions are in the same pixels. Never shrink a caption to make it fit a bar. */
internal fun placeSubtitleBlock(
    containerHeight: Int,
    pictureHeight: Int,
    contentHeight: Int,
    region: SubtitleRegion,
    position: Float,
    safeInset: Int,
): SubtitlePlacement {
    val height = containerHeight.coerceAtLeast(0)
    val picture = pictureHeight.coerceIn(0, height).takeIf { it > 0 } ?: height
    val content = contentHeight.coerceAtLeast(0)
    val inset = safeInset.coerceAtLeast(0)
    val pictureTop = (height - picture) / 2
    val pictureBottom = pictureTop + picture
    val bar = height - pictureBottom
    if (region == SubtitleRegion.PreferBlackBars && content > 0 && bar >= content + 2L * inset) {
        return SubtitlePlacement(pictureBottom + (bar - content) / 2, true)
    }
    val fraction = if (position.isFinite()) position.coerceIn(0.60f, 0.96f) else DEFAULT_SUBTITLE_POSITION
    val bottom = pictureBottom - maxOf(inset, (picture * (1f - fraction)).roundToInt())
    val lastTop = (height - content).coerceAtLeast(0)
    return SubtitlePlacement((bottom - content).coerceIn(minOf(pictureTop, lastTop), lastTop), false)
}

/** Native renderers do not expose glyph bounds. Reserve two lines per track before using a bar. */
internal fun nativeSubtitleReserve(
    pictureHeight: Int,
    scale: Float,
    secondaryScale: Float?,
    minimumLineHeight: Int,
): Int {
    val line = maxOf(minimumLineHeight, (pictureHeight * 0.065f).roundToInt())
    val scales = scale.coerceIn(0.6f, 1.8f) + (secondaryScale?.coerceIn(0.6f, 1.8f) ?: 0f)
    return (2 * line * scales).roundToInt()
}
