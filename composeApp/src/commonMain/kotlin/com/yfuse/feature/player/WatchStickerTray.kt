package com.yfuse.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
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

/**
 * One sticker, moving.
 *
 * Motion writes directly into [GraphicsLayerScope], so the player can keep rendering video
 * without triggering a Compose recomposition for every animation frame. 减弱动态效果 turns
 * all presets into still glyphs.
 */
@Composable
fun WatchStickerGlyph(
    sticker: WatchSticker,
    sizeSp: Float,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val motion = if (animated && !reduceMotion) sticker.motion else WatchStickerMotion.Still
    val phase = if (motion == WatchStickerMotion.Still) null else rememberMotionPhase(motion)

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
            animation = tween(motion.periodMs, easing = LinearEasing),
        ),
        label = motion.name,
    )

/** The animation vocabulary in one place, expressed as a cheap graphics-layer transform. */
private fun GraphicsLayerScope.applyStickerMotion(
    motion: WatchStickerMotion,
    phase: Float,
    height: Float,
) {
    val turn = phase * 2f * PI.toFloat()
    when (motion) {
        WatchStickerMotion.Still -> Unit

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
            val pulse = ((sin(turn) + 1f) * 0.5f)
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
 * 64-preset tray. Categories keep the picker readable instead of turning it into a single
 * endless rail. Only the selected shelf is animated, so the UI previews motion without
 * running dozens of infinite transitions at once.
 */
@Composable
internal fun WatchStickerTray(
    enabled: Boolean,
    onPick: (WatchSticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf(WatchStickerCategory.Reaction) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchStickerCategory.entries.forEach { category ->
                val selected = category == selectedCategory
                Text(
                    text = category.label,
                    style = sc(10.5f, if (selected) 700 else 550),
                    color = Color.White.copy(alpha = if (selected) 1f else 0.66f),
                    modifier = Modifier
                        .pressable(
                            enabled = enabled,
                            haptic = HapticSignal.Select,
                            onClickLabel = category.label,
                            onClick = { selectedCategory = category },
                        )
                        .glass(
                            shape = GlassShapes.chip,
                            fill = if (selected) {
                                Brand.Primary.copy(alpha = 0.58f)
                            } else {
                                PlayerTokens.chipFill
                            },
                            border = if (selected) {
                                Color.White.copy(alpha = 0.32f)
                            } else {
                                PlayerTokens.chipBorder
                            },
                        )
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = WatchStickers.inCategory(selectedCategory),
                key = WatchSticker::id,
            ) { sticker ->
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
                            .size(38.dp)
                            .glass(
                                shape = GlassShapes.chip,
                                fill = PlayerTokens.chipFill,
                                border = PlayerTokens.chipBorder,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        WatchStickerGlyph(sticker, sizeSp = 17f, animated = true)
                    }
                }
            }
        }
    }
}
