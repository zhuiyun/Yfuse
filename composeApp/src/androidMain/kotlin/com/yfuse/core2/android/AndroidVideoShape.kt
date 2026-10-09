package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core2.demux.YVideoGeometry
import com.yfuse.core2.demux.shownVideoSize
import com.yfuse.core2.demux.statedPixelAspectRatio

/** The pixel aspect ratio a format states, or null; see the common [statedPixelAspectRatio]. */
internal fun MediaFormat.statedPixelAspectRatio(): Double? =
    statedPixelAspectRatio(
        sarWidth = positiveInteger(KEY_SAR_WIDTH),
        sarHeight = positiveInteger(KEY_SAR_HEIGHT),
        displayWidth = positiveInteger(KEY_DISPLAY_WIDTH),
        displayHeight = positiveInteger(KEY_DISPLAY_HEIGHT),
        width = positiveInteger(MediaFormat.KEY_WIDTH) ?: 0,
        height = positiveInteger(MediaFormat.KEY_HEIGHT) ?: 0,
    )

/**
 * [shownVideoSize] of an extractor's video format: its stored size squared by the pixel aspect
 * ratio ([pixelAspectRatio] when a decoder has stated one since), then turned by its rotation.
 */
internal fun MediaFormat.shownSize(pixelAspectRatio: Double? = null): Pair<Int, Int> =
    shownVideoSize(
        width = positiveInteger(MediaFormat.KEY_WIDTH) ?: 0,
        height = positiveInteger(MediaFormat.KEY_HEIGHT) ?: 0,
        rotationDegrees = integerOrZero(MediaFormat.KEY_ROTATION),
        pixelAspectRatio = pixelAspectRatio ?: statedPixelAspectRatio() ?: 1.0,
    )

private fun MediaFormat.positiveInteger(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull()?.takeIf { it > 0 } else null

private fun MediaFormat.integerOrZero(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

// MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH/HEIGHT name these from API 30; the strings are older.
private const val KEY_SAR_WIDTH = "sar-width"
private const val KEY_SAR_HEIGHT = "sar-height"
private const val KEY_DISPLAY_WIDTH = "display-width"
private const val KEY_DISPLAY_HEIGHT = "display-height"

/** The quarter turns a picture is drawn with: 90, 180 or 270, and 0 for anything else. */
internal val YVideoGeometry.drawnRotationDegrees: Int
    get() = normalizedRotationDegrees.takeIf { it == 90 || it == 180 || it == 270 } ?: 0

/**
 * The size a [width]×[height] frame is drawn at, before it is turned, to fit a [canvasWidth]×
 * [canvasHeight] canvas: pixels squared and the turned picture fitted whole, centred on black.
 */
internal fun softwareFrameDrawSize(
    width: Int,
    height: Int,
    geometry: YVideoGeometry,
    canvasWidth: Int,
    canvasHeight: Int,
): Pair<Float, Float> {
    val turned = geometry.drawnRotationDegrees % 180 == 90
    val squaredAspect = width * geometry.pixelAspectRatio / height
    val shownAspect = (if (turned) 1.0 / squaredAspect else squaredAspect).toFloat()
    val canvasAspect = canvasWidth.toFloat() / canvasHeight
    val (shownWidth, shownHeight) =
        if (shownAspect > canvasAspect) {
            canvasWidth.toFloat() to canvasWidth / shownAspect
        } else {
            canvasHeight * shownAspect to canvasHeight.toFloat()
        }
    return if (turned) shownHeight to shownWidth else shownWidth to shownHeight
}
