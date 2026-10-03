package com.yfuse.feature.player

import com.yfuse.core.model.isPortraitPicture

/** Whether this entry's picture stands upright, from the server's stream facts; null when unknown. */
internal fun PlayerMediaItem.portraitPicture(): Boolean? =
    activeVersion?.let { version ->
        isPortraitPicture(version.sourceWidth, version.sourceHeight, version.sourceRotation)
    }

/**
 * Whether the decoded picture stands upright, for an entry the server said nothing about. Only
 * ExoPlayer reports its size after rotation, so the server's answer is asked first.
 */
internal fun PlaybackState.decodedPortraitPicture(): Boolean? = isPortraitPicture(diagnostics.videoWidth, videoHeight)

/** Width over height of the picture as displayed, rotation applied; null when unknown. */
internal fun PlayerMediaVersion.displayAspectRatio(): Float? {
    val width = sourceWidth?.takeIf { it > 0 } ?: return null
    val height = sourceHeight?.takeIf { it > 0 } ?: return null
    val quarterTurn = (sourceRotation ?: 0).mod(180) == 90
    return if (quarterTurn) height.toFloat() / width else width.toFloat() / height
}
