package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.graphics.PathMeasure
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/** Uses Android's actual Canvas clip implementation and the production content-draw path. */
@RunWith(AndroidJUnit4::class)
class CuriousDialogMaskInstrumentedTest {
    @Test
    fun masks_draw_content_once_and_end_as_the_exact_unmasked_pattern_for_all_panel_shapes() {
        for ((width, height) in listOf(320 to 480, 480 to 220, 1 to 1, 3 to 1)) {
            val reference = render(DialogAnimation.Envelope, 1f, width, height)
            try {
                val expected = reference.bitmap.pixels()
                for (animation in curiousDialogAnimations) {
                    var previousCoverage = 0L
                    for (progress in listOf(0f, 0.0001f, 0.25f, 0.55f, 0.845f, 0.88f, 0.92f, 1f)) {
                        val rendered = render(animation, progress, width, height)
                        try {
                            val pixels = rendered.bitmap.pixels()
                            assertEquals("$animation at $progress", if (progress <= 0f) 0 else 1, rendered.contentDraws)
                            val coverage = pixels.sumOf { (it ushr 24).toLong() }
                            assertTrue("$animation shrank its reveal at $progress", coverage >= previousCoverage)
                            previousCoverage = coverage
                            if (progress == 0f) assertEquals(0L, coverage)
                            if (progress >= 0.88f) {
                                assertArrayEquals(
                                    "$animation retained a seam or altered content at $progress",
                                    expected,
                                    pixels,
                                )
                            }
                        } finally {
                            rendered.bitmap.recycle()
                        }
                    }
                }
            } finally {
                reference.bitmap.recycle()
            }
        }
    }

    @Test
    fun pinwheel_first_frame_is_tiny_and_constellation_does_not_skip_unfinished_corner_cuts() {
        val pinwheel = render(DialogAnimation.Pinwheel, 0.001f, 320, 240)
        val constellation = render(DialogAnimation.Constellation, 0.845f, 320, 240)
        try {
            val visible = pinwheel.bitmap.pixels().count { it ushr 24 > 0 }
            assertTrue("Pinwheel jumped to a large silhouette: $visible pixels", visible <= 16)
            assertTrue("The corner cut was bypassed too early", constellation.bitmap.getPixel(0, 0) ushr 24 < 255)
            assertEquals(255, constellation.bitmap.getPixel(160, 120) ushr 24)
        } finally {
            pinwheel.bitmap.recycle()
            constellation.bitmap.recycle()
        }
    }

    @Test
    fun puzzle_and_pinwheel_contours_share_nonzero_winding_so_their_parts_cannot_cancel() {
        for (animation in listOf(DialogAnimation.PuzzleLock, DialogAnimation.Pinwheel)) {
            for ((width, height) in listOf(320f to 480f, 480f to 220f, 1f to 1f)) {
                val path = Path()
                path.curiousMask(animation, curiousDialogGeometry(animation, width, height, 0.55f))
                assertEquals(PathFillType.NonZero, path.fillType)
                val measure = PathMeasure(path.asAndroidPath(), true)
                var contours = 0
                do {
                    val first = FloatArray(2)
                    val previous = FloatArray(2)
                    val current = FloatArray(2)
                    assertTrue(measure.getPosTan(0f, first, null))
                    first.copyInto(previous)
                    var twiceArea = 0.0
                    for (step in 1..128) {
                        assertTrue(measure.getPosTan(measure.length * step / 128f, current, null))
                        twiceArea += previous[0].toDouble() * current[1] - current[0].toDouble() * previous[1]
                        current.copyInto(previous)
                    }
                    twiceArea += previous[0].toDouble() * first[1] - first[0].toDouble() * previous[1]
                    assertTrue("$animation contour $contours reversed its winding", twiceArea > 0.0)
                    contours++
                } while (measure.nextContour())
                assertEquals(if (animation == DialogAnimation.PuzzleLock) 2 else 4, contours)
            }
        }
    }

    private fun render(
        animation: DialogAnimation,
        progress: Float,
        width: Int,
        height: Int,
    ): Rendered {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawScope = CanvasDrawScope()
        var contentDraws = 0
        val content =
            object : ContentDrawScope, DrawScope by drawScope {
                override fun drawContent() {
                    contentDraws++
                    drawRect(Color(0xFF316599))
                    drawRect(Color.White, Offset(size.width * 0.2f, 0f), Size(max(1f, size.width * 0.13f), size.height))
                    drawRect(
                        Color.Black,
                        Offset(0f, size.height * 0.65f),
                        Size(size.width, max(1f, size.height * 0.15f)),
                    )
                }
            }
        drawScope.draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(width.toFloat(), height.toFloat()),
        ) {
            content.drawCuriousDialog(animation, progress, Color.Transparent, DialogDrawCache(Color.Transparent))
        }
        return Rendered(bitmap, contentDraws)
    }

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private data class Rendered(
        val bitmap: Bitmap,
        val contentDraws: Int,
    )
}
