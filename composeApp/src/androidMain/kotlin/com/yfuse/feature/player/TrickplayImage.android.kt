package com.yfuse.feature.player

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

/** Uses Coil's existing authenticated fetch/disk cache, decoding only the requested sprite cell. */
@Composable
internal actual fun TrickplayImage(
    storyboard: TrickplayStoryboard,
    frame: TrickplayFrame,
    description: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var lastFrameKey by remember(storyboard) { mutableStateOf<MemoryCache.Key?>(null) }
    val moving =
        LocalRouteVisible.current &&
            !LocalAccessibilityOptions.current.reduceMotion &&
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
    val width = with(LocalDensity.current) { 152.dp.roundToPx() }.coerceAtLeast(1)
    val height =
        (
            width.toLong() *
                storyboard.height.coerceAtLeast(
                    1,
                ) / storyboard.width.coerceAtLeast(1)
        ).coerceIn(1, 2048).toInt()
    val columns = if (storyboard.frames.isEmpty()) storyboard.tileColumns else 1
    val rows = if (storyboard.frames.isEmpty()) storyboard.tileRows else 1
    val request =
        remember(context, frame, columns, rows, width, height, moving) {
            ImageRequest
                .Builder(context)
                .data(frame.url)
                .placeholderMemoryCacheKey(lastFrameKey)
                .crossfade(if (moving) Motion.QUICK else 0)
                .size(width, height)
                .memoryCacheKeyExtra("trickplay-region-v1", "$columns,$rows,${frame.column},${frame.row}")
                .decoderFactory { result, _, _ ->
                    Decoder {
                        @Suppress("DEPRECATION")
                        val decoder = BitmapRegionDecoder.newInstance(result.source.source().inputStream(), false)
                        requireNotNull(decoder) { "Unsupported trickplay image" }
                        try {
                            val region =
                                trickplayRegion(decoder.width, decoder.height, columns, rows, frame.column, frame.row)
                            var sample = 1
                            while ((region.right - region.left) / (sample * 2) >= width &&
                                (region.bottom - region.top) / (sample * 2) >= height
                            ) {
                                sample *= 2
                            }
                            val bitmap =
                                requireNotNull(
                                    decoder.decodeRegion(
                                        Rect(region.left, region.top, region.right, region.bottom),
                                        BitmapFactory.Options().apply { inSampleSize = sample },
                                    ),
                                ) { "Trickplay region could not be decoded" }
                            DecodeResult(bitmap.asImage(), isSampled = sample > 1)
                        } finally {
                            decoder.recycle()
                        }
                    }
                }.build()
        }
    AsyncImage(
        model = request,
        contentDescription = description,
        onSuccess = { lastFrameKey = it.result.memoryCacheKey },
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
    )
}
