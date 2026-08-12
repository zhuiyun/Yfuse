package com.yfuse.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.sync.WatchSticker
import com.yfuse.core.sync.WatchStickerCategory
import com.yfuse.core.sync.WatchStickerMotion
import com.yfuse.core.sync.WatchStickers
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One clock for every visible preset in the tray; standalone sent stickers may own one. */
private val LocalStickerClock = compositionLocalOf<State<Float>?> { null }
private const val SHARED_CLOCK_MS = 60_000

/**
 * One sticker, moving.
 *
 * The motion is driven straight into a [graphicsLayer] block: the phase is read in the draw
 * phase, so an animating sticker costs a layer update per frame and never a recomposition.
 * That matters more here than anywhere else in the app — a transcript can hold a dozen of
 * these at once, over a playing film, inside a list somebody is scrolling.
 *
 * 减弱动态效果 stops the motion outright rather than slowing it. A sticker that has stopped
 * moving is still the sticker that was sent; there is nothing to convey by keeping it going.
 */
@Composable
fun WatchStickerGlyph(
    sticker: WatchSticker,
    sizeSp: Float,
    modifier: Modifier = Modifier,
    /** Set false where a still glyph is wanted regardless of the preset — a dense list. */
    animated: Boolean = true,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val motion = if (animated && !reduceMotion) sticker.motion else WatchStickerMotion.Still
    val sharedClock = LocalStickerClock.current
    val phase = when {
        motion == WatchStickerMotion.Still -> null
        sharedClock == null -> rememberMotionPhase(motion)
        else -> remember(motion, sharedClock) {
            derivedStateOf {
                ((sharedClock.value * SHARED_CLOCK_MS) % motion.periodMs) / motion.periodMs
            }
        }
    }

    Text(
        sticker.glyph,
        style = sc(sizeSp, 400),
        color = Color.White,
        modifier = modifier
            .semantics { contentDescription = sticker.label }
            .then(
                if (phase == null) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        // Pivot at the top for anything that hangs, centre for everything else.
                        transformOrigin = if (motion == WatchStickerMotion.Swing) {
                            TransformOrigin(0.5f, 0f)
                        } else {
                            TransformOrigin.Center
                        }
                        applyStickerMotion(motion, phase.value, size.height)
                    }
                },
            ),
    )
}

/** 0f..1f, once per [WatchStickerMotion.periodMs], forever. */
@Composable
private fun rememberMotionPhase(motion: WatchStickerMotion): State<Float> =
    rememberInfiniteTransition(label = "sticker-motion").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Linear, and the shaping happens in the maths below. An eased phase would ease
            // the *cycle* rather than the movement, which reads as a stutter at the seam.
            animation = tween(motion.periodMs, easing = LinearEasing),
        ),
        label = motion.name,
    )

/**
 * The whole animation vocabulary, in one place, as a function of phase.
 *
 * Amplitudes are relative to the glyph's own height so the same preset reads the same in a
 * 34dp tray key and in a 40sp bubble.
 */
