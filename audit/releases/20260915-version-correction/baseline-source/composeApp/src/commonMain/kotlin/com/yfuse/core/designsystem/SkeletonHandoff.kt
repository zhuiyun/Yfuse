package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val HANDOFF_ROW_MS = 55
private const val HANDOFF_REVEAL_MS = 1000

internal class SkeletonArrival(
    val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
) {
    private val order = mutableMapOf<Any, Int>()

    fun index(key: Any): Int = order.getOrPut(key) { order.size.coerceAtMost(12) }
}

internal val LocalSkeletonArrival = staticCompositionLocalOf<SkeletonArrival?> { null }

internal fun skeletonRowProgress(
    progress: Float,
    index: Int,
): Float {
    val delay = index.coerceIn(0, 12) * HANDOFF_ROW_MS
    return Motion.Curve.transform(((progress * HANDOFF_REVEAL_MS - delay) / 340f).coerceIn(0f, 1f))
}

/** Also supports mixed lazy feeds: animateItem retains their removed skeletons during dissolution. */
@Composable
internal fun SkeletonArrivalScope(
    loading: Boolean,
    content: @Composable () -> Unit,
) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val previous = remember { booleanArrayOf(loading) }
    val arrival =
        remember(loading, moving) {
            SkeletonArrival(Animatable(if (previous[0] && !loading && moving) 0f else 1f))
        }
    SideEffect { previous[0] = loading }
    LaunchedEffect(arrival) {
        if (arrival.progress.value < 1f) arrival.progress.animateTo(1f, tween(HANDOFF_REVEAL_MS, easing = LinearEasing))
    }
    CompositionLocalProvider(LocalSkeletonArrival provides arrival, content = content)
}

/** One live content tree. Only the cheap outgoing skeleton survives the finite dissolve. */
@Composable
internal fun SkeletonHandoff(
    loading: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduce = LocalAccessibilityOptions.current.reduceMotion || !LocalRouteVisible.current
    SkeletonArrivalScope(loading) {
        Box(
            modifier.semantics {
                if (loading) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    stateDescription = "正在加载"
                }
            },
        ) {
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(tween(if (reduce) 0 else Motion.QUICK)),
                exit = fadeOut(tween(if (reduce) 0 else Motion.STANDARD)),
                modifier = Modifier.matchParentSize().clearAndSetSemantics {},
            ) { Box { skeleton() } }
            if (!loading) content()
        }
    }
}

@Composable
internal fun Modifier.skeletonArrival(key: Any): Modifier {
    val arrival = LocalSkeletonArrival.current ?: return this
    val index = remember(arrival, key) { arrival.index(key) }
    return graphicsLayer {
        val amount = skeletonRowProgress(arrival.progress.value, index)
        alpha = amount
        translationY = 12.dp.toPx() * (1f - amount)
    }
}

/** Used only while real local/connection data is pending; no artificial loading delay. */
@Composable
internal fun PageLoadingSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    /** Lists that already pad their content pass 0.dp so the placeholder lines up with the real rows. */
    horizontalPadding: Dp = 18.dp,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SkeletonBlock(Modifier.width(120.dp).height(24.dp))
        repeat(rows.coerceIn(1, 4)) { index ->
            SkeletonBlock(Modifier.fillMaxWidth().height(76.dp), phaseMs = index * HANDOFF_ROW_MS)
        }
    }
}
