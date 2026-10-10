package com.yfuse.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.skeletonSweep
import kotlinx.coroutines.delay

/** Adds the shared loading sweep while preserving TV-specific Coil requests and transitions. */
@Composable
internal fun TvLoadingImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var loading by remember(model) { mutableStateOf(false) }
    var sweepReady by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, loading) {
        sweepReady = false
        if (loading) {
            delay(150L)
            sweepReady = true
        }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = if (loading && sweepReady) modifier.skeletonSweep() else modifier,
        onLoading = { loading = true },
        onSuccess = { loading = false },
        onError = { loading = false },
    )
}
