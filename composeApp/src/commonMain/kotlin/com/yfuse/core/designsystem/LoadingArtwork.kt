package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Brushes and paths are cached across frames. All nine studies fit the same 56-unit square. */
internal class LoadingArtwork(
    dark: Boolean,
    accent: Color,
) {
    private val cyan = if (dark) Color(0xFF54E0EC) else Color(0xFF13AFC7)
    private val blue = lerp(if (dark) Color(0xFF819DFF) else Color(0xFF4968EE), accent, 0.12f)
    private val violet = if (dark) Color(0xFFB394FF) else Color(0xFF9055E9)
    private val pink = if (dark) Color(0xFFFF89C8) else Color(0xFFE85AA1)
    private val orange = if (dark) Color(0xFFFFAB70) else Color(0xFFF07847)
    private val gold = if (dark) Color(0xFFFFD178) else Color(0xFFE6A132)
    private val mint = if (dark) Color(0xFF66E5B8) else Color(0xFF22B590)
    private val centre = Offset(28f, 28f)
    private val ringStroke = Stroke(2.6f)
    private val rippleStroke = Stroke(1.5f)
    private val orbit =
        Brush.sweepGradient(
            0f to Color.Transparent,
            0.18f to Color.Transparent,
            0.28f to pink.copy(alpha = 0.18f),
            0.44f to pink,
            0.61f to violet,
            0.78f to blue,
            0.92f to cyan,
            1f to mint,
            center = centre,
        )
    private val core =
        Brush.radialGradient(
            listOf(Color.White, cyan, blue, violet),
            center = Offset(-2f, -2f),
            radius = 13f,
        )
    private val ripple = Brush.sweepGradient(listOf(cyan, blue, violet, pink, mint, cyan), centre)
    private val reverseRipple = Brush.sweepGradient(listOf(pink, violet, blue, cyan, mint, pink), centre)
    private val drops =
        listOf(
            Brush.linearGradient(listOf(mint, cyan, blue, violet), Offset(-9f, -12f), Offset(9f, 12f)),
            Brush.linearGradient(listOf(gold, orange, pink, violet), Offset(-9f, -12f), Offset(9f, 12f)),
        )
    private val petals =
        listOf(cyan to blue, violet to pink, orange to gold, mint to cyan).map { (from, to) ->
            Brush.linearGradient(listOf(from, to), Offset(-8f, -24f), Offset(8f, -2f))
        }
    private val bars =
        listOf(mint to cyan, cyan to blue, blue to violet, violet to pink, pink to orange).map { (from, to) ->
            Brush.linearGradient(listOf(from, to), Offset(0f, 13f), Offset(0f, 43f))
        }
    private val beads =
        listOf(mint to cyan, blue to violet, pink to orange).map { (from, to) ->
            Brush.linearGradient(listOf(from, to), Offset(-5f, -5f), Offset(5f, 5f))
        }
    private val drop =
        Path().apply {
            moveTo(1f, -12f)
            cubicTo(10f, -12f, 12f, -3f, 8f, 5f)
            cubicTo(5f, 13f, -7f, 14f, -9f, 5f)
            cubicTo(-12f, -3f, -7f, -11f, 1f, -12f)
            close()
        }
    private val petal =
        Path().apply {
            moveTo(-8f, -5f)
            lineTo(-8f, -19f)
            cubicTo(-8f, -28f, 8f, -27f, 9f, -17f)
            lineTo(9f, -5f)
            quadraticTo(0f, 0f, -8f, -5f)
            close()
        }

    fun DrawScope.draw(
        animation: LoadingAnimation,
        phase: Float,
        moving: Boolean,
    ) {
        when (animation) {
            LoadingAnimation.Orbit -> drawOrbit(phase)
            LoadingAnimation.Fusion -> drawFusion(phase)
            LoadingAnimation.Ribbon -> drawRibbon(phase)
            LoadingAnimation.Ripple -> drawRipple(phase, moving)
            LoadingAnimation.Spectrum -> drawSpectrum(phase, moving)
            LoadingAnimation.Beads,
            LoadingAnimation.BeadWave,
            LoadingAnimation.BeadBreath,
            LoadingAnimation.BeadRelay,
            -> drawBeads(animation, phase, moving)
        }
    }

    private fun DrawScope.drawOrbit(phase: Float) {
        rotate(phase * 720f, centre) {
            drawCircle(orbit, radius = 24.5f, center = centre, style = ringStroke)
        }
        translate(28f, 28f) {
            drawCircle(core, radius = 6.3f * orbCoreScale(phase), center = Offset.Zero)
        }
    }

    private fun DrawScope.drawFusion(phase: Float) {
        rotate(phase * 360f, centre) {
            repeat(2) { index ->
                val wave = sin((phase * 2f + index * 0.5f) * 2f * PI.toFloat())
                translate(if (index == 0) 14f else 42f, 28f) {
                    rotate(wave * 25f, Offset.Zero) {
                        scale(0.88f + wave * 0.08f, pivot = Offset.Zero) {
                            drawPath(drop, drops[index])
                        }
                    }
                }
            }
        }
    }

    private fun DrawScope.drawRibbon(phase: Float) {
        val close = (1f - cos(phase * 4f * PI.toFloat())) / 2f
        rotate(phase * 360f, centre) {
            repeat(4) { index ->
                translate(28f, 28f) {
                    rotate(index * 90f + close * 18f, Offset.Zero) {
                        translate(0f, close * 4f) {
                            scale(0.94f - close * 0.18f, pivot = Offset.Zero) {
                                drawPath(petal, petals[index])
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DrawScope.drawRipple(
        phase: Float,
        moving: Boolean,
    ) {
        repeat(2) { index ->
            val p = if (moving) loadingPhase(phase + index * 0.5f) else 0.35f + index * 0.4f
            val opacity = if (moving) sin(p * PI.toFloat()) * (1f - p) * 0.8f else 0.35f
            drawCircle(
                if (index == 0) ripple else reverseRipple,
                radius = 7f + p * 19f,
                center = centre,
                alpha = opacity.coerceIn(0f, 1f),
                style = rippleStroke,
            )
        }
        translate(28f, 28f) {
            drawCircle(core, radius = 7f * orbCoreScale(phase), center = Offset.Zero)
        }
    }

    private fun DrawScope.drawSpectrum(
        phase: Float,
        moving: Boolean,
    ) {
        repeat(5) { index ->
            val p = if (moving) phase + index * 0.104f else index * 0.16f
            val wave = (1f - cos(p * 2f * PI.toFloat())) / 2f
            val height = 9f + wave * 21f
            drawRoundRect(
                bars[index],
                topLeft = Offset(5.5f + index * 10f, 28f - height / 2f),
                size = Size(5f, height),
                cornerRadius = CornerRadius(2.5f),
                alpha = 0.55f + wave * 0.45f,
            )
        }
    }

    private fun DrawScope.drawBeads(
        animation: LoadingAnimation,
        phase: Float,
        moving: Boolean,
    ) {
        repeat(3) { index ->
            val dot = loadingDotFrame(animation, phase, index, moving)
            translate(dot.x, dot.y) {
                scale(dot.scaleX, dot.scaleY, Offset.Zero) {
                    drawCircle(beads[index], radius = 4.5f, center = Offset.Zero, alpha = dot.alpha)
                    if (animation != LoadingAnimation.Beads) {
                        drawOval(
                            Color.White.copy(alpha = 0.35f * dot.alpha),
                            topLeft = Offset(-2.5f, -3.5f),
                            size = Size(3.5f, 2.2f),
                        )
                    }
                }
            }
        }
    }
}
