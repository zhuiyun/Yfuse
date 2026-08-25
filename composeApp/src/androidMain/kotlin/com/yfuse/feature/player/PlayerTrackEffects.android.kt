package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType

/** Applies track restoration and per-engine A/V tuning for the current playback session. */
@Composable
internal fun PlayerTrackEffects(
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    engineKind: PlayerEngine,
    state: PlaybackState,
    currentItemId: String?,
    handoverItemId: String?,
    requestedSpeed: Float,
    audioRestore: TrackRestorePreference?,
    subtitleRestore: TrackRestorePreference?,
    secondarySubtitleRestore: TrackRestorePreference?,
    restoreSubtitlesOff: Boolean,
    subtitleControls: SubtitleControlState,
    audioControls: AudioControlState,
    handoverSnapshot: PlaybackHandoverSnapshot,
    scaleMode: VideoScaleMode,
    pendingSubtitleLanguage: String?,
    automaticEngineSelection: Boolean,
    onSecondarySubtitleTrackChanged: (String?) -> Unit,
    onPendingSubtitleLanguageApplied: () -> Unit,
    onRequestMpv: () -> Unit,
) {
    LaunchedEffect(player, requestedSpeed) {
        if (state.speed != requestedSpeed) player.setSpeed(requestedSpeed)
    }
    LaunchedEffect(player, currentItemId, state.audioTracks, audioRestore) {
        if (currentItemId != handoverItemId) return@LaunchedEffect
        val target = audioRestore?.let(state.audioTracks::bestRestoreMatch) ?: return@LaunchedEffect
        if (!target.selected) player.selectTrack(YTrackType.Audio, target.id)
    }
    LaunchedEffect(
        player,
        currentItemId,
        state.subtitleTracks,
        subtitleRestore,
        restoreSubtitlesOff,
    ) {
        if (currentItemId != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (restoreSubtitlesOff) {
            if (state.subtitleTracks.any { it.selected }) {
                player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
            }
            return@LaunchedEffect
        }
        val target =
            subtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
                ?: return@LaunchedEffect
        if (!target.selected) player.selectTrack(YTrackType.Subtitle, target.id)
    }
    LaunchedEffect(
        backendExtensions,
        currentItemId,
        state.subtitleTracks,
        secondarySubtitleRestore,
        backendExtensions.supportsSecondarySubtitleTrack,
    ) {
        if (currentItemId != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (!backendExtensions.supportsSecondarySubtitleTrack) {
            onSecondarySubtitleTrackChanged(null)
            return@LaunchedEffect
        }
        val target = secondarySubtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
        if (target == null || target.selected) {
            backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
            onSecondarySubtitleTrackChanged(null)
            return@LaunchedEffect
        }
        if (backendExtensions.selectSecondarySubtitleTrack(target.id)) {
            onSecondarySubtitleTrackChanged(target.id)
        }
    }

    LaunchedEffect(backendExtensions, engineKind, subtitleControls.offsetMs) {
        val applied = backendExtensions.setSubtitleOffsetMs(subtitleControls.offsetMs)
        if (!applied && subtitleControls.offsetMs != 0L) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(backendExtensions, engineKind, audioControls.delayMs) {
        val applied = backendExtensions.setAudioDelayMs(audioControls.delayMs)
        if (!applied && audioControls.delayMs != 0L) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(backendExtensions, engineKind, subtitleControls.scale) {
        if (engineKind != PlayerEngine.Exo) {
            val applied = backendExtensions.setSubtitleScale(subtitleControls.scale)
            if (!applied && subtitleControls.scale != 1f) {
                requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
            }
        }
    }
    LaunchedEffect(backendExtensions, engineKind, subtitleControls.brightness) {
        val applied = backendExtensions.setSubtitleBrightness(subtitleControls.brightness)
        if (!applied && subtitleControls.brightness != 1f) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(backendExtensions, engineKind, subtitleControls.position) {
        if (engineKind != PlayerEngine.Exo) {
            val applied = backendExtensions.setSubtitlePosition(subtitleControls.position)
            if (!applied && subtitleControls.position != DEFAULT_SUBTITLE_POSITION) {
                requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
            }
        }
    }
    LaunchedEffect(backendExtensions, scaleMode) {
        backendExtensions.setVideoScaleMode(scaleMode)
    }
    LaunchedEffect(
        backendExtensions,
        currentItemId,
        state.discNavigation.kind,
        state.discNavigation.effectiveTitleCount,
        handoverSnapshot.discTitleIndex,
    ) {
        val title = handoverSnapshot.discTitleIndex ?: return@LaunchedEffect
        val navigation = state.discNavigation
        if (navigation.effectiveTitleCount > title && navigation.selectedTitleIndex != title) {
            backendExtensions.selectDiscTitle(title)
        }
    }
    LaunchedEffect(
        backendExtensions,
        currentItemId,
        state.discNavigation.selectedTitleIndex,
        state.discNavigation.effectiveChapterCount,
        handoverSnapshot.discTitleIndex,
        handoverSnapshot.discChapterIndex,
    ) {
        val title = handoverSnapshot.discTitleIndex
        val chapter = handoverSnapshot.discChapterIndex ?: return@LaunchedEffect
        val navigation = state.discNavigation
        if (title != null && navigation.selectedTitleIndex != title) return@LaunchedEffect
        if (navigation.effectiveChapterCount > chapter && navigation.selectedChapterIndex != chapter) {
            backendExtensions.selectDiscChapter(chapter)
        }
    }
    LaunchedEffect(player, state.subtitleTracks, pendingSubtitleLanguage) {
        val language = pendingSubtitleLanguage ?: return@LaunchedEffect
        state.subtitleTracks.matchingLanguage(language)?.let { trackId ->
            player.selectTrack(YTrackType.Subtitle, trackId)
            onPendingSubtitleLanguageApplied()
        }
    }
}

private fun requestMpvIfAllowed(
    engineKind: PlayerEngine,
    automaticEngineSelection: Boolean,
    onRequestMpv: () -> Unit,
) {
    if (engineKind != PlayerEngine.Mpv && automaticEngineSelection) onRequestMpv()
}
