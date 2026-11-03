package com.yfuse.feature.player

/** All unobstructed picture regions share one temporary speed gesture. */
internal enum class PictureHoldAction { SpeedBoost, None }

internal fun pictureHoldAction(
    x: Float,
    width: Int,
): PictureHoldAction =
    if (width > 0 && x.isFinite() && x >= 0f && x <= width) {
        PictureHoldAction.SpeedBoost
    } else {
        PictureHoldAction.None
    }
