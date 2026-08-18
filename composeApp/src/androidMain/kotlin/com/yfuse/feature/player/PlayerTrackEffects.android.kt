package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.yfuse.core.model.PlayerEngine

/** Applies track restoration and per-engine A/V tuning for the current playback session. */
@Composable
internal fun PlayerTrackEffects(
    engine: VideoEngine,
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
    scaleMode: VideoScaleMode,
    pendingSubtitleLanguage: String?,
    automaticEngineSelection: Boolean,
    onSecondarySubtitleTrackChanged: (String?) -> Unit,
    onPendingSubtitleLanguageApplied: () -> Unit,
    onRequestMpv: () -> Unit,
) {
    LaunchedEffect(engine, requestedSpeed) {
        if (state.speed != requestedSpeed) engine.setSpeed(requestedSpeed)
    }
    LaunchedEffect(engine, currentItemId, state.audioTracks, audioRestore) {
        if (currentItemId != handoverItemId) return@LaunchedEffect
        val target = audioRestore?.let(state.audioTracks::bestRestoreMatch) ?: return@LaunchedEffect
        if (!target.selected) engine.selectAudioTrack(target.id)
    }
    LaunchedEffect(
        engine,
        currentItemId,
        state.subtitleTracks,
        subtitleRestore,
        restoreSubtitlesOff,
    ) {
        if (currentItemId != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (restoreSubtitlesOff) {
            if (state.subtitleTracks.any { it.selected }) engine.selectSubtitleTrack(EngineTrack.OFF)
            return@LaunchedEffect
        }
        val target =
            subtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
                ?: return@LaunchedEffect
        if (!target.selected) engine.selectSubtitleTrack(target.id)
    }
    LaunchedEffect(
        engine,
        currentItemId,
        state.subtitleTracks,
        secondarySubtitleRestore,
        engine.supportsSecondarySubtitleTrack,
    ) {
        if (currentItemId != handoverItemId || state.subtitleTracks.isEmpty()) {
            return@LaunchedEffect
        }
        if (!engine.supportsSecondarySubtitleTrack) {
            onSecondarySubtitleTrackChanged(null)
            return@LaunchedEffect
        }
        val target = secondarySubtitleRestore?.let(state.subtitleTracks::bestRestoreMatch)
        if (target == null || target.selected) {
            engine.selectSecondarySubtitleTrack(EngineTrack.OFF)
            onSecondarySubtitleTrackChanged(null)
            return@LaunchedEffect
        }
        if (engine.selectSecondarySubtitleTrack(target.id)) {
            onSecondarySubtitleTrackChanged(target.id)
        }
    }

    LaunchedEffect(engine, engineKind, subtitleControls.offsetMs) {
        val applied = engine.setSubtitleOffsetMs(subtitleControls.offsetMs)
        if (!applied && subtitleControls.offsetMs != 0L) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(engine, engineKind, audioControls.delayMs) {
        val applied = engine.setAudioDelayMs(audioControls.delayMs)
        if (!applied && audioControls.delayMs != 0L) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(engine, engineKind, subtitleControls.scale) {
        if (engineKind != PlayerEngine.Exo) {
            val applied = engine.setSubtitleScale(subtitleControls.scale)
            if (!applied && subtitleControls.scale != 1f) {
                requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
            }
        }
    }
    LaunchedEffect(engine, engineKind, subtitleControls.brightness) {
        val applied = engine.setSubtitleBrightness(subtitleControls.brightness)
        if (!applied && subtitleControls.brightness != 1f) {
            requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
        }
    }
    LaunchedEffect(engine, engineKind, subtitleControls.position) {
        if (engineKind != PlayerEngine.Exo) {
            val applied = engine.setSubtitlePosition(subtitleControls.position)
            if (!applied && subtitleControls.position != DEFAULT_SUBTITLE_POSITION) {
                requestMpvIfAllowed(engineKind, automaticEngineSelection, onRequestMpv)
            }
        }
    }
    LaunchedEffect(engine, scaleMode) {
        (engine as? MpvVideoEngine)?.setScaleMode(scaleMode)
        (engine as? MdkVideoEngine)?.setFill(scaleMode != VideoScaleMode.Fit)
    }
    LaunchedEffect(engine, state.subtitleTracks, pendingSubtitleLanguage) {
        val language = pendingSubtitleLanguage ?: return@LaunchedEffect
        state.subtitleTracks.matchingLanguage(language)?.let { trackId ->
            engine.selectSubtitleTrack(trackId)
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
