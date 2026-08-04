package com.yfuse.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local playback selection shared by detail screens and the dedicated player task.
 *
 * It deliberately keeps only identifiers. Stream URLs and credentials remain owned by the
 * player, while resource/version/episode highlights can still follow changes made there.
 */
data class PlaybackSelectionState(
    val serverId: String? = null,
    val itemId: String? = null,
    val seriesId: String? = null,
    val versionId: String? = null,
)

object PlaybackSelection {
    private val _state = MutableStateFlow(PlaybackSelectionState())
    val state = _state.asStateFlow()

    fun update(item: PlayerMediaItem?) {
        if (item == null) return
        _state.value = PlaybackSelectionState(
            serverId = item.serverId,
            itemId = item.id,
            seriesId = item.seriesId,
            versionId = item.versionId,
        )
    }
}
