package com.yfuse.feature.player

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The viewer's remembered choices for the series, restored as each item starts: its tracks through
 * [initialTracks], then speed, picture fit and subtitle and audio tuning; and, once the output route
 * is verified, the audio delay kept for that route, falling back to the series' own.
 */
@Composable
internal fun PlayerSeriesRestoreEffects(
    currentItem: PlayerMediaItem?,
    stateSource: State<PlaybackState>,
    choices: PlayerViewerChoices,
    playbackPreferences: PlaybackPreferences,
    audioOutputDelayPreferences: AudioOutputDelayPreferences,
    initialTracks: (PlayerMediaItem) -> YInitialTrackSelection?,
) {
    val state by stateSource
    LaunchedEffect(currentItem?.serverId, currentItem?.seriesId, currentItem?.id) {
        val item = currentItem ?: return@LaunchedEffect
        val remembered =
            playbackPreferences.rememberedSeriesPlayback(
                serverId = item.serverId,
                seriesId = item.seriesId,
                itemId = item.id,
            )
        choices.handoverItemId = item.id
        val initialTracks = initialTracks(item)
        choices.audioRestore =
            initialTracks?.audio?.let {
                TrackRestorePreference(
                    it.language,
                    it.label.orEmpty(),
                    it.codec,
                    it.languageOrdinal,
                )
            }
        choices.subtitleRestore =
            initialTracks?.subtitle?.let {
                TrackRestorePreference(
                    it.language,
                    it.label.orEmpty(),
                    it.codec,
                    it.languageOrdinal,
                )
            }
        choices.secondarySubtitleRestore = remembered?.secondarySubtitle?.toRestorePreference()
        choices.secondarySubtitleTrackId = null
        choices.restoreSubtitlesOff = initialTracks?.subtitlesDisabled == true
        choices.requestedPlaybackSpeed = remembered?.speed ?: 1f
        choices.audioControls =
            choices.audioControls.copy(
                delayMs =
                    audioOutputDelayPreferences.read(
                        choices.lastVerifiedAudioRoute,
                    ) ?: remembered?.audioDelayMs ?: 0L,
                enhancement =
                    remembered
                        ?.audioEnhancement
                        ?.let { stored -> AudioEnhancementMode.entries.firstOrNull { it.name == stored } }
                        ?: AudioEnhancementMode.Off,
            )
        choices.scaleMode =
            remembered
                ?.aspectMode
                ?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
                ?: VideoScaleMode.Fit
        choices.subtitleControls =
            choices.subtitleControls.copy(
                offsetMs = remembered?.subtitleOffsetMs ?: 0L,
                scale = remembered?.subtitleScale ?: 1f,
                secondaryScale = remembered?.secondarySubtitleScale ?: 1f,
                secondaryOffsetMs = remembered?.secondarySubtitleOffsetMs ?: 0L,
                brightness = remembered?.subtitleBrightness ?: 1f,
                position = remembered?.subtitlePosition ?: DEFAULT_SUBTITLE_POSITION,
                stylePreset =
                    remembered
                        ?.subtitleStylePreset
                        ?.let { stored -> SubtitleStylePreset.entries.firstOrNull { it.name == stored } }
                        ?: SubtitleStylePreset.Standard,
                appearance =
                    SubtitleAppearance(
                        textColorArgb = remembered?.subtitleTextColorArgb ?: 0xFFFFFFFFL,
                        backgroundColorArgb = remembered?.subtitleBackgroundColorArgb ?: 0x00000000L,
                        outlineColorArgb = remembered?.subtitleOutlineColorArgb ?: 0xFF000000L,
                        outlineWidth = remembered?.subtitleOutlineWidth ?: 2f,
                    ),
            )
    }
    LaunchedEffect(
        currentItem?.id,
        state.diagnostics.audioOutputRoute,
        state.diagnostics.audioOutputRouteVerified,
    ) {
        val route = state.diagnostics.audioOutputRoute
        if (!state.diagnostics.audioOutputRouteVerified || route.isBlank()) return@LaunchedEffect
        if (route != choices.lastVerifiedAudioRoute) {
            choices.lastVerifiedAudioRoute = route
            val item = currentItem
            val seriesDelay =
                playbackPreferences
                    .rememberedSeriesPlayback(
                        serverId = item?.serverId,
                        seriesId = item?.seriesId,
                        itemId = item?.id,
                    )?.audioDelayMs ?: 0L
            choices.audioControls =
                choices.audioControls.copy(delayMs = audioOutputDelayPreferences.read(route) ?: seriesDelay)
        }
    }
}

/**
 * Tracks picked before the player opened, applied once the engine has published what the file
 * holds: the detail page's 音轨 / 字幕 (PlaybackTrackRequest), and what a 接力 handoff carries —
 * tracks, the second subtitle, speed and offsets — for the same item and profile.
 */
