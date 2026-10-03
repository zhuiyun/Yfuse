package com.yfuse.feature.player

import com.yfuse.core.model.isPortraitPicture

/** Whether this entry's picture stands upright, from the server's stream facts; null when unknown. */
internal fun PlayerMediaItem.portraitPicture(): Boolean? =
    activeVersion?.let { version ->
        isPortraitPicture(version.sourceWidth, version.sourceHeight, version.sourceRotation)
    }

/**
 * Whether the decoded picture stands upright, for an entry the server said nothing about. Not every
 * route reports its size after rotation, so the server's answer is asked first.
 */
internal fun PlaybackState.decodedPortraitPicture(): Boolean? = isPortraitPicture(diagnostics.videoWidth, videoHeight)

/** Width over height of the picture as displayed, rotation applied; null when unknown. */
internal fun PlayerMediaVersion.displayAspectRatio(): Float? {
    val width = sourceWidth?.takeIf { it > 0 } ?: return null
    val height = sourceHeight?.takeIf { it > 0 } ?: return null
    val quarterTurn = (sourceRotation ?: 0).mod(180) == 90
    return if (quarterTurn) height.toFloat() / width else width.toFloat() / height
}

/** What a vertical drag down the middle of an upright 短剧 does as it lets go. */
internal enum class EpisodeSwipe {
    Next,
    Previous,
    None,
}

/** How far, as a share of the picture's height, a drag has to go to change episode. */
private const val EPISODE_SWIPE_FRACTION = 0.12f

/** Each side keeps this share of the width, at the least, for brightness and volume. */
private const val EPISODE_SWIPE_EDGE_FRACTION = 0.18f

/**
 * An upright 短剧 is flicked through like a feed: a drag up of an eighth of the picture or more is
 * the next episode, one down the episode before. [totalY] is how far the finger went, down positive.
 */
internal fun episodeSwipe(
    totalY: Float,
    height: Float,
    hasNext: Boolean,
    hasPrevious: Boolean,
): EpisodeSwipe {
    val threshold = height * EPISODE_SWIPE_FRACTION
    return when {
        height <= 0f -> EpisodeSwipe.None
        totalY <= -threshold && hasNext -> EpisodeSwipe.Next
        totalY >= threshold && hasPrevious -> EpisodeSwipe.Previous
        else -> EpisodeSwipe.None
    }
}

/**
 * Whether a drag starting at [x] changes episode rather than brightness or volume: the middle of the
 * picture does, and a band along each side — [minEdgePx] wide at the least — keeps the old drags.
 */
internal fun inEpisodeSwipeBand(
    x: Float,
    width: Float,
    minEdgePx: Float,
): Boolean {
    val edge = maxOf(minEdgePx, width * EPISODE_SWIPE_EDGE_FRACTION)
    return x > edge && x < width - edge
}
