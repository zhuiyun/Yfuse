package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyStream

/**
 * The current item's trickplay storyboard: the one it carries, or else one loaded from its server
 * the first time it plays and kept, per item and file, for the life of the player. Null until one
 * is known.
 */
@Composable
internal fun rememberCurrentTrickplay(
    currentItem: PlayerMediaItem?,
    remoteSubtitleRepository: EmbyRepository,
    remoteSubtitleRegistry: ServerRegistry,
): TrickplayStoryboard? {
    var trickplayCache by remember {
        mutableStateOf(emptyMap<TrickplayCacheKey, TrickplayStoryboard?>())
    }
    val trickplayKey =
        currentItem?.let { item ->
            val serverId = item.serverId ?: return@let null
            TrickplayCacheKey(
                serverId = serverId,
                itemId = item.id,
                mediaSourceId = item.activeVersion?.id ?: item.versionId ?: item.id,
            )
        }
    LaunchedEffect(trickplayKey, currentItem?.trickplay) {
        val key = trickplayKey ?: return@LaunchedEffect
        val item = currentItem
        if (item.trickplay != null || trickplayCache.containsKey(key)) return@LaunchedEffect
        val server = remoteSubtitleRegistry.serverById(key.serverId) ?: return@LaunchedEffect
        remoteSubtitleRepository
            .trickplayInfo(server, key.itemId, key.mediaSourceId)
            .onSuccess { info ->
                val storyboard =
                    info?.let {
                        TrickplayStoryboard(
                            urlPattern =
                                it.urlPattern
                                    ?: it.frames.firstOrNull()?.url
                                    ?: EmbyStream.trickplayTilePattern(
                                        baseUrl = server.baseUrl,
                                        itemId = key.itemId,
                                        mediaSourceId = key.mediaSourceId,
                                        width = it.width,
                                        token = server.accessToken,
                                    ),
                            width = it.width,
                            height = it.height,
                            tileColumns = it.tileColumns,
                            tileRows = it.tileRows,
                            intervalMs = it.intervalMs,
                            thumbnailCount = it.thumbnailCount,
                            urlIndexMultiplier = it.urlIndexMultiplier,
                            frames =
                                it.frames.map { frame ->
                                    TrickplayStoryboardFrame(frame.positionMs, frame.url)
                                },
                        )
                    }
                trickplayCache = trickplayCache.withTrickplayResult(key, storyboard)
            }.onFailure { failure ->
                AppLog.warning(
                    category = "player.trickplay",
                    event = "lazy_load_failed",
                    message = "Current episode storyboard could not be loaded",
                    throwable = failure,
                    attributes = mapOf("itemId" to key.itemId),
                )
            }
    }
    return currentItem?.trickplay ?: trickplayKey?.let(trickplayCache::get)
}
