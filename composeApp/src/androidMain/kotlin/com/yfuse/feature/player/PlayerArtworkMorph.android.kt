package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.PlayerArtworkOrigin
import com.yfuse.core.designsystem.PlayerArtworkOrigins
import com.yfuse.core.designsystem.playerArtworkRect
import kotlin.math.roundToInt

internal const val PLAYER_ARTWORK_TOKEN = "yfuse.player.artworkToken"

/** Activity-owned finite animation survives the preparation-to-player composition handoff. */
internal class PlayerArtworkMorphState(
    initialOrigin: PlayerArtworkOrigin,
) {
    var origin by mutableStateOf(initialOrigin)
    val progress = Animatable(0f)
    val opacity = Animatable(1f)
    var visible by mutableStateOf(true)
    var exiting by mutableStateOf(false)
    var disabled = false
    var completeExit: (() -> Unit)? = null

    fun requestExit(action: () -> Unit): Boolean {
        if (disabled) return false
        if (exiting) return true
        val destination = PlayerArtworkOrigins.resolve(origin.key) ?: return false
        origin = destination
        completeExit = action
        visible = true
        exiting = true
        return true
    }
}

/** Artwork morphs to the fitted video rectangle. Surface stays attached until reverse completes. */
@Composable
internal fun PlayerArtworkMorph(
    state: PlayerArtworkMorphState?,
    ready: Boolean,
    inPictureInPicture: Boolean,
    aspectRatio: Float? = null,
) {
    if (state == null) return
    val disabled = LocalAccessibilityOptions.current.reduceMotion || inPictureInPicture
    SideEffect { state.disabled = disabled }
    LaunchedEffect(state, state.exiting, disabled) {
        if (state.exiting) {
            if (!disabled) {
                state.opacity.animateTo(1f, tween(Motion.QUICK, easing = Motion.Curve))
                state.progress.animateTo(0f, tween(Motion.MODAL, easing = Motion.Curve))
            }
            state.completeExit?.also { state.completeExit = null }?.invoke()
        } else if (disabled) {
            state.progress.snapTo(1f)
            state.opacity.snapTo(0f)
            state.visible = false
        } else {
            state.progress.animateTo(1f, tween(Motion.MODAL, easing = Motion.Curve))
        }
    }
    LaunchedEffect(state, ready, state.exiting, disabled) {
        if (!state.exiting && (ready || disabled)) {
            if (disabled) {
                state.opacity.snapTo(0f)
            } else {
                state.opacity.animateTo(0f, tween(Motion.QUICK, easing = Motion.Curve))
            }
            state.visible = false
        }
    }
    if (state.visible && !disabled) {
        Box(Modifier.fillMaxSize()) {
            FallbackImage(
                urls = state.origin.urls,
                contentDescription = null,
                progressive = false,
                modifier =
                    Modifier
                        .layout { measurable, constraints ->
                            val width = constraints.maxWidth.toFloat()
                            val height = constraints.maxHeight.toFloat()
                            val start = playerArtworkRect(state.origin, width, height)
                            val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f }
                            val targetWidth = if (ratio == null) width else minOf(width, height * ratio)
                            val targetHeight = if (ratio == null) height else minOf(height, width / ratio)
                            val end =
                                Rect(
                                    (width - targetWidth) / 2f,
                                    (height - targetHeight) / 2f,
                                    (width + targetWidth) / 2f,
                                    (height + targetHeight) / 2f,
                                )
                            // Keep the image decode size fixed; only its placement layer moves each frame.
                            val child =
                                measurable.measure(
                                    Constraints.fixed(
                                        end.width.roundToInt().coerceAtLeast(1),
                                        end.height.roundToInt().coerceAtLeast(1),
                                    ),
                                )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                child.placeWithLayer(end.left.roundToInt(), end.top.roundToInt()) {
                                    val p = state.progress.value

                                    fun mix(
                                        a: Float,
                                        b: Float,
                                    ) = a + (b - a) * p
                                    scaleX = mix(start.width, end.width) / child.width
                                    scaleY = mix(start.height, end.height) / child.height
                                    translationX = mix(start.left, end.left) - end.left
                                    translationY = mix(start.top, end.top) - end.top
                                    transformOrigin =
                                        androidx.compose.ui.graphics
                                            .TransformOrigin(0f, 0f)
                                    alpha = state.opacity.value
                                    shape = RoundedCornerShape((16f * (1f - p)).dp)
                                    clip = true
                                }
                            }
                        },
            )
            if (!ready && !state.exiting) {
                OrbProgress(
                    size = 28.dp,
                    contentDescription = "正在准备画面",
                    modifier =
                        Modifier.align(Alignment.Center).graphicsLayer {
                            alpha = ((state.progress.value - 0.8f) / 0.2f).coerceIn(0f, 1f) * state.opacity.value
                        },
                )
            }
        }
    }
}
