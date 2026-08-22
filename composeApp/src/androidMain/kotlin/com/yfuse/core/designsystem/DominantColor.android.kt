package com.yfuse.core.designsystem

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@Composable
actual fun rememberDominantColor(
    url: String?,
    fallback: Color,
): Color {
    val context = LocalContext.current
    // Detail routes intentionally share one SaveableStateProvider key. A saveable value at this
    // slot can therefore restore the previous title's colour before [url] is evaluated. Keep the
    // result with this live image request instead; the retained route still preserves it on back.
    var colorArgb by remember(url) { mutableIntStateOf(fallback.toArgb()) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val extracted =
            withContext(Dispatchers.IO) {
                runCatching {
                    // Hardware bitmaps cannot be read back by Palette.
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .allowHardware(false)
                            .build()
                    val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                    val bitmap = (image as? BitmapImage)?.bitmap ?: return@runCatching null
                    val palette =
                        Palette
                            .from(bitmap)
                            // Keep Palette's default black/white/red-I-line filters. clearFilters()
                            // made subtitles, letterboxing and faces win over the artwork colour.
                            .maximumColorCount(24)
                            .generate()
                    val largestPopulation =
                        palette.swatches
                            .maxOfOrNull { it.population }
                            ?.coerceAtLeast(1)
                            ?: 1
                    palette.swatches
                        .asSequence()
                        .filter { swatch ->
                            val hsl = swatch.hsl
                            hsl[1] >= 0.20f && hsl[2] in 0.16f..0.82f
                        }.maxByOrNull { swatch ->
                            val hsl = swatch.hsl
                            val population = swatch.population.toFloat() / largestPopulation
                            val usefulLightness = 1f - kotlin.math.abs(hsl[2] - 0.52f)
                            hsl[1] * 0.55f + population * 0.30f + usefulLightness * 0.15f
                        }?.rgb
                        ?: palette.vibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                }.getOrNull()
            }
        if (extracted != null) colorArgb = extracted
    }

    return Color(colorArgb)
}

@Composable
actual fun rememberArtworkPageColor(
    url: String?,
    targetAspectRatio: Float,
    fadeFraction: Float,
): Color? {
    val context = LocalContext.current
    var colorArgb by remember(url, targetAspectRatio, fadeFraction) { mutableStateOf<Int?>(null) }

    LaunchedEffect(url, targetAspectRatio, fadeFraction) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val extracted =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .allowHardware(false)
                            .build()
                    val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                    val bitmap = (image as? BitmapImage)?.bitmap ?: return@runCatching null
                    bitmap.weightedArtworkPageColor(targetAspectRatio, fadeFraction)
                }.getOrNull()
            }
        if (extracted != null) colorArgb = extracted
    }

    return colorArgb?.let { Color(it) }
}

private data class BitmapCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Reproduces the centred source rectangle used by `ContentScale.Crop`. */
private fun Bitmap.centerCrop(targetAspectRatio: Float): BitmapCrop {
    val sourceAspectRatio = width.toFloat() / height.toFloat()
    val safeTargetAspectRatio =
        targetAspectRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: sourceAspectRatio
    return if (sourceAspectRatio > safeTargetAspectRatio) {
        val visibleWidth = height * safeTargetAspectRatio
        val left = (width - visibleWidth) / 2f
        BitmapCrop(left, 0f, left + visibleWidth, height.toFloat())
    } else {
        val visibleHeight = width / safeTargetAspectRatio
        val top = (height - visibleHeight) / 2f
        BitmapCrop(0f, top, width.toFloat(), top + visibleHeight)
    }
}

/**
 * Fits one opaque page colour to the exact visible hero fade in linear sRGB.
 *
 * Sampling a small grid keeps this cheap even for a 4K backdrop. Baked-in black letterbox rows
 * are ignored only when almost the entire row is neutral black; varied dark artwork remains
 * valid source material. If the whole region really is black, a second pass retains it.
 */
private fun Bitmap.weightedArtworkPageColor(
    targetAspectRatio: Float,
    fadeFraction: Float,
): Int? {
    if (width <= 0 || height <= 0) return null
    val crop = centerCrop(targetAspectRatio)
    val visibleHeight = crop.bottom - crop.top
    val safeFadeFraction = fadeFraction.takeIf(Float::isFinite)?.coerceIn(0.02f, 1f) ?: 0.25f
    val fadeTop = crop.bottom - visibleHeight * safeFadeFraction

    return sampleArtworkFade(crop, fadeTop, skipLetterboxRows = true)
        ?: sampleArtworkFade(crop, fadeTop, skipLetterboxRows = false)
}

private fun Bitmap.sampleArtworkFade(
    crop: BitmapCrop,
    fadeTop: Float,
    skipLetterboxRows: Boolean,
): Int? {
    val sampleColumns = minOf(72, (crop.right - crop.left).roundToInt().coerceAtLeast(1))
    val sampleRows = minOf(48, (crop.bottom - fadeTop).roundToInt().coerceAtLeast(1))
    var redLinear = 0.0
    var greenLinear = 0.0
    var blueLinear = 0.0
    var totalWeight = 0.0

    repeat(sampleRows) { row ->
        val fadeProgress = (row + 0.5f) / sampleRows
        val y =
            (fadeTop + (crop.bottom - fadeTop) * fadeProgress)
                .toInt()
                .coerceIn(0, height - 1)
        val pixels = IntArray(sampleColumns)
        var opaquePixels = 0
        var neutralBlackPixels = 0

        repeat(sampleColumns) { column ->
            val horizontalProgress = (column + 0.5f) / sampleColumns
            val x =
                (crop.left + (crop.right - crop.left) * horizontalProgress)
                    .toInt()
                    .coerceIn(0, width - 1)
            val pixel = getPixel(x, y)
            pixels[column] = pixel
            if (AndroidColor.alpha(pixel) >= 128) {
                opaquePixels++
                val red = AndroidColor.red(pixel)
                val green = AndroidColor.green(pixel)
                val blue = AndroidColor.blue(pixel)
                if (maxOf(red, green, blue) <= 10 && maxOf(red, green, blue) - minOf(red, green, blue) <= 3) {
                    neutralBlackPixels++
                }
            }
        }

        val looksLikeLetterbox =
            skipLetterboxRows &&
                opaquePixels > 0 &&
                neutralBlackPixels.toFloat() / opaquePixels >= 0.88f
        if (!looksLikeLetterbox) {
            val rowWeight = artworkPageSampleWeight(fadeProgress).toDouble()
            pixels.forEach { pixel ->
                val alpha = AndroidColor.alpha(pixel) / 255.0
                if (alpha >= 0.5) {
                    val weight = rowWeight * alpha
                    redLinear += AndroidColor.red(pixel).srgbToLinear() * weight
                    greenLinear += AndroidColor.green(pixel).srgbToLinear() * weight
                    blueLinear += AndroidColor.blue(pixel).srgbToLinear() * weight
                    totalWeight += weight
                }
            }
        }
    }

    if (totalWeight <= 0.0) return null
    return AndroidColor.rgb(
        (redLinear / totalWeight).linearToSrgb(),
        (greenLinear / totalWeight).linearToSrgb(),
        (blueLinear / totalWeight).linearToSrgb(),
    )
}

private fun Int.srgbToLinear(): Double {
    val encoded = this / 255.0
    return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
}

private fun Double.linearToSrgb(): Int {
    val linear = coerceIn(0.0, 1.0)
    val encoded = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1.0 / 2.4) - 0.055
    return (encoded * 255.0).roundToInt().coerceIn(0, 255)
}
