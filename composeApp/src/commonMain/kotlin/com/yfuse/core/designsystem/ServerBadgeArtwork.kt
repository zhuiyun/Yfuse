package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import com.yfuse.core.data.ServerIconCatalog
import com.yfuse.core.data.matchServerIcon
import com.yfuse.core.model.SavedServer
import org.koin.core.context.GlobalContext

@Composable
internal fun rememberMatchedServerIcon(
    server: SavedServer,
    catalog: ServerIconCatalog?,
): String? {
    if (catalog == null) return null
    val index by catalog.state.collectAsState()
    return remember(index.icons, server.serverName, server.primaryUrl) {
        matchServerIcon(index.icons, server.serverName, server.primaryUrl)?.url
    }
}

@Composable
internal fun ServerBadgeArtwork(
    server: SavedServer,
    fallback: @Composable () -> Unit,
) {
    val url =
        server.iconUrl
            ?: rememberMatchedServerIcon(server, remember { GlobalContext.getOrNull()?.getOrNull<ServerIconCatalog>() })
    ServerBadgeArtwork(url, fallback = fallback)
}

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
        contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
        onError = onLoadError?.let { notify -> { notify() } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
    )
}
