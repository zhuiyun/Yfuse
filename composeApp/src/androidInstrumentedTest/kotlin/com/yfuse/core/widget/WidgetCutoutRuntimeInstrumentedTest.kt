package com.yfuse.core.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Runs the packaged native ONNX engine, real Coil decoding and the production private cache. */
@RunWith(AndroidJUnit4::class)
class WidgetCutoutRuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val source = "file:///android_asset/widget-preview/breakout-astronaut.png"

    @Test(timeout = 180_000L)
    fun real_native_model_preserves_coordinates_reuses_cache_and_recovers_missing_files() =
        runBlocking {
            withTimeout(150_000L) {
                val directory = File(context.cacheDir, "widget-cutout")
                assertTrue("Cannot reset feature cache", !directory.exists() || directory.deleteRecursively())
                // The Store has no precomputed-preview branch. This must execute Coil + ONNX + gate.
                assertNull(WidgetCutoutStore.get(context, source, allowProcessing = false))
                val first = WidgetCutoutStore.get(context, source)
                assertNotNull("Packaged model failed its known-good original artwork", first)
                first!!
                assertEquals(512, first.sourceWidth)
                assertEquals(768, first.sourceHeight)
                assertTrue(first.subjectTop in 0.10f..0.22f)
                assertTrue(first.subjectBottom in 0.75f..0.88f)
                assertTrue(first.subjectTop < first.subjectBottom)
                val backgroundFile = imageFile(first.backgroundModel)
                val foregroundFile = imageFile(first.foregroundModel)
                assertTrue(backgroundFile.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                assertTrue(foregroundFile.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                verifyAlignedPixelsAndSaveEvidence(backgroundFile, foregroundFile, first)

                // A cache hit can touch metadata, but it must not rewrite either PNG or run inference.
                val unchangedStamp = System.currentTimeMillis() - 120_000L
                assertTrue(backgroundFile.setLastModified(unchangedStamp))
                assertTrue(foregroundFile.setLastModified(unchangedStamp))
                val storedBackgroundStamp = backgroundFile.lastModified()
                val storedForegroundStamp = foregroundFile.lastModified()
                assertEquals(first, WidgetCutoutStore.get(context, source))
                assertEquals(storedBackgroundStamp, backgroundFile.lastModified())
                assertEquals(storedForegroundStamp, foregroundFile.lastModified())

                // Retained routes must discard an evicted or corrupt pair before trying to render it.
                assertTrue(foregroundFile.delete())
                assertNull(WidgetCutoutStore.get(context, source, allowProcessing = false))
                val recovered = WidgetCutoutStore.get(context, source)
                assertNotNull(recovered)
                assertTrue(imageFile(recovered!!.foregroundModel).isFile)
                imageFile(recovered.foregroundModel).writeText("not a PNG")
                assertNull(WidgetCutoutStore.get(context, source, allowProcessing = false))
                assertNotNull(WidgetCutoutStore.get(context, source))

                // A real weak mask is rejected and the rejection is cached without extending its TTL.
                val rejectedSource = "file:///android_asset/widget-preview/rain.png"
                assertNull(WidgetCutoutStore.get(context, rejectedSource))
                val rejected = directory.listFiles().orEmpty().filter { it.extension == "reject" }
                assertEquals(1, rejected.size)
                val expiry = rejected.single().readText()
                assertNull(WidgetCutoutStore.get(context, rejectedSource))
                assertEquals(expiry, rejected.single().readText())
                assertTrue(directory.listFiles().orEmpty().sumOf(File::length) <= 32L * 1024 * 1024)
                assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
            }
        }

    @Test(timeout = 10_000L)
    fun unsupported_sources_fail_closed_without_loading_private_files() =
        runBlocking {
            for (invalid in listOf(
                "",
                "content://private/image/1",
                "file:///data/user/0/com.yfuse/files/private.png",
                "file:///android_asset/widget-preview/../widget-cutout/preview-astronaut.png",
                "file:///android_asset/widget-preview/unknown.png",
                "ftp://example.invalid/poster.png",
                "https:///missing-host.png",
            )) {
                assertNull(invalid, WidgetCutoutStore.get(context, invalid))
            }
        }

    @Test
    fun production_preprocessing_uses_model_max_pixel_normalization_and_keeps_coil_bitmap_owned() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(64, 128, 192))
            val input = prepareWidgetCutoutInput(bitmap)
            assertEquals(3 * 320 * 320, input.size)
            assertEquals((64f / 192f - .485f) / .229f, input[0], .00001f)
            assertEquals((128f / 192f - .456f) / .224f, input[320 * 320], .00001f)
            assertEquals((1f - .406f) / .225f, input[2 * 320 * 320], .00001f)
            assertTrue(input.all(Float::isFinite))
            assertFalse(bitmap.isRecycled)
            assertEquals(Color.rgb(64, 128, 192), bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageFile(model: String): File = File(requireNotNull(Uri.parse(model).path))

    private fun verifyAlignedPixelsAndSaveEvidence(
        backgroundFile: File,
        foregroundFile: File,
        artwork: WidgetCutoutArtwork,
    ) {
        val background = requireNotNull(BitmapFactory.decodeFile(backgroundFile.path))
        val foreground = requireNotNull(BitmapFactory.decodeFile(foregroundFile.path))
        try {
            assertEquals(artwork.sourceWidth, background.width)
            assertEquals(artwork.sourceHeight, background.height)
            assertEquals(background.width, foreground.width)
            assertEquals(background.height, foreground.height)
            val count = background.width * background.height
            val originalPixels = IntArray(count)
            val cutoutPixels = IntArray(count)
            background.getPixels(originalPixels, 0, background.width, 0, 0, background.width, background.height)
            foreground.getPixels(cutoutPixels, 0, foreground.width, 0, 0, foreground.width, foreground.height)
            val visibleCount = cutoutPixels.count { Color.alpha(it) >= 128 }
            assertTrue(visibleCount.toFloat() / count in 0.08f..0.30f)
            assertTrue(Color.alpha(cutoutPixels.first()) <= 2)
            assertTrue(Color.alpha(cutoutPixels.last()) <= 2)
            var opaqueSamples = 0
            for (index in cutoutPixels.indices step 11) {
                val foregroundPixel = cutoutPixels[index]
                if (Color.alpha(foregroundPixel) < 254) continue
                opaqueSamples++
                val original = originalPixels[index]
                // One unit of premultiplied-alpha rounding is expected after PNG decoding.
                assertTrue(abs(Color.red(original) - Color.red(foregroundPixel)) <= 2)
                assertTrue(abs(Color.green(original) - Color.green(foregroundPixel)) <= 2)
                assertTrue(abs(Color.blue(original) - Color.blue(foregroundPixel)) <= 2)
            }
            assertTrue("No confident foreground remained", opaqueSamples > 100)

            val evidence =
                File(
                    context.getExternalFilesDir(null),
                    "validation/widget-cutout-runtime",
                ).apply { mkdirs() }
            backgroundFile.copyTo(File(evidence, "runtime-background.png"), overwrite = true)
            foregroundFile.copyTo(File(evidence, "runtime-foreground.png"), overwrite = true)
            val onGray = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(onGray).apply {
                    drawColor(Color.GRAY)
                    drawBitmap(foreground, 0f, 0f, null)
                }
                File(evidence, "runtime-cutout-on-gray.png").outputStream().use {
                    assertTrue(onGray.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                onGray.recycle()
            }
        } finally {
            background.recycle()
            foreground.recycle()
        }
    }
}
