package com.yfuse.core.designsystem

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.catch

/**
 * Observes Jetpack WindowManager's [FoldingFeature] for [activity] and translates it into a
 * platform-neutral [WindowPosture] for dual-pane hinge avoidance and foldable tabletop playback.
 */
@Composable
fun rememberAndroidWindowPosture(activity: Activity): WindowPosture {
    val density = LocalDensity.current
    var posture by remember(activity) { mutableStateOf(WindowPosture.Flat) }
    LaunchedEffect(activity, density) {
        runCatching {
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                .catch { emit(androidx.window.layout.WindowLayoutInfo(emptyList())) }
                .collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    if (fold == null) {
                        posture = WindowPosture.Flat
                    } else {
                        val bounds =
                            WindowMetricsCalculator
                                .getOrCreate()
                                .computeCurrentWindowMetrics(activity)
                                .bounds
                        val winW = bounds.width().coerceAtLeast(1).toFloat()
                        val winH = bounds.height().coerceAtLeast(1).toFloat()
                        val foldBounds = fold.bounds
                        val isHorizontal = fold.orientation == FoldingFeature.Orientation.HORIZONTAL
                        val isVertical = fold.orientation == FoldingFeature.Orientation.VERTICAL
                        val isHalfOpened = fold.state == FoldingFeature.State.HALF_OPENED
                        val thicknessPx = if (isHorizontal) foldBounds.height() else foldBounds.width()
                        val thicknessDp = with(density) { thicknessPx.toDp() }
                        posture =
                            WindowPosture(
                                isTabletop = isHorizontal && isHalfOpened,
                                isBookMode = isVertical,
                                verticalHingeFraction =
                                    if (isVertical) {
                                        (foldBounds.exactCenterX() / winW).coerceIn(0.30f, 0.70f)
                                    } else {
                                        null
                                    },
                                horizontalHingeFraction =
                                    if (isHorizontal) {
                                        (foldBounds.exactCenterY() / winH).coerceIn(0.30f, 0.70f)
                                    } else {
                                        null
                                    },
                                hingeThicknessDp = thicknessDp,
                            )
                    }
                }
        }.onFailure {
            posture = WindowPosture.Flat
        }
    }
    return posture
}
