package com.yfuse.feature.player

/** A centered crop in image coordinates; the same scale is applied to both image axes. */
internal data class PlayerArtworkTransform(
    val scale: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
)

internal fun playerArtworkTransform(
    imageWidth: Float,
    imageHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
): PlayerArtworkTransform {
    val width = frameWidth.coerceAtLeast(1f)
    val height = frameHeight.coerceAtLeast(1f)
    val scale = maxOf(width / imageWidth, height / imageHeight)
    val cropWidth = width / scale
    val cropHeight = height / scale
    return PlayerArtworkTransform(
        scale = scale,
        cropLeft = (imageWidth - cropWidth) / 2f,
        cropTop = (imageHeight - cropHeight) / 2f,
        cropWidth = cropWidth,
        cropHeight = cropHeight,
    )
}
