package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.UserDataDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackStateRecord
import com.yfuse.core.sync.playback.PlaybackSyncStore

/**
 * The single boundary between server metadata and progress shown by the app.
 *
 * Server UserData is never a live progress source for screens. A startup pull may seed records
 * which do not exist locally; after that, watched state, percentage and resume position always
 * come from [localStore]. Favorites remain server-owned.
 */
class PlaybackProgressProjection(
    private val localStore: PlaybackSyncStore? = null,
    private val progressSyncEnabled: () -> Boolean = { true },
) {
    val localOnly: Boolean get() = !progressSyncEnabled()

    fun project(
        server: SavedServer,
        item: BaseItemDto,
    ): BaseItemDto {
        val state = stateFor(server, item)
        val favorite = item.UserData?.IsFavorite
        val positionMs = state?.positionMs?.coerceAtLeast(0L) ?: 0L
        val durationMs =
            (state?.durationMs ?: 0L)
                .takeIf { it > 0L }
                ?: item.RunTimeTicks?.div(TICKS_PER_MILLISECOND)?.takeIf { it > 0L }
                ?: 0L
        val percentage =
            if (state != null && !state.played && positionMs > 0L && durationMs > 0L) {
                (positionMs.toDouble() * 100.0 / durationMs.toDouble()).coerceIn(0.0, 100.0)
            } else {
                null
            }
        return item.copy(
            UserData =
                UserDataDto(
                    PlayedPercentage = percentage,
                    PlaybackPositionTicks =
                        positionMs
                            .takeIf { state != null && !state.played && it > 0L }
                            ?.let(::millisecondsToTicks),
                    LastPlayedDate = null,
                    Played = state?.played == true,
                    IsFavorite = favorite,
                ),
        )
    }

    fun stateFor(
        server: SavedServer,
        item: BaseItemDto,
    ): PlaybackStateRecord? {
        val store = localStore ?: return null
        // Progress is local to this exact server item: portable provider aliases must not
        // silently copy server A's progress onto server B during normal browsing.
        return store.stateForServerItem(server.id, item.Id)
    }

    fun localStates(server: SavedServer): List<PlaybackStateRecord> =
        localStore?.statesForServer(server.id).orEmpty()

    private fun millisecondsToTicks(value: Long): Long =
        if (value > Long.MAX_VALUE / TICKS_PER_MILLISECOND) Long.MAX_VALUE else value * TICKS_PER_MILLISECOND

    private companion object {
        const val TICKS_PER_MILLISECOND = 10_000L
    }
}
