package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection

// The state PlayerRoot keeps for the whole viewing session, across every engine it builds. It was
// three dozen remembered locals of one composable; held here, the effects and callbacks split out of
// PlayerRoot are handed a holder and read and write the same snapshot state the root does. Each is
// remembered once by PlayerRoot and touched on the main thread only: composition, effects and the
// controls' callbacks.

/**
 * The engine PlayerRoot builds next and where that build resumes. Every switch, fallback and
 * recovery writes here; a new [kind] or [effectiveDecoderMode] builds a different engine, and a bump
 * of [engineGeneration] rebuilds the same one from [resume].
 */
@Stable
internal class PlayerEngineBuild(
    kind: PlayerEngine,
    decoderMode: DecoderMode,
    resume: PlaybackHandoverSnapshot,
) {
    var kind: PlayerEngine by mutableStateOf(kind)
    var effectiveDecoderMode: DecoderMode by mutableStateOf(decoderMode)

    /** Everything a newly built backend needs to resume without changing user intent. */
    var resume: PlaybackHandoverSnapshot by mutableStateOf(resume)
    var engineGeneration: Int by mutableIntStateOf(0)

    /** Bumped when the running engine restarts its own pipeline in place, without a rebuild. */
    var runtimeSessionGeneration: Int by mutableIntStateOf(0)

    /** The YCore 2.0 trial failed once; the rest of the session builds the selected Legacy engine. */
    var core2DisabledForSession: Boolean by mutableStateOf(false)
}

/**
 * What the viewer chose for this session — the per-video engine strategy, tracks, speed, picture fit,
 * and subtitle and audio tuning — which every rebuilt engine takes over through [handover] and the
 * track restore in PlayerTrackEffects.
 */
@Stable
internal class PlayerViewerChoices(
    sessionEngineSelection: PlaybackEngineSelection,
) {
    var sessionEngineSelection: PlaybackEngineSelection by mutableStateOf(sessionEngineSelection)
    var requestedPlaybackSpeed: Float by mutableFloatStateOf(1f)
    var handoverItemId: String? by mutableStateOf(null)
    var audioRestore: TrackRestorePreference? by mutableStateOf(null)
    var subtitleRestore: TrackRestorePreference? by mutableStateOf(null)
    var secondarySubtitleRestore: TrackRestorePreference? by mutableStateOf(null)
    var secondarySubtitleTrackId: String? by mutableStateOf(null)
    var restoreSubtitlesOff: Boolean by mutableStateOf(false)

    // 没听清: a subtitle shown for a replay. Kept out of the restore state above, series memory and
    // the preferences alike; while it runs, the track restore stands aside.
    var subtitlePeek: SubtitlePeek? by mutableStateOf(null)
    var scaleMode: VideoScaleMode by mutableStateOf(VideoScaleMode.Fit)
    var subtitleControls: SubtitleControlState by mutableStateOf(SubtitleControlState())
    var audioControls: AudioControlState by mutableStateOf(AudioControlState())
    var lastVerifiedAudioRoute: String by mutableStateOf("")
    var pendingSubtitleLanguage: String? by mutableStateOf(null)

    /**
     * The snapshot a rebuilt engine resumes from: [state]'s item at [positionMs], with the viewer's
     * speed, second subtitle and delays carried across.
     */
    fun handover(
        state: PlaybackState,
        positionMs: Long,
        playbackRequested: Boolean,
    ): PlaybackHandoverSnapshot =
        playbackHandoverSnapshot(
            state = state,
            currentPositionMs = positionMs,
            playbackRequested = playbackRequested,
            requestedSpeed = requestedPlaybackSpeed,
            secondarySubtitle = secondarySubtitleRestore,
            subtitleDelayMs = subtitleControls.offsetMs,
            audioDelayMs = audioControls.delayMs,
        )
}

/** Which copy of each queue entry plays, and the subtitles imported for it. */
@Stable
internal class PlayerSourceChoices {
    // Entry id -> chosen file, for titles the server holds more than one copy of. Switching
    // rebuilds the queue and restarts the engine at the same position, which is the same
    // handover an engine switch already performs — no engine needs to know about versions.
    var versionChoices: Map<String, PlayerMediaVersion> by mutableStateOf(emptyMap())

    // Queue index -> the same entry on another server, chosen by hand or by failover.
    var serverChoices: Map<Int, PlayerMediaItem> by mutableStateOf(emptyMap())
    var importedSubtitles: Map<SubtitleItemKey, List<PlayerExternalSubtitle>> by mutableStateOf(emptyMap())

    // Entry id -> why the server has to transcode it, for an engine that cannot rewrite an open source
    // in place (YCore). The session restarts at the same position with that entry transcoded.
    var forcedTranscodes: Map<String, ForcedTranscode> by mutableStateOf(emptyMap())
}

/** Why an entry is restarted on the server's transcode, and whether the viewer asked for it. */
internal data class ForcedTranscode(
    val reason: String,
    val byViewer: Boolean,
)
