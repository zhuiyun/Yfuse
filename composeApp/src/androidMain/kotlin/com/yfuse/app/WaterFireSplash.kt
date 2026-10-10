package com.yfuse.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.shared.R
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Registered source artwork; decoded once, never during a draw. Blur mipmaps work on API 26+. */
internal class WaterFireArtwork(
    private val resources: Resources,
) {
    private fun bitmap(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)

    val full = bitmap(R.drawable.water_fire_logo)
    val outline = bitmap(R.drawable.water_fire_outline)
    val layers =
        listOf(
            bitmap(R.drawable.water_fire_blue),
            bitmap(R.drawable.water_fire_orange),
            bitmap(R.drawable.water_fire_gold),
        )
    val soft =
        listOf(
            bitmap(R.drawable.water_fire_blue_blur8),
            bitmap(R.drawable.water_fire_orange_blur8),
            bitmap(R.drawable.water_fire_gold_blur8),
        )
    val diffuse =
        listOf(
            bitmap(R.drawable.water_fire_blue_blur24),
            bitmap(R.drawable.water_fire_orange_blur24),
            bitmap(R.drawable.water_fire_gold_blur24),
        )
    val fullSoft = bitmap(R.drawable.water_fire_logo_blur8)
    val fullDiffuse = bitmap(R.drawable.water_fire_logo_blur24)
    val beads = craftDots(R.raw.water_fire_beads).sortedBy { it.y * 1.7f + it.x }
    val sand = craftDots(R.raw.water_fire_sand).sortedBy { it.x }
    val domino by lazy { craftDots(R.raw.water_fire_domino) }

    private fun craftDots(id: Int): List<CraftDot> =
        resources.openRawResource(id).bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() }
                .map { line ->
                    val fields = line.split(',')
                    CraftDot(
                        40f + fields[0].toFloat() * 4.32f,
                        40f + fields[1].toFloat() * 4.32f,
                        (0xFF000000 or fields[2].toLong(16)).toInt(),
                    )
                }.toList()
        }

    val dots: List<InkDot> =
        buildList {
            val random = Random(63)
            for (y in 0 until full.height step 8) {
                for (x in 0 until full.width step 8) {
                    val color = full.getPixel(x, y)
                    if (color ushr 24 < 180) continue
                    val angle = random.nextFloat() * Tau
                    val radius = 220f + random.nextFloat() * 250f
                    add(
                        InkDot(
                            x.toFloat(),
                            y.toFloat(),
                            color,
                            cos(angle) * radius,
                            sin(angle) * radius,
                            random.nextFloat(),
                        ),
                    )
                }
            }
        }
}

internal data class InkDot(
    val x: Float,
    val y: Float,
    val color: Int,
    val dx: Float,
    val dy: Float,
    val seed: Float,
)

