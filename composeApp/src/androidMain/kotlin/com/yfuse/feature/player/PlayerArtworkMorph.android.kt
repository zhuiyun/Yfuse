package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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

/**
 * Which half of the morph one [PlayerArtworkMorph] call is responsible for drawing.
 *
 * The arrival grows underneath the player chrome, so the back button and the transport row stay
 * reachable while the picture is still being prepared. The departure has to sit on top of that
 * same chrome: drawn beneath it, the poster shrank away under the top bar and the seek bar while
 * the paused frame showed through around it, which read as a glitch rather than as the artwork
 * going back where it came from. [Full] draws both halves for a host with no chrome of its own.
 */
internal enum class PlayerArtworkMorphLayer { Full, Entrance, Exit }

/**
 * Artwork morphs to the fitted video rectangle. Surface stays attached until reverse completes.
 *
 * The animation state itself is driven only by a [PlayerArtworkMorphLayer.Full] or
 * [PlayerArtworkMorphLayer.Entrance] call; an [PlayerArtworkMorphLayer.Exit] call is a second,
 * purely visual layer that a host places above its chrome.
 */
@Composable
internal fun PlayerArtworkMorph(
    state: PlayerArtworkMorphState?,
    ready: Boolean,
    inPictureInPicture: Boolean,
    aspectRatio: Float? = null,
    layer: PlayerArtworkMorphLayer = PlayerArtworkMorphLayer.Full,
) {
    if (state == null) return
    val disabled = LocalAccessibilityOptions.current.reduceMotion || inPictureInPicture
    if (layer != PlayerArtworkMorphLayer.Exit) {
        PlayerArtworkMorphDriver(state, ready, disabled)
    }
    val drawsThisPhase =
        when (layer) {
            PlayerArtworkMorphLayer.Full -> true
            PlayerArtworkMorphLayer.Entrance -> !state.exiting
            PlayerArtworkMorphLayer.Exit -> state.exiting
        }
    if (state.visible && !disabled && drawsThisPhase) {
        Box(Modifier.fillMaxSize()) {
            if (state.exiting) {
                // The paused frame and whatever chrome is up fade to black on the same curve
                // as the poster surfaces, so the departure plays over the same ground the
                // arrival did instead of over a frozen picture.
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(Color.Black, alpha = state.opacity.value.coerceIn(0f, 1f))
                    },
                )
            }
            PlayerArtworkMorphImage(state, ready, aspectRatio)
        }
    }
}

@Composable
private fun PlayerArtworkMorphDriver(
    state: PlayerArtworkMorphState,
    ready: Boolean,
    disabled: Boolean,
) {
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
}

@Composable
private fun BoxScope.PlayerArtworkMorphImage(
    state: PlayerArtworkMorphState,
    ready: Boolean,
    aspectRatio: Float?,
) {
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
