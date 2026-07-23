package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
actual fun rememberDominantColor(url: String?, fallback: Color): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(fallback) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                // Hardware bitmaps cannot be read back by Palette.
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                val bitmap = (image as? BitmapImage)?.bitmap ?: return@runCatching null
                val palette = Palette.from(bitmap).clearFilters().maximumColorCount(16).generate()
                palette.vibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
            }.getOrNull()
        }
        if (extracted != null) color = Color(extracted)
    }

    return color
}
