package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage

/** The parent badge supplies its dimensions, tint and clipped shape. */
@Composable
internal fun ServerBadgeArtwork(
    url: String?,
    onLoadError: (() -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    if (url == null) {
        fallback()
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
        onError = onLoadError?.let { notify -> { notify() } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
    )
}
