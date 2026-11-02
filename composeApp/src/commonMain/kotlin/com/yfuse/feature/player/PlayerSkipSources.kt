package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.TheOtherDbRepository
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.mergeOnlineSkipSegments
import org.koin.core.Koin

internal data class PlayerSkipDependencies(
    val preferences: SkipSegmentPreferences,
    val online: TheOtherDbRepository,
)

/** Resolved by the activity once; the composables receive explicit dependencies. */
internal fun Koin.playerSkipDependencies(): PlayerSkipDependencies = PlayerSkipDependencies(get(), get())

internal data class PlayerSkipSources(
    val current: PlayerMediaItem?,
    val next: PlayerMediaItem?,
    val online: OnlineSkipMarkers,
)

/** Fetch current/next metadata outside the large root layout, shared by every playback engine. */
@Composable
internal fun rememberPlayerSkipSources(
    current: PlayerMediaItem?,
    next: PlayerMediaItem?,
    durationMs: Long,
    dependencies: PlayerSkipDependencies,
    registry: ServerRegistry,
    repository: EmbyRepository,
): PlayerSkipSources {
    // Jellyfin 10.10+ uses media segments instead of chapter markers. Preserve this existing
    // path; the community provider fills only types not already supplied by the media server.
    var serverCache by remember { mutableStateOf(emptyMap<String, List<PlaybackSegment>>()) }
    val targets =
        listOfNotNull(current, next)
            .filter { it.playbackSegments.isEmpty() && it.serverId != null }
            .map { mediaSegmentKey(it.serverId, it.id) to it }
    LaunchedEffect(targets.map { it.first }) {
        targets.forEach { (key, item) ->
            if (serverCache.containsKey(key)) return@forEach
            val server =
                item.serverId
                    ?.let(registry::serverById)
                    ?.takeIf { it.kind == MediaServerKind.Jellyfin } ?: return@forEach
            serverCache = serverCache + (key to repository.mediaSegments(server, item.id).getOrDefault(emptyList()))
        }
    }
    val enabled by dependencies.preferences.onlineEnabled.collectAsState()
    val online = rememberOnlineSkipMarkers(current, durationMs, enabled, dependencies.online)
    val nextSource = next?.withMediaSegments(serverCache)
    val nextOnline =
        rememberOnlineSkipMarkers(nextSource, nextSource?.durationMsHint ?: 0L, enabled, dependencies.online)
    return PlayerSkipSources(
        current = current?.withMediaSegments(serverCache),
        next =
            nextSource?.copy(
                playbackSegments = mergeOnlineSkipSegments(nextSource.playbackSegments, nextOnline.segments),
            ),
        online = online,
    )
}
