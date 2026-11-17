package com.yfuse.feature.player

import java.io.IOException

/** Some original runtimes reject a null output. Retire that session before recreating its surface. */
internal fun detachSenPlayerSurface(
    pause: () -> Unit,
    detach: () -> Unit,
    close: () -> Unit,
): Boolean {
    pause()
    return !bindSenPlayerSurface(detach, close)
}

/** A subtitle output may accept detach but reject reuse. Rebuild only for that exact error. */
internal fun bindSenPlayerSurface(
    bind: () -> Unit,
    close: () -> Unit,
): Boolean {
    try {
        bind()
        return true
    } catch (error: IOException) {
        if (error.message != "SenPlayer command failed (-6)") throw error
        close()
        return false
    }
}

/** Track IDs remain source-local: this snapshot must never be applied to another queue item. */
internal data class SenPlayerSurfaceTracks(
    val itemId: String,
    val audioId: String?,
    val subtitles: Map<Int, String>,
)

internal fun senPlayerSurfaceTracks(
    itemId: String,
    state: PlaybackState,
    requestedSubtitles: Map<Int, String>,
    requestedAudioId: String? = null,
): SenPlayerSurfaceTracks =
    SenPlayerSurfaceTracks(
        itemId,
        requestedAudioId ?: state.audioTracks.firstOrNull { it.selected }?.id,
        mapOf(
            SenPlayerSubtitles.PRIMARY to (state.subtitleTracks.firstOrNull { it.selected }?.id ?: EngineTrack.OFF),
            SenPlayerSubtitles.SECONDARY to (state.secondarySubtitleTrackId ?: EngineTrack.OFF),
        ) + requestedSubtitles,
    )
