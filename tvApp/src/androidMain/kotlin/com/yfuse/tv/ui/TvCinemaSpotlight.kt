package com.yfuse.tv.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CinemaMotion
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.cinemaMotion
import com.yfuse.core.designsystem.rememberDominantColor
import kotlinx.coroutines.delay

internal data class TvCinemaFocus(
    val owner: Any,
    val bounds: Rect,
    val artwork: String?,
    val repeated: Boolean,
)

/** Late blur/dispose events from the old card cannot take the new card's light away. */
internal class TvCinemaFocusOwner {
    var target by mutableStateOf<TvCinemaFocus?>(null)
        private set

    fun focus(next: TvCinemaFocus) {
        target = next
    }

    fun move(
        owner: Any,
        bounds: Rect,
    ) {
        target?.takeIf { it.owner === owner }?.let { target = it.copy(bounds = bounds) }
    }

    fun clear(owner: Any) {
        if (target?.owner === owner) target = null
    }
}

private val LocalTvCinemaFocus = staticCompositionLocalOf<TvCinemaFocusOwner?> { null }

@Composable
internal fun TvCinemaSpotlightHost(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val owner = remember { TvCinemaFocusOwner() }
    val enabled = cinemaMotion() && LocalWindowInfo.current.isWindowFocused
    val target = owner.target
    var settledArtwork by remember { mutableStateOf<String?>(null) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(target?.owner, target?.artwork, target?.repeated, enabled) {
        settledArtwork = null
        settled = false
        if (enabled && target != null) {
            delay(CinemaMotion.FOCUS_DWELL)
            settledArtwork = target.artwork
            settled = true
        }
    }
    // Sampling is debounced across focus changes, including the last card after a held key.
    val fastTravel = target?.repeated == true && !settled
    val sampled = rememberDominantColor(settledArtwork.takeIf { enabled }, TvAccent)
    val color =
        animateColorAsState(
            sampled,
            if (enabled) Motion.tween(CinemaMotion.COLOR) else snap(),
            label = "cinema-focus-color",
        )
    val center =
        animateOffsetAsState(
            targetValue = target?.bounds?.center ?: Offset.Zero,
            animationSpec = if (!enabled || fastTravel) snap() else Motion.tween(CinemaMotion.FOCUS),
            label = "cinema-focus-position",
        )
    val amount =
        animateFloatAsState(
            targetValue =
                if (enabled && target != null) {
                    if (fastTravel) {
                        0.3f
                    } else {
                        1f
                    }
                } else {
                    0f
                },
            animationSpec = if (!enabled) snap() else Motion.tween(CinemaMotion.FOCUS),
            label = "cinema-focus-light",
        )
    val origin = remember { arrayOf(Offset.Zero) }
    CompositionLocalProvider(LocalTvCinemaFocus provides owner) {
        Box(
            modifier
                .onGloballyPositioned { origin[0] = it.boundsInRoot().topLeft }
                .drawWithContent {
                    if (enabled && amount.value > 0f) {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(color.value.copy(alpha = 0.18f * amount.value), Color.Transparent),
                                center = center.value - origin[0],
                                radius = 260.dp.toPx(),
                            ),
                        )
                    }
                    drawContent()
                },
            content = content,
        )
    }
}

@Composable
internal fun Modifier.cinemaSpotlightTarget(artwork: String?): Modifier {
    val controller = LocalTvCinemaFocus.current ?: return this
    val enabled = cinemaMotion()
    val owner = remember { Any() }
    val bounds = remember { arrayOf(Rect.Zero) }
    var focused by remember { mutableStateOf(false) }
    val repeated = remember { booleanArrayOf(false) }
    val travel = LocalTvFocusTravel.current
    SideEffect {
        if (enabled && focused && bounds[0].width > 0f) {
            controller.focus(TvCinemaFocus(owner, bounds[0], artwork, repeated[0]))
        } else {
            controller.clear(owner)
        }
    }
    DisposableEffect(controller, owner) { onDispose { controller.clear(owner) } }
    return onGloballyPositioned {
        bounds[0] = it.boundsInRoot()
        controller.move(owner, bounds[0])
    }.onFocusChanged {
        focused = it.isFocused
        repeated[0] = travel?.isRepeating(SystemClock.uptimeMillis()) == true
        if (enabled && it.isFocused) {
            controller.focus(TvCinemaFocus(owner, bounds[0], artwork, repeated[0]))
        } else {
            controller.clear(owner)
        }
    }
}
