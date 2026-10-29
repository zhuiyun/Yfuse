package com.yfuse.core.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile
import kotlin.math.abs

/** Verifies the actual bundled preview and that the app no longer ships local inference. */
@RunWith(AndroidJUnit4::class)
class WidgetCutoutRuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val source = "file:///android_asset/widget-preview/breakout-astronaut.png"

    @Test(timeout = 10_000L)
    fun precomputed_preview_is_available_without_processing_and_keeps_coordinates() =
        runBlocking {
            val artwork = WidgetCutoutStore.get(context, source, allowProcessing = false)
            assertNotNull("The bundled preview must not require inference or a Poof key", artwork)
            artwork!!
            assertEquals(source, artwork.backgroundModel)
            assertEquals("file:///android_asset/widget-cutout/preview-astronaut.png", artwork.foregroundModel)
            assertEquals(512, artwork.sourceWidth)
            assertEquals(768, artwork.sourceHeight)
            assertTrue(artwork.subjectTop in 0.10f..0.22f)
            assertTrue(artwork.subjectBottom in 0.75f..0.88f)
            assertEquals(artwork, WidgetCutoutStore.get(context, source, allowProcessing = true))
            verifyAlignedPreviewPixels(artwork)
        }

    @Test(timeout = 10_000L)
    fun bundled_posters_without_a_foreground_preserve_the_ordinary_poster() =
        runBlocking {
            for (poster in listOf("space.png", "rain.png")) {
                val source = "file:///android_asset/widget-preview/$poster"
                assertNull(WidgetCutoutStore.get(context, source, allowProcessing = false))
                assertNull(WidgetCutoutStore.get(context, source, allowProcessing = true))
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
    fun installed_app_contains_neither_onnx_runtime_nor_the_u2netp_model() {
        val app = context.applicationInfo
        for (apk in listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()) {
            ZipFile(apk).use { archive ->
                val forbidden =
                    archive
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .filter { name ->
                            name == "assets/widget-cutout/u2netp.onnx" ||
                                (name.startsWith("lib/") && name.substringAfterLast('/').startsWith("libonnxruntime"))
                        }.toList()
                assertTrue("Local inference files remain in $apk: $forbidden", forbidden.isEmpty())
            }
        }
    }

    private fun readPreview(model: String): Bitmap {
        val prefix = "file:///android_asset/"
        require(model.startsWith(prefix))
        return context.assets.open(model.removePrefix(prefix)).use { requireNotNull(BitmapFactory.decodeStream(it)) }
    }

    private fun verifyAlignedPreviewPixels(artwork: WidgetCutoutArtwork) {
        val background = readPreview(artwork.backgroundModel)
        val foreground = readPreview(artwork.foregroundModel)
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
        } finally {
            background.recycle()
            foreground.recycle()
        }
    }
}
