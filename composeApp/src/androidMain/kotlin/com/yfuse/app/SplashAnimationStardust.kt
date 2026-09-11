package com.yfuse.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「光粒汇聚」 — the 粒子光效 launch.
 *
 * 光粒从四周汇聚成标志 → 标志亮起 → 字标浮起. Dozens of light grains start scattered
 * well outside the mark, each on its own delay, and are drawn in along an ease-out to a point
 * on the ribbon's silhouette. As they land the mark itself rises through them, and the grains
 * give way to it: the artwork was never assembled out of dots, the dots simply announced where
 * it would appear. Same 1.2s budget as the other water-fire choreographies.
 *
 * The grains are part of this artwork, not a [com.yfuse.core.designsystem.ParticleLight]
 * lease: the variant is chosen by name in settings, so a user who picked it is asking for them.
 */
internal object SplashStardust : SplashChoreography {
    override val durationMs = 1_200f
    override val fadeStartMs = durationMs - FADE_MS

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        mark ?: return
        drawWaterFireBloom(bell(span(nowMs, BloomStartMs, BloomMs)))
        drawGrains(nowMs)
        val rise = span(nowMs, MarkStartMs, MarkMs)
        drawCentredMark(
            mark = mark,
            scale = lerp(0.86f, 1f, easeOutExpo(rise)),
            alpha = easeOutCubic(span(nowMs, MarkStartMs, MarkFadeInMs)),
        )
    }

    override fun wordmark(nowMs: Float) = easeOutCubic(span(nowMs, WordmarkStartMs, WordmarkMs))

    private fun DrawScope.drawGrains(nowMs: Float) {
        // The grains dissolve together once the mark has risen through them.
        val hold = 1f - span(nowMs, GrainFadeStartMs, GrainFadeMs)
        if (hold <= 0.001f) return
        val side = size.minDimension * MarkSide
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        for (index in 0 until GrainCount) {
            val target = Silhouette[index % Silhouette.size]
            val targetX = left + target.x * side
            val targetY = top + target.y * side
            // Born on a wide ring, each at its own angle and distance, so the gathering reads
            // as coming from everywhere rather than from a smaller copy of the mark.
            val angle = scatter(index, 1) * Tau
            val reach = side * (0.75f + scatter(index, 2) * 0.55f)
            val fromX = centreX + cos(angle) * reach
            val fromY = centreY + sin(angle) * reach
            val delay = scatter(index, 3) * GatherStaggerMs
            val gather = easeOutExpo(span(nowMs, delay, GatherMs))
            if (gather <= 0f) continue
            val arrive = easeOutCubic(span(nowMs, delay, GrainFadeInMs))
            val alpha = arrive * hold * (0.55f + scatter(index, 4) * 0.45f)
            if (alpha <= 0.001f) continue
            val centre = Offset(lerp(fromX, targetX, gather), lerp(fromY, targetY, gather))
            val radius = side * (0.008f + scatter(index, 5) * 0.010f)
            // Water on the left of the ribbon, fire on the right — the grain takes the colour
            // of the side it is landing on, so the assembled cloud already reads as the mark.
            val ink = if (target.x < 0.5f) WaterGrain else FireGrain
            drawCircle(ink.copy(alpha = alpha * 0.18f), radius * 2.8f, centre)
            drawCircle(Color.White.copy(alpha = alpha), radius, centre)
        }
    }
}

/**
 * Points along the ribbon's silhouette, in 0..1 of the mark's box — the same chevron and
 * motion bars the Android 13 themed icon (`ic_yfuse_mark_mono`) flattens the ribbon to.
 */
private val Silhouette: List<Offset> =
    buildList {
        fun edge(
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            steps: Int,
        ) {
            for (step in 0 until steps) {
                val f = step / steps.toFloat()
                add(Offset(lerp(fromX, toX, f) / 108f, lerp(fromY, toY, f) / 108f))
            }
        }
        // Outer edge out to the point, inner edge back.
        edge(32f, 20f, 87f, 53f, 9)
        edge(87f, 53f, 33f, 84f, 9)
        edge(33f, 84f, 46f, 72f, 3)
        edge(46f, 72f, 69f, 53f, 4)
        edge(69f, 53f, 45f, 33f, 4)
        edge(45f, 33f, 32f, 20f, 3)
        // Motion bars, top to bottom.
        edge(26.5f, 50.5f, 38.5f, 50.5f, 3)
        edge(20.5f, 58.5f, 23.5f, 58.5f, 1)
        edge(30.5f, 58.5f, 42.5f, 58.5f, 3)
        edge(27.5f, 66.5f, 36.5f, 66.5f, 2)
    }

/** Matches [drawCentredMark]'s box so the grains land on the artwork, not beside it. */
private const val MarkSide = 0.82f
private const val GrainCount = 56

private val WaterGrain = Color(0xFF22D3EE)
private val FireGrain = Color(0xFFF97316)

private const val GatherStaggerMs = 240f
private const val GatherMs = 560f
private const val GrainFadeInMs = 140f
private const val GrainFadeStartMs = 660f
private const val GrainFadeMs = 300f
private const val BloomStartMs = 180f
private const val BloomMs = 820f
private const val MarkStartMs = 430f
private const val MarkFadeInMs = 320f
private const val MarkMs = 520f
private const val WordmarkStartMs = 640f
private const val WordmarkMs = 360f
