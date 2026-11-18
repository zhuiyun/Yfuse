package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPictureSettings
import com.yfuse.core.model.PlaybackMethod
import kotlin.math.abs

/** Preserve every source pixel, the original display aspect and the separate full-size subtitle layer. */
internal fun senPlayerOutputSize(
    item: PlayerMediaItem?,
    viewport: IntSize,
    scaleMode: VideoScaleMode,
    settings: SenPlayerPictureSettings,
): IntSize? {
    val source = item?.activeVersion ?: return null
    if (
        scaleMode != VideoScaleMode.Fit ||
        settings.upscaling != SenPlayerEnhancementLevel.Off ||
        settings.sdrToHdr != SenPlayerEnhancementLevel.Off ||
        item.playMethod != PlaybackMethod.DirectPlay ||
        source.playMethod != PlaybackMethod.DirectPlay ||
        source.discSource ||
        item.playbackSegments.size > 1 ||
        item.drmConfiguration != null ||
        source.drmConfiguration != null ||
        source.dolbyVision ||
        source.dolbyProfile != null ||
        !source.sourceDynamicRange.equals("SDR", ignoreCase = true) ||
        (source.sourceRotation ?: 0).mod(360) != 0
    ) {
        return null
    }
    val width = source.sourceWidth ?: return null
    val height = source.sourceHeight ?: return null
    if (width !in 1 until viewport.width || height !in 1 until viewport.height) return null
    if (width.toLong() * viewport.height != height.toLong() * viewport.width) return null
    val aspect = source.sourceDisplayAspect?.trim() ?: return null
    val numerator = aspect.substringBefore(':').toDoubleOrNull() ?: return null
    val denominator = aspect.substringAfter(':', "1").toDoubleOrNull() ?: return null
    val displayRatio = numerator / denominator
    // Emby can round the display aspect to e.g. 1.78:1.
    if (numerator <= 0 || denominator <= 0 || !displayRatio.isFinite()) return null
    if (abs(displayRatio * height / width - 1) > 0.005) {
        return null
    }
    return IntSize(width, height)
}