private fun GraphicsLayerScope.applyStickerMotion(
    motion: WatchStickerMotion,
    phase: Float,
    height: Float,
) {
    val turn = phase * 2f * PI.toFloat()
    when (motion) {
        WatchStickerMotion.Still -> Unit

        // abs(sin) gives two hops per cycle and a natural hang at the top of each.
        WatchStickerMotion.Bounce -> translationY = -height * 0.22f * abs(sin(turn))

        WatchStickerMotion.Shake -> rotationZ = 11f * sin(turn * 2f)

        WatchStickerMotion.Spin -> rotationZ = phase * 360f

        WatchStickerMotion.Pulse -> {
            val scale = 1f + 0.13f * sin(turn)
            scaleX = scale
            scaleY = scale
        }

        WatchStickerMotion.Swing -> rotationZ = 17f * sin(turn)

        WatchStickerMotion.Wobble -> {
            rotationZ = 9f * sin(turn)
            // Squash a quarter-cycle out of phase, so the tilt and the stretch never peak
            // together — that is the difference between "alive" and "being shaken".
            scaleY = 1f + 0.07f * sin(turn - PI.toFloat() / 2f)
        }

        WatchStickerMotion.Float -> {
            translationY = -height * 0.10f * sin(turn)
            rotationZ = 3f * sin(turn + PI.toFloat() / 3f)
        }

        WatchStickerMotion.Jelly -> {
            val squeeze = sin(turn)
            scaleX = 1f + 0.11f * squeeze
            scaleY = 1f - 0.09f * squeeze
            translationY = height * 0.025f * abs(squeeze)
        }

        WatchStickerMotion.Flip -> {
            rotationY = phase * 360f
            scaleX = 0.94f + 0.06f * abs(cos(turn))
        }

        WatchStickerMotion.Pop -> {
            val pulse = (sin(turn) + 1f) * 0.5f
            val scale = 0.94f + 0.15f * pulse * pulse
            scaleX = scale
            scaleY = scale
        }

        WatchStickerMotion.Heartbeat -> {
            val beat = maxOf(0f, sin(turn * 2f))
            val scale = 1f + 0.12f * beat * beat
            scaleX = scale
            scaleY = scale
        }

        WatchStickerMotion.Orbit -> {
            translationX = height * 0.08f * cos(turn)
            translationY = height * 0.08f * sin(turn)
            rotationZ = 6f * sin(turn)
        }

        WatchStickerMotion.Sway -> {
            translationX = height * 0.07f * sin(turn)
            rotationZ = 6f * sin(turn - PI.toFloat() / 4f)
        }
    }
}

/**
 * The 64-preset tray, above the transcript. Categories keep the catalogue readable without
 * turning it into one endless rail. Only the selected shelf is composed, while every visible
 * glyph still shares one animation clock.
 */
@Composable
internal fun WatchStickerTray(
    enabled: Boolean,
    onPick: (WatchSticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCategory = remember { mutableStateOf(WatchStickerCategory.Reaction) }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val accent = LocalAccentColors.current
    val sharedClock = if (reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "sticker-tray-clock").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(SHARED_CLOCK_MS, easing = LinearEasing),
            ),
            label = "sticker-tray-phase",
        )
    }
    CompositionLocalProvider(LocalStickerClock provides sharedClock) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(
                    items = WatchStickerCategory.entries,
                    key = WatchStickerCategory::name,
                ) { category ->
                    val isSelected = category == selectedCategory.value
                    Box(
                        Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                            .pressable(
                                enabled = enabled,
                                haptic = HapticSignal.Select,
                                role = Role.Tab,
                                onClickLabel = category.label,
                                onClick = { selectedCategory.value = category },
                            )
                            .semantics(mergeDescendants = true) { selected = isSelected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = category.label,
                            style = if (isSelected) {
                                AppTypography.caption.strong
                            } else {
                                AppTypography.caption.medium
                            },
                            color = Color.White.copy(alpha = if (isSelected) 1f else 0.66f),
                            modifier = Modifier
                                .glass(
                                    shape = GlassShapes.chip,
                                    fill = if (isSelected) {
                                        accent.accent.copy(alpha = 0.58f)
                                    } else {
                                        PlayerTokens.chipFill
                                    },
                                    border = if (isSelected) {
                                        Color.White.copy(alpha = 0.32f)
                                    } else {
                                        PlayerTokens.chipBorder
                                    },
                                )
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Lazy composition keeps off-screen glyph layers absent; LocalStickerClock
                // means every visible glyph reads one phase source instead of owning a clock.
                items(
                    items = WatchStickers.inCategory(selectedCategory.value),
                    key = WatchSticker::id,
                ) { sticker ->
                    // Keep the visual chip compact, but give it the same 44dp minimum target
                    // as every other control.
                    Box(
                        Modifier
                            .size(44.dp)
                            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                            .pressable(
                                enabled = enabled,
                                haptic = HapticSignal.Confirm,
                                onClickLabel = sticker.label,
                                onClick = { onPick(sticker) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .glass(
                                    shape = GlassShapes.chip,
                                    fill = PlayerTokens.chipFill,
                                    border = PlayerTokens.chipBorder,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            WatchStickerGlyph(sticker, sizeSp = 16f)
                        }
                    }
                }
            }
        }
    }
}
