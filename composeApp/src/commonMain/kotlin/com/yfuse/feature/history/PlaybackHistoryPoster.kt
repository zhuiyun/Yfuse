package com.yfuse.feature.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade

/** Keep the successfully decoded poster while its episode URL is enriched to a series URL. */
internal class PlaybackHistoryPosterState(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    var memoryCacheKey: MemoryCache.Key? = null
        private set
    var resolvedUrl by mutableStateOf<String?>(null)
        private set
    private var failedAt by mutableStateOf<Map<String, Long>>(emptyMap())

    fun candidate(urls: List<String?>): String? =
        urls.filterNotNull().firstOrNull { url ->
            url.isNotBlank() && failedAt[url]?.let { nowMs() - it < 30_000L } != true
        }

    fun resolved(
        url: String,
        cacheKey: MemoryCache.Key?,
    ) {
        memoryCacheKey = cacheKey ?: memoryCacheKey
        resolvedUrl = url
        if (url in failedAt) failedAt = failedAt - url
    }

    fun failed(url: String) {
        failedAt = failedAt + (url to nowMs())
    }

    fun retry() {
        failedAt = emptyMap()
    }
}

@Composable
internal fun PlaybackHistoryPoster(
    urls: List<String?>,
    state: PlaybackHistoryPosterState,
) {
    val context = LocalPlatformContext.current
    val candidate = state.candidate(urls) ?: state.resolvedUrl ?: return
    // Changing unrelated metadata or fallback URLs does not restart a successful painter.
    // A new primary URL uses the previous decoded image until its own drawable is ready.
    val request =
        remember(context, candidate) {
            ImageRequest
                .Builder(context)
                .data(candidate)
                .placeholderMemoryCacheKey(state.memoryCacheKey)
                .crossfade(false)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onSuccess = { state.resolved(candidate, it.result.memoryCacheKey) },
        onError = { state.failed(candidate) },
    )
}