/** Native translation of the approved player-splash designs, in a 512-unit artwork space. */
internal class WaterFireSplash(
    private val variant: SplashAnimation,
    private val art: WaterFireArtwork,
) : SplashChoreography {
    override val fadeStartMs = variant.motionDurationMs().toFloat()
    override val durationMs = fadeStartMs + FADE_MS
    override val showWordmark = false
    override val background =
        when (variant) {
            SplashAnimation.Bloom, SplashAnimation.Fold, SplashAnimation.Crayon,
            SplashAnimation.Beads, SplashAnimation.Sand, SplashAnimation.Rubbing,
            SplashAnimation.Fan, SplashAnimation.Domino,
            -> Color(0xFFFBF7EF)
            SplashAnimation.Stitch -> Color(0xFFF5F4F0)
            SplashAnimation.Focus -> Color(0xFFF4F4F6)
            SplashAnimation.Marble -> Color(0xFFF3F5F8)
            SplashAnimation.Hologram -> Color(0xFFECF2FB)
            else -> Color(0xFFF3F8FE)
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF(0f, 0f, 512f, 512f)
    private val path = Path()
    private val matrix = Matrix()
    private val camera = Camera()
    private val mask = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val coldFringe = PorterDuffColorFilter(0xFF5FB9FF.toInt(), PorterDuff.Mode.SRC_ATOP)
    private val warmFringe = PorterDuffColorFilter(0xFFFF967E.toInt(), PorterDuff.Mode.SRC_ATOP)
    private val colors = intArrayOf(0xFF68C8F4.toInt(), 0xFFFF9360.toInt(), 0xFFFFC365.toInt())
    private val bloomOffsets = arrayOf(-105f to -100f, 106f to 35f, -42f to 128f)
    private val crystalOrigins = arrayOf(225f to 174f, 369f to 307f, 133f to 430f)
    private val crafts = WaterFireCrafts(art)
    private val mechanisms =
        if (variant in
            listOf(SplashAnimation.Hologram, SplashAnimation.Marble, SplashAnimation.Fan, SplashAnimation.Domino)
        ) {
            WaterFireMechanisms(art, variant)
        } else {
            null
        }

    override fun wordmark(nowMs: Float) = 1f

    override fun DrawScope.drawMark(
        nowMs: Float,
        mark: ImageBitmap?,
    ) {
        val canvas = drawContext.canvas.nativeCanvas
        val scale = size.minDimension / 512f
        canvas.save()
        canvas.translate((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f)
        canvas.scale(scale, scale)
        when (variant) {
            SplashAnimation.Magnet -> magnet(canvas, nowMs)
            SplashAnimation.Bloom -> bloom(canvas, nowMs)
            SplashAnimation.Register -> register(canvas, nowMs)
            SplashAnimation.Pour -> pour(canvas, nowMs)
            SplashAnimation.Fold -> fold(canvas, nowMs)
            SplashAnimation.Stitch -> stitch(canvas, nowMs)
            SplashAnimation.Crystal -> crystal(canvas, nowMs)
            SplashAnimation.Focus -> focus(canvas, nowMs)
            SplashAnimation.Crayon -> crayon(canvas, nowMs)
            SplashAnimation.Beads, SplashAnimation.Sand, SplashAnimation.Rubbing -> crafts.draw(canvas, nowMs, variant)
            SplashAnimation.Hologram, SplashAnimation.Marble, SplashAnimation.Fan, SplashAnimation.Domino ->
                mechanisms?.draw(canvas, nowMs)
            else -> image(canvas, art.full)
        }
        canvas.restore()
    }

    private fun image(
        canvas: Canvas,
        bitmap: Bitmap,
        alpha: Float = 1f,
    ) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bitmap, null, bounds, paint)
        paint.alpha = 255
    }

    private fun ink(
        color: Int,
        alpha: Float = 1f,
        width: Float = 1f,
    ) {
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.strokeWidth = width
        paint.style = Paint.Style.FILL
    }

    private fun ring(
        canvas: Canvas,
        time: Float,
        start: Float,
        x: Float = 256f,
        y: Float = 256f,
    ) {
        val p = span(time, start, 650f)
        if (p <= 0f || p >= 1f) return
        ink(0xFF91BBD4.toInt(), sin(p * PiF) * (1 - p) * 0.55f, 1.4f)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(x, y, lerp(20f, 242f, easeOutCubic(p)), paint)
        paint.style = Paint.Style.FILL
    }

    private fun blurred(
        canvas: Canvas,
        crisp: Bitmap,
        soft: Bitmap,
        diffuse: Bitmap,
        blur: Float,
        alpha: Float,
    ) {
        val b = blur.coerceIn(0f, 24f)
        if (b > 8f) {
            image(canvas, diffuse, alpha * (b - 8f) / 16f)
            image(canvas, soft, alpha * (24f - b) / 16f)
        } else {
            image(canvas, soft, alpha * b / 8f)
            image(canvas, crisp, alpha * (1 - b / 8f))
        }
    }

    private fun magnet(
        canvas: Canvas,
        time: Float,
    ) {
        val resolved = smooth(span(time, 1400f, 300f))
        if (resolved < 1f) {
            for (dot in art.dots) {
                val p = easeOutCubic(span(time, 260f + dot.seed * 300f, 850f))
                ink(dot.color, (0.18f + p * 0.82f) * (1 - resolved))
                canvas.drawCircle(
                    dot.x + dot.dx * (1 - p),
                    dot.y + dot.dy * (1 - p),
                    (2.3f + dot.seed) * (1.7f - 0.7f * p),
                    paint,
                )
            }
        }
        image(canvas, art.full, resolved)
        ring(canvas, time, 200f)
        ring(canvas, time, 1280f)
    }

    private fun bloom(
        canvas: Canvas,
        time: Float,
    ) {
        art.layers.forEachIndexed { index, bitmap ->
            val p = span(time, index * 70f, 1600f)
            val settle = easeOutCubic(p)
            canvas.save()
            canvas.translate(bloomOffsets[index].first * (1 - settle), bloomOffsets[index].second * (1 - settle))
            val scale = lerp(1.8f, 1f, settle)
            canvas.scale(scale, scale, 256f, 256f)
            blurred(
                canvas,
                bitmap,
                art.soft[index],
                art.diffuse[index],
                24f * (1 - smooth(span(p, 0.2f, 0.8f))),
                (p * 5).coerceAtMost(1f),
            )
            canvas.restore()
        }
        ring(canvas, time, 1400f)
    }

    private fun register(
        canvas: Canvas,
        time: Float,
    ) {
        val alignment = 1 - smooth(span(time, 1340f, 520f))
        art.layers.forEachIndexed { index, bitmap ->
            val p = span(time, index * 160f, 520f)
            if (p <= 0f) return@forEachIndexed
            val settle = easeOutBack(p)
            canvas.save()
            canvas.translate(
                (
                    if (index ==
                        1
                    ) {
                        15f
                    } else {
                        -15f
                    }
                ) * alignment,
                -620f * (1 - settle) + (if (index == 0) 12f else -12f) * alignment,
            )
            canvas.rotate((if (index == 1) 2.2f else -2f) * alignment, 256f, 256f)
            image(canvas, bitmap)
            canvas.restore()
        }
        ink(0xFF8196A8.toInt(), alignment * 0.5f, 1f)
        for (x in listOf(20f, 492f)) {
            for (y in listOf(30f, 482f)) {
                canvas.drawLine(x - 8, y, x + 8, y, paint)
                canvas.drawLine(x, y - 8, x, y + 8, paint)
            }
        }
        ring(canvas, time, 460f)
        ring(canvas, time, 1340f)
    }

    private fun pour(
        canvas: Canvas,
        time: Float,
    ) {
        image(canvas, art.outline, 0.13f * (1 - span(time, 1800f, 300f)))
        art.layers.forEachIndexed { index, bitmap ->
            val p = smooth(span(time, 420f + index * 260f, 1150f))
            canvas.save()
            when (index) {
                0 -> canvas.clipRect(0f, 0f, 512f, 512f * p)
                1 -> canvas.clipRect(512f * (1 - p), 0f, 512f, 512f)
                else -> canvas.clipRect(0f, 512f * (1 - p), 512f, 512f)
            }
            image(canvas, bitmap)
            canvas.restore()
            if (p > 0f && p < 1f) {
                masked(canvas, bitmap) {
                    ink(0xFFFFFFFF.toInt(), 0.75f, 3f)
                    if (index == 1) {
                        canvas.drawLine(512f * (1 - p), 0f, 512f * (1 - p), 512f, paint)
                    } else {
                        val y = 512f * if (index == 0) p else 1 - p
                        canvas.drawLine(0f, y, 512f, y, paint)
                    }
                }
            }
        }
        val drop = span(time, 80f, 440f)
        if (drop > 0f && drop < 1f) {
            ink(colors[0], sin(drop * PiF))
            val y = lerp(-90f, 104f, drop * drop)
            canvas.drawOval(149f, y - 10f, 163f, y + 10f, paint)
        }
        ring(canvas, time, 500f, 156f, 104f)
    }

    private fun fold(
        canvas: Canvas,
        time: Float,
    ) {
        art.layers.forEachIndexed { index, bitmap ->
            val p = span(time, 80f + index * 260f, 1200f)
            if (p <= 0f) return@forEachIndexed
            val remaining = 1 - easeOutBack(p)
            val pivotX = if (index == 1) 60f else 256f
            val pivotY = if (index == 0) 365f else 130f
            camera.save()
            if (index ==
                1
            ) {
                camera.rotateY(93f * remaining)
            } else {
                camera.rotateX((if (index == 0) -93f else 93f) * remaining)
            }
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-pivotX, -pivotY)
            matrix.postTranslate(pivotX, pivotY)
            canvas.save()
            canvas.concat(matrix)
            image(canvas, bitmap, (p * 6).coerceAtMost(1f))
            canvas.restore()
        }
        shine(canvas, time, 1350f, 650f)
    }

    private fun stitch(
        canvas: Canvas,
        time: Float,
    ) {
        val p = smooth(span(time, 360f, 1400f))
        canvas.save()
        canvas.clipRect(0f, 0f, 512f, 512f * p)
        image(canvas, art.full)
        masked(canvas, art.full) {
            ink(0xFFFFFFFF.toInt(), 0.22f * (1 - span(time, 1750f, 550f)), 1f)
            for (line in -512..512 step 6) canvas.drawLine(line.toFloat(), 0f, line + 512f, 512f, paint)
        }
        canvas.restore()
        if (p > 0f && p < 1f) {
            val x = 256f + sin(time * 0.06f) * 130f
            val y = p * 512f
            ink(0xFF899AA5.toInt(), sin(p * PiF), 1.6f)
            canvas.drawLine(x - 13f, y - 32f, x + 5f, y + 11f, paint)
            ink(0xFFFFFFFF.toInt(), sin(p * PiF), 1f)
            canvas.drawLine(x - 11f, y - 28f, x + 4f, y + 7f, paint)
        }
        shine(canvas, time, 1720f, 800f)
    }

    private fun crystal(
        canvas: Canvas,
        time: Float,
    ) {
        art.layers.forEachIndexed { index, bitmap ->
            val p = easeOutCubic(span(time, 160f + index * 170f, 1150f))
            path.reset()
            path.addCircle(crystalOrigins[index].first, crystalOrigins[index].second, 580f * p, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            image(canvas, bitmap)
            canvas.restore()
        }
        repeat(36) { i ->
            val p = span(time, 180f + scatter(i, 9) * 350f, 1150f)
            val angle = i * Tau / 36
            val radius = (90f + scatter(i, 2) * 210f) * easeOutCubic(p)
            ink(0xFFB2D8ED.toInt(), sin(p * PiF) * (1 - p) * 0.75f, 1.2f)
            val x = 256f + cos(angle) * radius
            val y = 256f + sin(angle) * radius
            canvas.drawLine(256f, 256f, x, y, paint)
            canvas.drawLine(x, y, x - cos(angle + 0.6f) * 20, y - sin(angle + 0.6f) * 20, paint)
        }
        val glow = sin(span(time, 180f, 1100f) * PiF).coerceAtLeast(0f)
        ink(0xFFFFFFFF.toInt(), glow * 0.7f)
        canvas.drawCircle(256f, 256f, 8f * glow, paint)
    }

    private fun focus(
        canvas: Canvas,
        time: Float,
    ) {
        val p = span(time, 120f, 1500f)
        val settle = smooth(p)
        canvas.save()
        val scale = lerp(1.32f, 1f, settle)
        canvas.scale(scale, scale, 256f, 256f)
        val fringe = sin(p * PiF) * 0.4f
        for (side in listOf(-1, 1)) {
            canvas.save()
            canvas.translate(side * 25f * (1 - settle), side * 14f * (1 - settle))
            paint.colorFilter = if (side < 0) coldFringe else warmFringe
            blurred(canvas, art.full, art.fullSoft, art.fullDiffuse, 24f * (1 - settle), fringe)
            paint.colorFilter = null
            canvas.restore()
        }
        blurred(canvas, art.full, art.fullSoft, art.fullDiffuse, 24f * (1 - settle), (p * 4).coerceAtMost(1f))
        canvas.restore()
    }

    private fun crayon(
        canvas: Canvas,
        time: Float,
    ) {
        image(canvas, art.outline, smooth(span(time, 60f, 300f)) * 0.7f)
        art.layers.forEachIndexed { index, bitmap ->
            val p = smooth(span(time, 240f + index * 320f, 1050f))
            path.reset()
            path.moveTo(-205f, -205f)
            path.lineTo(lerp(-174f, 717f, p), -205f)
            path.lineTo(lerp(-379f, 512f, p), 717f)
            path.lineTo(-410f, 717f)
            path.close()
            canvas.save()
            canvas.clipPath(path)
            image(canvas, bitmap)
            masked(canvas, bitmap) {
                ink(0xFFFFFFFF.toInt(), 0.2f, 1.8f)
                for (line in -512..512 step 6) canvas.drawLine(line.toFloat(), 0f, line + 512f, 512f, paint)
                ink(0xFF5B6773.toInt(), 0.08f, 1f)
                for (line in 0..1024 step 10) canvas.drawLine(line.toFloat(), 0f, line - 512f, 512f, paint)
                art.dots.forEach { dot ->
                    ink(0xFFFFFFFF.toInt(), dot.seed * 0.2f)
                    canvas.drawCircle(dot.x, dot.y, 0.6f + dot.seed, paint)
                }
            }
            canvas.restore()
        }
        val p = span(time, 120f, 1600f)
        if (p > 0f && p < 1f) {
            val x: Float
            val y: Float
            val color: Int
            when {
                p < 0.34f -> {
                    val s = smooth(p / 0.34f)
                    x = lerp(50f, 437f, s)
                    y = lerp(118f, 256f, s)
                    color =
                        colors[0]
                }
                p < 0.68f -> {
                    val s = smooth((p - 0.34f) / 0.34f)
                    x = lerp(437f, 226f, s)
                    y = lerp(256f, 403f, s)
                    color =
                        colors[1]
                }
                else -> {
                    val s = smooth((p - 0.68f) / 0.32f)
                    x = lerp(226f, 60f, s)
                    y = lerp(403f, 455f, s)
                    color =
                        colors[2]
                }
            }
            ink(color, (span(p, 0f, 0.06f) * (1 - span(p, 0.92f, 0.08f))))
            canvas.drawRoundRect(x - 19f, y - 6f, x + 19f, y + 6f, 4f, 4f, paint)
        }
    }

    /** Texture and highlight layers stay inside alpha, including the transparent water/fire gap. */
    private inline fun masked(
        canvas: Canvas,
        bitmap: Bitmap,
        draw: () -> Unit,
    ) {
        canvas.saveLayer(bounds, null)
        draw()
        paint.xfermode = mask
        image(canvas, bitmap)
        paint.xfermode = null
        canvas.restore()
    }

    private fun shine(
        canvas: Canvas,
        time: Float,
        start: Float,
        duration: Float,
    ) {
        val p = span(time, start, duration)
        if (p <= 0f || p >= 1f) return
        masked(canvas, art.full) {
            ink(0xFFFFFFFF.toInt(), sin(p * PiF) * 0.28f, 28f)
            val x = lerp(-220f, 660f, p)
            canvas.drawLine(x, 0f, x + 170f, 512f, paint)
        }
    }
}
