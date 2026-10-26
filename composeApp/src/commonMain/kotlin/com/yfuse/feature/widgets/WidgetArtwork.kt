package com.yfuse.feature.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Main-thread URL identities only: decoded bitmaps remain owned and bounded by Coil. */
internal class WidgetArtworkKeys(
    private val capacity: Int = 256,
) {
    private val keys = LinkedHashMap<String, MemoryCache.Key>()

    operator fun get(url: String): MemoryCache.Key? = keys.remove(url)?.also { keys[url] = it }

    fun resolved(
        url: String,
        key: MemoryCache.Key?,
    ) {
        if (key == null) return
        keys.remove(url)
        keys[url] = key
        if (keys.size > capacity) keys.remove(keys.keys.first())
    }
}

private val artworkKeys = WidgetArtworkKeys()

/** Reuse the last decoded size while Coil resolves the new layout's requested size. */
@Composable
internal fun WidgetArtworkImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {},
    onError: (AsyncImagePainter.State.Error) -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val request =
        remember(context, model) {
            ImageRequest
                .Builder(context)
                .data(model)
                // Full URL identity keeps different accounts and changed URLs isolated.
                // An evicted/cleared Coil entry simply falls through to its normal disk/network load.
                .placeholderMemoryCacheKey(artworkKeys[model])
                .crossfade(false)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        onSuccess = {
            artworkKeys.resolved(model, it.result.memoryCacheKey)
            onSuccess(it)
        },
        onError = onError,
    )
}
