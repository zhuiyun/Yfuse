package com.yfuse.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.yfuse.core.designsystem.SplashAnimation
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CraftDot(
    val x: Float,
    val y: Float,
    val color: Int,
)

/** The reference's exact 448 beads and 1266 sand samples, animated from a deterministic clock. */
internal class WaterFireCrafts(
    private val art: WaterFireArtwork,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF(0f, 0f, 512f, 512f)
    private val mask = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val relief = PorterDuffColorFilter(0xFFDBDCD6.toInt(), PorterDuff.Mode.SRC_ATOP)
    private val ironGradient =
        LinearGradient(
            0f,
            0f,
            0f,
            64f,
            intArrayOf(0xFFF3F6FA.toInt(), 0xFFC3CDD9.toInt(), 0xFF8C99AB.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    private val waxGradient =
        LinearGradient(
            0f,
            -20f,
            0f,
            20f,
            intArrayOf(0xFFFFE7A6.toInt(), 0xFFE9B94E.toInt(), 0xFFC8943B.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    private val sand =
        art.sand.mapIndexed { index, dot ->
            val release = 100f + (dot.x - art.sand.first().x) / (art.sand.last().x - art.sand.first().x) * 1360f
            SandGrain(dot, release, sqrt(2f * (dot.y + 120f) / 3200f) * 1000f, 3.8f + scatter(index, 11) * 2f)
        }

    fun draw(
        canvas: Canvas,
        time: Float,
        variant: SplashAnimation,
    ) {
        when (variant) {
            SplashAnimation.Beads -> beads(canvas, time)
            SplashAnimation.Sand -> sand(canvas, time)
            SplashAnimation.Rubbing -> rubbing(canvas, time)
            else -> Unit
        }
    }

    private fun ink(
        color: Int,
        alpha: Float = 1f,
        width: Float = 1f,
    ) {
        paint.shader = null
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.strokeWidth = width
    }

    private fun beads(
        canvas: Canvas,
        time: Float,
    ) {
        ink(0xFFAEA38D.toInt(), 0.12f)
        for (i in 0..35) {
            val position = i * 14.4f + 4f
            canvas.drawLine(position, 0f, position, 512f, paint)
            canvas.drawLine(0f, position, 512f, position, paint)
        }
        art.beads.forEachIndexed { index, dot ->
            val drop = span(time, index.toFloat() / (art.beads.size - 1) * 620f, 460f)
            if (drop <= 0f) return@forEachIndexed
            val settle = easeOutBack(drop)
            val y = lerp(-60f, dot.y, settle)
            val heat = span(time, 1230f + dot.y / 512f * 770f, 440f)
            val radius = 6.77f * lerp(0.5f, 1f, drop) * (1 + sin(heat * PiF) * 0.17f + heat * 0.06f)
            val alpha = (drop * 6f).coerceAtMost(1f)
            ink(0xFF514A40.toInt(), alpha * 0.16f)
            canvas.drawCircle(dot.x, y + 1.8f, radius + 0.6f, paint)
            ink(dot.color, alpha)
            canvas.drawCircle(dot.x, y, radius, paint)
            ink(0xFFFFFFFF.toInt(), alpha * (0.42f + sin(heat * PiF) * 0.35f))
            canvas.drawCircle(dot.x - radius * 0.28f, y - radius * 0.3f, radius * 0.38f, paint)
            ink(0xFFFFFFFF.toInt(), sin(heat * PiF).coerceAtLeast(0f) * 0.25f)
            canvas.drawCircle(dot.x, y, radius, paint)
        }
        val sweep = span(time, 1100f, 1000f)
        if (sweep > 0f && sweep < 1f) {
            val alpha = span(sweep, 0f, 0.12f) * (1 - span(sweep, 0.88f, 0.12f))
            val y = lerp(-135f, 545f, smooth(sweep))
            canvas.save()
            canvas.translate(0f, y)
            ink(0xFF4A5260.toInt(), alpha * 0.14f)
            canvas.drawRoundRect(35f, 14f, 477f, 84f, 20f, 20f, paint)
            ink(0xFFE8EDF3.toInt(), alpha)
            canvas.drawRoundRect(100f, -20f, 412f, 18f, 12f, 12f, paint)
            paint.shader = ironGradient
            canvas.drawRoundRect(28f, 0f, 484f, 68f, 18f, 18f, paint)
            ink(0xFFFFFFFF.toInt(), alpha * 0.7f, 2f)
            canvas.drawLine(50f, 4f, 462f, 4f, paint)
            canvas.restore()
        }
        repeat(12) { index ->
            val p = span(time, 1200f + index * 30f, 800f)
            val x = 65f + scatter(index, 16) * 370f
            val y = 90f + scatter(index, 23) * 310f - p * 65f
            // Concentric translucent circles give soft steam on Android 8 without RenderEffect.
            for (halo in 4 downTo 1) {
                ink(0xFFFFFFFF.toInt(), sin(p * PiF).coerceAtLeast(0f) * 0.08f)
                canvas.drawCircle(x, y, (5f + halo * 3f) * (0.5f + p), paint)
            }
        }
    }

    private fun sand(
        canvas: Canvas,
        time: Float,
    ) {
        for (grain in sand) {
            val elapsed = time - grain.releaseMs
            if (elapsed < 0f) continue
            val landed = elapsed >= grain.fallMs
            val impact = if (landed) 1 - span(elapsed, grain.fallMs, 140f) else 0f
            val y = if (landed) grain.dot.y else -120f + 1600f * elapsed * elapsed / 1_000_000f
            ink(grain.dot.color, if (landed) 1f else 0.72f)
            canvas.drawCircle(grain.dot.x, y, grain.radius * (1 + impact * 0.55f), paint)
        }
        val travel = span(time, 80f, 1420f)
        if (travel > 0f && travel < 1f) {
            val alpha = span(travel, 0f, 0.06f) * (1 - span(travel, 0.94f, 0.06f))
            val x = lerp(art.sand.first().x, art.sand.last().x, travel)
            ink(0xFFD1C6AD.toInt(), alpha * 0.7f, 2.5f)
            canvas.drawLine(x, -76f, x, -20f, paint)
            canvas.save()
            canvas.translate(x, -92f)
            ink(0xFFE1D8C6.toInt(), alpha)
            // A row of shrinking spans forms the reference's trapezoid funnel.
            for (row in 0..35) {
                val halfWidth = lerp(30f, 8f, row / 35f)
                canvas.drawLine(-halfWidth, row.toFloat() - 35, halfWidth, row.toFloat() - 35, paint)
            }
            canvas.restore()
        }
    }

    private fun rubbing(
        canvas: Canvas,
        time: Float,
    ) {
        ink(0xFFFFFFFF.toInt(), 0.58f)
        paint.colorFilter = relief
        canvas.save()
        canvas.translate(1.5f, 2f)
        canvas.drawBitmap(art.full, null, bounds, paint)
        canvas.restore()
        paint.colorFilter = null
        val reveal = span(time, 250f, 1350f)
        canvas.save()
        canvas.clipRect(0f, 0f, 512f, reveal * 512f)
        ink(0xFFFFFFFF.toInt())
        canvas.drawBitmap(art.full, null, bounds, paint)
        canvas.saveLayer(bounds, null)
        ink(0xFFFFFFFF.toInt(), 0.24f, 1.4f)
        for (line in 0..512 step 4) canvas.drawLine(0f, line.toFloat(), 512f, line + 20f, paint)
        paint.xfermode = mask
        paint.alpha = 255
        canvas.drawBitmap(art.full, null, bounds, paint)
        paint.xfermode = null
        canvas.restore()
        canvas.restore()
        if (reveal > 0f && reveal < 1f) {
            val alpha = span(reveal, 0f, 0.06f) * (1 - span(reveal, 0.94f, 0.06f))
            val stroke = ((time - 250f) / 170f) % 2f
            val x = 92f + (1 - abs(stroke - 1)) * 328f
            canvas.save()
            canvas.translate(x, reveal * 512f)
            ink(0xFF9E773E.toInt(), alpha * 0.13f)
            canvas.drawRoundRect(-85f, -4f, 100f, 38f, 17f, 17f, paint)
            ink(0xFFFFFFFF.toInt(), alpha)
            paint.shader = waxGradient
            canvas.drawRoundRect(-92f, -22f, 92f, 22f, 14f, 14f, paint)
            ink(0xFFFFFFFF.toInt(), alpha * 0.55f, 2f)
            canvas.drawLine(-74f, -17f, 74f, -17f, paint)
            canvas.restore()
        }
    }

    private data class SandGrain(
        val dot: CraftDot,
        val releaseMs: Float,
        val fallMs: Float,
        val radius: Float,
    )
}
