package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
