package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Where a half-open fold crosses the window, top to bottom in window pixels, while the phone
 * stands on a table (a horizontal hinge, half open). Null on every other device and posture,
 * and until the window has reported its layout.
 */
@Composable
internal fun rememberTabletopHinge(): IntRange? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val hinge by produceState<IntRange?>(initialValue = null, activity) {
        val target = activity ?: return@produceState
        WindowInfoTracker
            .getOrCreate(target)
            .windowLayoutInfo(target)
            .collect { layout ->
                value =
                    layout.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull {
                            it.state == FoldingFeature.State.HALF_OPENED &&
                                it.orientation == FoldingFeature.Orientation.HORIZONTAL
                        }?.bounds
                        ?.let { it.top..it.bottom }
            }
    }
    return hinge
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