@Composable
internal fun PlayerRequestedTrackEffects(
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    currentItem: PlayerMediaItem?,
    stateSource: State<PlaybackState>,
    choices: PlayerViewerChoices,
    trackRequest: PlaybackTrackRequest,
    handoffBridge: com.yfuse.core.handoff.HandoffPlaybackRegistry?,
    personalLibrary: com.yfuse.core.personal.PersonalLibraryRepository?,
) {
    val context = LocalContext.current
    val state by stateSource
    // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
    // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
    LaunchedEffect(player, currentItem?.id, state.audioTracks, state.subtitleTracks) {
        if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
        val requested = trackRequest.peek(currentItem?.id) ?: return@LaunchedEffect
        var audioApplied = requested.audioLanguage == null
        var subtitleApplied = requested.subtitleLanguage == null
        requested.audioLanguage?.let { language ->
            state.audioTracks.matchingRequestedTrack(language, requested.audioHint)?.let { trackId ->
                state.audioTracks.firstOrNull { it.id == trackId }?.let { track ->
                    choices.handoverItemId = currentItem?.id
                    choices.audioRestore = state.audioTracks.restorePreferenceFor(track)
                }
                if (state.audioTracks.none { it.id == trackId && it.selected }) {
                    player.selectTrack(YTrackType.Audio, trackId)
                }
                audioApplied = state.audioTracks.any { it.id == trackId && it.selected }
            }
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> {
                choices.handoverItemId = currentItem?.id
                choices.subtitleRestore = null
                choices.restoreSubtitlesOff = true
                if (state.subtitleTracks.any { it.selected }) {
                    player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
                }
                subtitleApplied = state.subtitleTracks.none { it.selected }
            }
            else ->
                state.subtitleTracks
                    .matchingRequestedTrack(subtitle, requested.subtitleHint)
                    ?.let { trackId ->
                        state.subtitleTracks.firstOrNull { it.id == trackId }?.let { track ->
                            choices.handoverItemId = currentItem?.id
                            choices.subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                            choices.restoreSubtitlesOff = false
                        }
                        if (state.subtitleTracks.none { it.id == trackId && it.selected }) {
                            player.selectTrack(YTrackType.Subtitle, trackId)
                        }
                        subtitleApplied = state.subtitleTracks.any { it.id == trackId && it.selected }
                    }
        }
        trackRequest.acknowledge(currentItem?.id, requested, audioApplied, subtitleApplied)
    }

    val receivedPreferences by (
        handoffBridge?.pendingPreferences ?: remember {
            kotlinx.coroutines.flow.MutableStateFlow<com.yfuse.core.handoff.HandoffMedia?>(null)
        }
    ).collectAsState()
    val handoffReady by remember(player) {
        player.state.map { it.phase == com.yfuse.core2.api.YPlaybackPhase.Ready }.distinctUntilChanged()
    }.collectAsState(false)
    val handoffPreferenceWait =
        remember(receivedPreferences) { HandoffPreferenceWait(SystemClock.elapsedRealtime()) }
    var handoffPreferenceDeadlineElapsed by remember(receivedPreferences) { mutableStateOf(false) }
    LaunchedEffect(receivedPreferences, handoffPreferenceWait) {
        if (receivedPreferences == null) return@LaunchedEffect
        delay(handoffPreferenceWait.remainingMs(SystemClock.elapsedRealtime()))
        handoffPreferenceDeadlineElapsed = true
    }
    LaunchedEffect(
        player,
        currentItem?.subtitleItemKey(),
        handoffReady,
        state.audioTracks,
        state.subtitleTracks,
        receivedPreferences,
        handoffPreferenceDeadlineElapsed,
    ) {
        val received = receivedPreferences ?: return@LaunchedEffect
        val item = currentItem ?: return@LaunchedEffect
        if (!handoffReady ||
            item.serverId != received.serverId ||
            item.id != received.itemId ||
            item.versionId != received.mediaSourceId ||
            item.watchKey != received.mediaKey
        ) {
            return@LaunchedEffect
        }
        if (personalLibrary?.activeProfileId != received.profileId) return@LaunchedEffect
        val preference = received.preference
        val resolution =
            handoffPreferenceWait.resolve(
                media = received,
                audioTracks = state.audioTracks,
                subtitleTracks = state.subtitleTracks,
                supportsSecondary = backendExtensions.supportsSecondarySubtitleTrack,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        if (!resolution.apply) return@LaunchedEffect
        val missing = resolution.missing.toMutableList()
        choices.handoverItemId = item.id
        resolution.audio?.let { track ->
            choices.audioRestore = state.audioTracks.restorePreferenceFor(track)
            player.selectTrack(YTrackType.Audio, track.id)
        }
        choices.restoreSubtitlesOff = preference?.subtitlesEnabled == false
        if (choices.restoreSubtitlesOff) {
            choices.subtitleRestore = null
            player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
        } else {
            resolution.subtitle?.let { track ->
                choices.subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                player.selectTrack(YTrackType.Subtitle, track.id)
            }
        }
        if (backendExtensions.supportsSecondarySubtitleTrack) {
            val secondary = resolution.secondarySubtitle
            if (backendExtensions.selectSecondarySubtitleTrack(secondary?.id ?: EngineTrack.OFF)) {
                choices.secondarySubtitleRestore = secondary?.let(state.subtitleTracks::restorePreferenceFor)
                choices.secondarySubtitleTrackId = secondary?.id
            } else if (received.secondarySubtitlesEnabled == true) {
                missing += "副字幕"
            }
        }
        choices.requestedPlaybackSpeed = preference?.playbackSpeed ?: 1f
        choices.subtitleControls =
            choices.subtitleControls.copy(
                offsetMs = received.subtitleOffsetMs ?: 0L,
                secondaryOffsetMs = received.secondarySubtitleOffsetMs ?: 0L,
            )
        choices.audioControls = choices.audioControls.copy(delayMs = received.audioOffsetMs ?: 0L)
        handoffBridge?.clearPreferences(received)
        if (missing.isNotEmpty()) {
            Toast
                .makeText(
                    context,
                    "接力设置未完整恢复：${missing.distinct().joinToString("、")}，可在播放器中重新选择。",
                    Toast.LENGTH_LONG,
                ).show()
        }
    }
}
