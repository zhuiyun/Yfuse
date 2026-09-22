package com.yfuse.core.util

import android.annotation.SuppressLint
import android.app.Activity

internal const val ADAPTIVE_SCREEN_MIN_WIDTH_DP = 600

internal fun shouldLockCompactScreenOrientation(smallestScreenWidthDp: Int): Boolean =
    smallestScreenWidthDp < ADAPTIVE_SCREEN_MIN_WIDTH_DP

/**
 * Preserves the phone experience without fighting Android 16's adaptive large-screen model.
 * Large displays, desktop windows and foldables at 600dp+ remain freely resizable/rotatable.
 */
@SuppressLint("SourceLockedOrientationActivity", "DiscouragedApi")
internal fun Activity.lockOrientationOnCompactScreens(orientation: Int) {
    if (shouldLockCompactScreenOrientation(resources.configuration.smallestScreenWidthDp)) {
        requestedOrientation = orientation
    }
}
