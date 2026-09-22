package com.yfuse.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ArtworkColorExtractionInstrumentedTest {
    @Test
    fun large_landscape_and_portrait_sources_decode_to_small_proportional_sampling_bitmaps() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val loader = ImageLoader.Builder(context).build()
            try {
                for ((width, height) in listOf(2048 to 1152, 1152 to 2048)) {
                    val source = fixture(width, height)
                    val file = File.createTempFile("artwork-color-", ".png", context.cacheDir)
                    try {
                        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        val result = loader.execute(artworkColorImageRequest(context, file.toURI().toString()))
                        assertTrue("Expected a decoded sampling bitmap", result is SuccessResult)
                        val sampled = ((result as SuccessResult).image as BitmapImage).bitmap
                        assertTrue("Sampling must not allocate an original-size software bitmap", sampled.width <= 256)
                        assertTrue(sampled.height <= 256)
                        assertTrue(sampled.config != Bitmap.Config.HARDWARE)
                        assertTrue(abs(sampled.width.toFloat() / sampled.height - width.toFloat() / height) < 0.02f)
                        for (aspect in listOf(16f / 9f, 1f, 0.7f)) {
                            assertColorNear(
                                source.weightedArtworkPageColor(aspect, 0.25f)!!,
                                sampled.weightedArtworkPageColor(aspect, 0.25f)!!,
                                tolerance = 14,
                            )
                        }
                        assertColorNear(
                            source.dominantArtworkColor()!!,
                            sampled.dominantArtworkColor()!!,
                            tolerance = 16,
                        )
                    } finally {
                        source.recycle()
                        file.delete()
                    }
                }
            } finally {
                loader.shutdown()
            }
        }

    @Test
    fun black_bars_remain_excluded_and_row_sampling_cooperates_with_cancellation() {
        val bitmap = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888)
        try {
            val color = Color.rgb(35, 100, 165)
            bitmap.eraseColor(color)
            Canvas(bitmap).drawRect(0f, 132f, 256f, 144f, Paint().apply { this.color = Color.BLACK })
            assertColorNear(color, bitmap.weightedArtworkPageColor(16f / 9f, 0.25f)!!, 1)
            var visited = 0
            var cancelled = false
            try {
                bitmap.weightedArtworkPageColor(16f / 9f, 0.25f) {
                    if (++visited == 3) throw kotlinx.coroutines.CancellationException("Hidden route")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(3, visited)
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 100, 165))
            val canvas = Canvas(this)
            val paint = Paint()
            // A smaller face-like warm patch must not win over the saturated artwork.
            paint.color = Color.rgb(200, 158, 133)
            canvas.drawRect(width * 0.42f, height * 0.1f, width * 0.58f, height * 0.28f, paint)
            paint.color = Color.BLACK
            canvas.drawRect(0f, height * 0.93f, width.toFloat(), height.toFloat(), paint)
        }

    private fun assertColorNear(
        expected: Int,
        actual: Int,
        tolerance: Int,
    ) {
        assertTrue(abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }
}
