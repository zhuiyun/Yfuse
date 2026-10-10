package com.yfuse.app

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import com.yfuse.core.designsystem.SplashAnimation
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** Designs 17–20, rendered on API 26+ without a WebView or per-frame bitmap allocation. */
internal class WaterFireMechanisms(
    private val art: WaterFireArtwork,
    private val variant: SplashAnimation,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF(0f, 0f, 512f, 512f)
    private val red = PorterDuffColorFilter(0xFFFF466E.toInt(), PorterDuff.Mode.SRC_IN)
    private val cyan = PorterDuffColorFilter(0xFF00D7FF.toInt(), PorterDuff.Mode.SRC_IN)
    private val shade = PorterDuffColorFilter(0xFF60543C.toInt(), PorterDuff.Mode.SRC_IN)
    private val scanLight =
        LinearGradient(
            0f,
            -23f,
            0f,
            23f,
            intArrayOf(0x007ECEFF, 0xDD7ECEFF.toInt(), 0x007ECEFF),
            null,
            Shader.TileMode.CLAMP,
        )
    private val camera = Camera().apply { setLocation(0f, 0f, -16.8f) }
    private val matrix = Matrix()
    private val mesh = FloatArray((MESH + 1) * (MESH + 1) * 2)
    private val ripples =
        FloatArray(mesh.size) { coordinate ->
            val vertex = coordinate / 2
            val x = (vertex % (MESH + 1)) / MESH.toFloat()
            val y = (vertex / (MESH + 1)) / MESH.toFloat()
            // Three spatial frequencies approximate the reference's fractal displacement field.
            if (coordinate % 2 == 0) {
                19f * sin(x * 17f + sin(y * 11f) * 2.8f) + 8f * sin(y * 37f + x * 24f) + 3f * sin(x * 73f - y * 61f)
            } else {
                18f * cos(y * 19f + sin(x * 13f) * 2.4f) + 7f * sin(x * 39f - y * 27f) + 3f * cos(y * 69f + x * 53f)
            }
        }
    private val offsets = arrayOf(-112.32f to 86.4f, 108f to -82.08f, 30.24f to 125.28f)
    private val rotations = floatArrayOf(-15f, 13f, 21f)
    private val tiles =
        if (variant == SplashAnimation.Domino) {
            val order =
                art.domino.map {
                    round((it.x - 40f) / 432f * 22f - 0.5f) * 1.45f +
                        round((it.y - 40f) / 432f * 22f - 0.5f)
                }
            val last = order.max().coerceAtLeast(1f)
            art.domino.mapIndexed { index, dot ->
                DominoTile(dot, order[index] / last * 1020f, scatter(index, 41) * 5f - 2.5f)
            }
        } else {
            emptyList()
        }

    fun draw(
        canvas: Canvas,
        time: Float,
    ) {
        when (variant) {
            SplashAnimation.Hologram -> hologram(canvas, time)
            SplashAnimation.Marble -> marble(canvas, time)
            SplashAnimation.Fan -> fan(canvas, time)
            SplashAnimation.Domino -> domino(canvas, time)
            else -> Unit
        }
    }

    private fun ink(
        color: Int,
        alpha: Float = 1f,
        width: Float = 1f,
    ) {
        paint.shader = null
        paint.colorFilter = null
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        paint.strokeWidth = width
    }

    private fun logo(
        canvas: Canvas,
        alpha: Float = 1f,
    ) {
        ink(0xFFFFFFFF.toInt(), alpha)
        canvas.drawBitmap(art.full, null, bounds, paint)
    }

    private fun hologram(
        canvas: Canvas,
        time: Float,
    ) {
        if (time >= WaterFireMechanismMotion.HOLO_SETTLED_MS) {
            logo(canvas)
            return
        }
        ink(0xFF6092CC.toInt(), 0.05f * (1 - span(time, 1550f, 280f)))
        for (y in 0..512 step 6) canvas.drawLine(0f, y.toFloat(), 512f, y.toFloat(), paint)
        val height = 432f / WaterFireMechanismMotion.HOLO_BANDS
        repeat(WaterFireMechanismMotion.HOLO_BANDS) { index ->
            val p = WaterFireMechanismMotion.bandProgress(time, index)
            if (p <= 0f) return@repeat
            val arrival = span(p, 0f, 0.2f)
            val close = span(p, 0.66f, 0.34f)
            val top = 40f + index * height
            val gap = height * 0.15f * (1 - close)
            canvas.save()
            canvas.translate(0f, 21.6f * (1 - arrival))
            canvas.scale(1f, lerp(0.18f, 1f, arrival), 256f, top + height / 2f)
            canvas.clipRect(0f, top + gap, 512f, top + height - gap)
            ink(0xFFFFFFFF.toInt(), arrival * (1 - close) * 0.48f)
            paint.colorFilter = red
            canvas.save()
            canvas.translate(3.9f * (1 - close), 0f)
            canvas.drawBitmap(art.full, null, bounds, paint)
            canvas.restore()
            paint.colorFilter = cyan
            canvas.save()
            canvas.translate(-3.9f * (1 - close), 0f)
            canvas.drawBitmap(art.full, null, bounds, paint)
            canvas.restore()
            logo(canvas, arrival)
            canvas.restore()
        }
        val scan = span(time, 50f, 1150f)
        if (scan > 0f && scan < 1f) {
            canvas.save()
            canvas.translate(0f, lerp(515f, -20f, scan))
            ink(0xFFFFFFFF.toInt(), span(scan, 0f, 0.08f) * (1 - span(scan, 0.88f, 0.12f)))
            paint.shader = scanLight
            canvas.drawRect(-64f, -23f, 576f, 23f, paint)
            ink(0xFFDFF7FF.toInt(), sin(scan * PiF) * 0.85f, 1.5f)
            canvas.drawLine(-64f, 0f, 576f, 0f, paint)
            canvas.restore()
        }
    }

    private fun marble(
        canvas: Canvas,
        time: Float,
    ) {
        if (time >= WaterFireMechanismMotion.MARBLE_SETTLED_MS) {
            logo(canvas)
            return
        }
        val dry = smooth(span(time, 1160f, 500f))
        val wet = 1 - smooth(span(time, 1220f, 500f))
        // Reuse one mesh for all three plates; the field relaxes as the water comes to rest.
        for (vertex in 0 until mesh.size / 2) {
            mesh[vertex * 2] = (vertex % (MESH + 1)) * 512f / MESH + ripples[vertex * 2] * (1 - dry * 0.7f)
            mesh[vertex * 2 + 1] = (vertex / (MESH + 1)) * 512f / MESH + ripples[vertex * 2 + 1] * (1 - dry * 0.7f)
        }
        art.layers.forEachIndexed { index, bitmap ->
            val p = WaterFireMechanismMotion.gatherProgress(time, index)
            if (p <= 0f) return@forEachIndexed
            val gather = span(p, 0.42f, 0.58f)
            val grow = lerp(0.18f, 1f, span(p, 0f, 0.42f))
            val visible = span(p, 0f, 0.16f)
            canvas.save()
            canvas.translate(offsets[index].first * (1 - gather), offsets[index].second * (1 - gather))
            canvas.rotate(rotations[index] * (1 - gather), 256f, 256f)
            canvas.scale(grow, grow, 256f, 256f)
            ink(0xFFFFFFFF.toInt(), visible * wet)
            canvas.drawBitmapMesh(bitmap, MESH, MESH, mesh, 0, null, 0, paint)
            ink(0xFFFFFFFF.toInt(), visible * dry)
            canvas.drawBitmap(bitmap, null, bounds, paint)
            canvas.restore()
        }
        val rake = span(time, 140f, 1280f)
        if (rake > 0f && rake < 1f) {
            val position =
                when {
                    rake < 0.48f -> lerp(-12f, 46f, smooth(rake / 0.48f))
                    rake < 0.55f -> 46f
                    else -> lerp(46f, 104f, smooth(span(rake, 0.55f, 0.35f)))
                }
            val y = 40f + position * 4.32f
            ink(0xFF7A705E.toInt(), span(rake, 0f, 0.1f) * (1 - span(rake, 0.9f, 0.1f)) * 0.8f, 2.5f)
            for (x in 58..454 step 17) canvas.drawLine(x.toFloat(), y, x.toFloat(), y + 34.5f, paint)
        }
    }

    private fun fan(
        canvas: Canvas,
        time: Float,
    ) {
        if (time >= WaterFireMechanismMotion.FAN_SETTLED_MS) {
            logo(canvas)
            return
        }
        repeat(WaterFireMechanismMotion.FAN_PLEATS) { index ->
            val p = WaterFireMechanismMotion.pleatProgress(time, index)
            if (p <= 0f) return@repeat
            val left = 40f + index * 24f
            val alpha = span(p, 0f, 0.14f)
            canvas.save()
            canvas.rotate(WaterFireMechanismMotion.pleatRotation(time, index), 256f, 592.96f)
            // Offset the clipped silhouette itself, so shadowing never draws a rectangular paper strip.
            canvas.save()
            canvas.translate(3f, 5f)
            canvas.clipRect(left, 0f, left + 24f, 512f)
            ink(0xFFFFFFFF.toInt(), alpha * (1 - p.coerceIn(0f, 1f)) * 0.22f)
            paint.colorFilter = shade
            canvas.drawBitmap(art.full, null, bounds, paint)
            canvas.restore()
            canvas.clipRect(left, 0f, left + 24f, 512f)
            logo(canvas, alpha)
            canvas.restore()
        }
    }

    private fun domino(
        canvas: Canvas,
        time: Float,
    ) {
        val size = 432f / 22f * 0.92f
        for (tile in tiles) {
            val p = WaterFireMechanismMotion.dominoProgress(time, tile.delay)
            if (p <= 0f) continue
            val angle = WaterFireMechanismMotion.dominoTilt(p)
            val alpha = span(p, 0f, 0.12f)
            canvas.save()
            canvas.translate(tile.dot.x, tile.dot.y + size / 2f)
            canvas.rotate(tile.rotation)
            ink(0xFF3C465A.toInt(), alpha * (0.12f + sin(p * PiF) * 0.07f))
            val height = size * cos(angle * PiF / 180f)
            canvas.drawRoundRect(-size / 2f, -height + 2f, size / 2f + 1f, 3f, 2.5f, 2.5f, paint)
            camera.save()
            camera.rotateX(angle)
            camera.getMatrix(matrix)
            camera.restore()
            canvas.concat(matrix)
            ink(tile.dot.color, alpha)
            canvas.drawRoundRect(-size / 2f, -size, size / 2f, 0f, 2f, 2f, paint)
            // A translucent white top edge and dark bottom edge give each falling tile a bevel.
            ink(0xFFFFFFFF.toInt(), alpha * 0.5f, 1.5f)
            canvas.drawLine(-size / 2f + 2f, -size + 1.5f, size / 2f - 2f, -size + 1.5f, paint)
            ink(0xFF000000.toInt(), alpha * 0.14f, 1.5f)
            canvas.drawLine(-size / 2f + 2f, -1.5f, size / 2f - 2f, -1.5f, paint)
            canvas.restore()
        }
    }

    private data class DominoTile(
        val dot: CraftDot,
        val delay: Float,
        val rotation: Float,
    )

    private companion object {
        const val MESH = 32
    }
}
