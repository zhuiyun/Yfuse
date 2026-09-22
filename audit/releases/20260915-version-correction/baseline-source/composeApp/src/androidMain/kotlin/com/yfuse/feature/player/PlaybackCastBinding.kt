package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastQueueEntry
import com.yfuse.core.cast.CastState
import com.yfuse.core2.api.YPlayer

internal suspend fun loadPlaybackCastItem(
    castManager: CastManager,
    items: List<PlayerMediaItem>,
    deviceId: String,
    index: Int,
    positionMs: Long,
): Boolean {
    val item = items.getOrNull(index) ?: return false
    // A Yfuse receiver may approve the original Dolby representation. Default/unknown
    // receivers keep the established H.264/AAC fallback and never gain a Dolby badge.
    val fallbackUrl = item.transcodeUrl.ifBlank { item.fallbackTranscodeUrl }
    return castManager.play(
        deviceId = deviceId,
        mediaUrl = item.url,
        title = item.title,
        positionMs = positionMs,
        fallbackMediaUrl = fallbackUrl,
        mediaProfile = item.castMediaProfile(),
        queue =
            items.map { queued ->
                CastQueueEntry(
                    mediaUrl = queued.url,
                    title = queued.title,
                    fallbackMediaUrl =
                        queued.transcodeUrl
                            .ifBlank { queued.fallbackTranscodeUrl }
                            .takeIf(String::isNotBlank),
                    mediaProfile = queued.castMediaProfile(),
                )
            },
        queueIndex = index,
    )
}

/** Mirror receiver queue changes without recreating the binding for position ticks. */
@Composable
internal fun BindCastQueue(
    castState: CastState,
    player: YPlayer,
    items: List<PlayerMediaItem>,
    currentIndex: Int,
) {
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestItems by rememberUpdatedState(items)
    LaunchedEffect(
        player,
        castState.sessionRevision,
        castState.currentQueueIndex,
        castState.queueSize,
        castState.hasActiveSession,
    ) {
        if (castState.hasActiveSession &&
            castState.queueSize > 1 &&
            castState.currentQueueIndex in latestItems.indices &&
            latestIndex != castState.currentQueueIndex
        ) {
            player.selectItem(castState.currentQueueIndex)
            player.pause()
        }
    }
}
