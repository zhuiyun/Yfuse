package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
        // The departure shrinks into the close key, so it no longer needs the launching card to
        // still be on screen underneath; a refreshed origin only serves as the fallback target.
        PlayerArtworkOrigins.resolve(origin.key)?.let { origin = it }
        completeExit = action
        visible = true
        exiting = true
        return true
    }
}

/**
 * The rectangle the departing artwork shrinks into, in the morph's own coordinates.
 *
 * The viewer just pressed the close key in the top-left corner, so the picture goes there: it
 * collapses to the size of that key's ring and rounds off into a disc on the way. When the ring
 * has never been measured, or its last measurement lies outside this window, the artwork falls
 * back to the card it was launched from.
 */
internal fun playerArtworkExitRect(
    anchor: Rect?,
    morphOrigin: Offset,
    width: Float,
    height: Float,
    fallback: () -> Rect,
): Rect {
    val local = anchor?.translate(-morphOrigin) ?: return fallback()
    val inside =
        local.width > 0f &&
            local.height > 0f &&
            local.left >= 0f &&
            local.top >= 0f &&
            local.right <= width &&
            local.bottom <= height
    return if (inside) local else fallback()
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
        var morphOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { morphOrigin = it.positionInRoot() }) {
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
            PlayerArtworkMorphImage(state, ready, aspectRatio, morphOrigin = { morphOrigin })
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
    morphOrigin: () -> Offset,
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
                    val toCloseKey = state.exiting
                    val start =
                        if (toCloseKey) {
                            playerArtworkExitRect(PlayerCloseAnchor.bounds, morphOrigin(), width, height) {
                                playerArtworkRect(state.origin, width, height)
                            }
                        } else {
                            playerArtworkRect(state.origin, width, height)
                        }
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
                            val transform =
                                playerArtworkTransform(
                                    child.width.toFloat(),
                                    child.height.toFloat(),
                                    mix(start.width, end.width),
                                    mix(start.height, end.height),
                                )
                            scaleX = transform.scale
                            scaleY = transform.scale
                            translationX =
                                mix(start.left, end.left) - end.left - transform.cropLeft * transform.scale
                            translationY =
                                mix(start.top, end.top) - end.top - transform.cropTop * transform.scale
                            transformOrigin =
                                androidx.compose.ui.graphics
                                    .TransformOrigin(0f, 0f)
                            alpha = state.opacity.value
                            // Leaving, the frame rounds off into a disc as it reaches the key;
                            // arriving, it only sheds the card's corner radius.
                            val frameRadius =
                                if (toCloseKey) {
                                    (1f - p) * minOf(mix(start.width, end.width), mix(start.height, end.height)) / 2f
                                } else {
                                    (16f * (1f - p)).dp.toPx()
                                }
                            val radius = frameRadius / transform.scale
                            shape =
                                GenericShape { _, _ ->
                                    addRoundRect(
                                        RoundRect(
                                            Rect(
                                                transform.cropLeft,
                                                transform.cropTop,
                                                transform.cropLeft + transform.cropWidth,
                                                transform.cropTop + transform.cropHeight,
                                            ),
                                            CornerRadius(radius),
                                        ),
                                    )
                                }
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
