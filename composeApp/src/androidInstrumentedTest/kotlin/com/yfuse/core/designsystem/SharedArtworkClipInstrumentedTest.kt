package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedArtworkClipInstrumentedTest {
    @Test
    fun return_clip_rounds_continuously_and_lands_on_the_exact_source_pixels() {
        for (source in listOf(AppShapes.thumb, AppShapes.card, AppShapes.sheet, RoundedCornerShape(18.dp))) {
            for (density in listOf(Density(1f), Density(2f))) {
                var progress = 0f
                val clip = SharedArtworkClipShape(source) { progress }
                var previousCoverage = Int.MAX_VALUE
                for (amount in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.2f)) {
                    progress = amount
                    val pixels = render(clip, density)
                    val coverage = pixels.count { it != 0 }
                    assertTrue("The corner reverted to square at $amount", coverage <= previousCoverage)
                    previousCoverage = coverage
                    if (amount >= 1f) {
                        assertArrayEquals("Landing changed the source contour", render(source, density), pixels)
                        assertTrue("The landed picture retained a square corner", pixels.first() == 0)
                    }
                }
            }
        }
    }

    @Test
    fun the_same_frame_reuses_its_outline_and_a_new_frame_updates_it() {
        var progress = 0.5f
        val clip = SharedArtworkClipShape(AppShapes.card) { progress }
        val size = Size(120f, 180f)
        val density = Density(1f)
        val first = clip.createOutline(size, LayoutDirection.Ltr, density)
        assertSame(first, clip.createOutline(size, LayoutDirection.Ltr, density))
        progress = 1f
        val rounded = clip.createOutline(size, LayoutDirection.Ltr, density)
        assertNotSame(first, rounded)
        assertNotSame(rounded, clip.createOutline(Size(240f, 360f), LayoutDirection.Ltr, density))
    }

    private fun render(
        shape: Shape,
        density: Density,
    ): IntArray {
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            when (val outline = shape.createOutline(Size(120f, 180f), LayoutDirection.Ltr, density)) {
                is Outline.Rectangle ->
                    canvas.clipRect(
                        outline.rect.left,
                        outline.rect.top,
                        outline.rect.right,
                        outline.rect.bottom,
                    )
                is Outline.Generic -> canvas.clipPath(outline.path.asAndroidPath())
                is Outline.Rounded -> {
                    val rect = outline.roundRect
                    val path = Path()
                    path.addRoundRect(
                        rect.left,
                        rect.top,
                        rect.right,
                        rect.bottom,
                        floatArrayOf(
                            rect.topLeftCornerRadius.x,
                            rect.topLeftCornerRadius.y,
                            rect.topRightCornerRadius.x,
                            rect.topRightCornerRadius.y,
                            rect.bottomRightCornerRadius.x,
                            rect.bottomRightCornerRadius.y,
                            rect.bottomLeftCornerRadius.x,
                            rect.bottomLeftCornerRadius.y,
                        ),
                        Path.Direction.CW,
                    )
                    canvas.clipPath(path)
                }
            }
            canvas.drawColor(Color.WHITE)
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
