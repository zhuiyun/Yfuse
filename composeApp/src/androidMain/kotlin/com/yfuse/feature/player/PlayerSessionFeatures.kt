package com.yfuse.feature.player

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastState
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.WatchTogetherState
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Track, subtitle and audio choices that must survive an engine rebuild and follow the viewer from
 * one episode to the next. One holder rather than ten `remember`s in the root, so the effects that
 * restore them — remembered series preferences, a detail-page track request, a handoff from
 * another device — can be composed from outside it.
 */
@Stable
internal class PlayerTrackSession {
    val requestedPlaybackSpeed = mutableFloatStateOf(1f)
    val handoverItemId = mutableStateOf<String?>(null)
    val audioRestore = mutableStateOf<TrackRestorePreference?>(null)
    val subtitleRestore = mutableStateOf<TrackRestorePreference?>(null)
    val secondarySubtitleRestore = mutableStateOf<TrackRestorePreference?>(null)
    val secondarySubtitleTrackId = mutableStateOf<String?>(null)
    val restoreSubtitlesOff = mutableStateOf(false)
    val scaleMode = mutableStateOf(VideoScaleMode.Fit)
    val subtitleControls = mutableStateOf(SubtitleControlState())
    val audioControls = mutableStateOf(AudioControlState())
    val lastVerifiedAudioRoute = mutableStateOf("")
    val pendingSubtitleLanguage = mutableStateOf<String?>(null)
}

/** 睡眠定时: the chosen option and, for 「播完本集」, which entry and cast session it is armed for. */
@Stable
internal class PlayerSleepTimer {
    val option = mutableStateOf(SleepTimerOption.Off)
    val endIndex = mutableStateOf<Int?>(null)
    val endSessionRevision = mutableStateOf<Long?>(null)
    val armedItemReachedEnd = mutableStateOf(false)

    /** Bumped on every selection so choosing the same duration again restarts its countdown. */
    val revision = mutableIntStateOf(0)
}

/**
 * Runs the sleep timer — a countdown of played time, or the end of the armed episode — and
 * returns the action that fires it, which the cast queue also needs when the armed episode ends
 * on a receiver instead of on this device.
 */
@Composable
internal fun rememberPlayerSleepTimerPause(
    sleepTimer: PlayerSleepTimer,
    player: YPlayer,
    castManager: CastManager,
    castState: CastState,
    state: PlaybackState,
    localState: PlaybackState,
    liveLocalState: State<PlaybackState>,
    scope: CoroutineScope,
): (String) -> Unit {
    val context = LocalContext.current
    var sleepTimerOption by sleepTimer.option
    var sleepTimerEndIndex by sleepTimer.endIndex
    var sleepTimerEndSessionRevision by sleepTimer.endSessionRevision
    var sleepTimerArmedItemReachedEnd by sleepTimer.armedItemReachedEnd
    val sleepTimerRevision by sleepTimer.revision
    val latestPlayerForSleep by rememberUpdatedState(player)
    val latestCastStateForSleep by rememberUpdatedState(castState)

    fun pauseForSleepTimer(message: String) {
        latestPlayerForSleep.pause()
        val pauseCast = latestCastStateForSleep.hasActiveSession
        sleepTimerOption = SleepTimerOption.Off
        sleepTimerEndIndex = null
        sleepTimerEndSessionRevision = null
        sleepTimerArmedItemReachedEnd = false
        if (pauseCast) scope.launch { castManager.pause() }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val sleepTimerPlaying by rememberUpdatedState(state.playing)
    LaunchedEffect(sleepTimerOption, sleepTimerRevision) {
        val durationMs = sleepTimerOption.durationMs ?: return@LaunchedEffect
        // Counts playback, not wall-clock: a pause to answer the door must not use up the timer.
        var remainingMs = durationMs
        while (remainingMs > 0L) {
            if (!sleepTimerPlaying) {
                delay(SLEEP_TIMER_PAUSED_POLL_MS)
                continue
            }
            val step = minOf(SLEEP_TIMER_TICK_MS, remainingMs)
            delay(step)
            remainingMs -= step
        }
        pauseForSleepTimer("睡眠定时已到，播放已暂停")
    }
    LaunchedEffect(sleepTimerOption, sleepTimerEndIndex, liveLocalState) {
        snapshotFlow { liveLocalState.value }.collect { current ->
            if (sleepTimerOption == SleepTimerOption.EndOfEpisode &&
                sleepTimerEndIndex == current.currentIndex &&
                current.durationMs > 0L &&
                current.remainingMs <= END_OF_EPISODE_ARM_WINDOW_MS
            ) {
                sleepTimerArmedItemReachedEnd = true
            }
        }
    }
    LaunchedEffect(
        sleepTimerOption,
        sleepTimerEndIndex,
        sleepTimerArmedItemReachedEnd,
        localState.currentIndex,
        localState.ended,
        localState.playing,
    ) {
        if (sleepTimerOption != SleepTimerOption.EndOfEpisode || castState.hasActiveSession) {
            return@LaunchedEffect
        }
        if (
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = sleepTimerEndIndex,
                currentIndex = localState.currentIndex,
                ended = localState.ended,
                playing = localState.playing,
                armedItemReachedEnd = sleepTimerArmedItemReachedEnd,
            )
        ) {
            pauseForSleepTimer("本集已结束，播放已暂停")
        }
    }
    return { message -> pauseForSleepTimer(message) }
}

/** A new entry starts from what the viewer last chose for its series, not from the engine defaults. */
@Composable
internal fun RestoreRememberedSeriesPlayback(
    currentItem: PlayerMediaItem?,
    playbackPreferences: PlaybackPreferences,
    audioOutputDelayPreferences: AudioOutputDelayPreferences,
    trackSession: PlayerTrackSession,
) {
    var handoverItemId by trackSession.handoverItemId
    var audioRestore by trackSession.audioRestore
    var subtitleRestore by trackSession.subtitleRestore
    var secondarySubtitleRestore by trackSession.secondarySubtitleRestore
    var secondarySubtitleTrackId by trackSession.secondarySubtitleTrackId
    var restoreSubtitlesOff by trackSession.restoreSubtitlesOff
    var requestedPlaybackSpeed by trackSession.requestedPlaybackSpeed
    var audioControls by trackSession.audioControls
    var scaleMode by trackSession.scaleMode
    var subtitleControls by trackSession.subtitleControls
    val lastVerifiedAudioRoute by trackSession.lastVerifiedAudioRoute
    LaunchedEffect(currentItem?.serverId, currentItem?.seriesId, currentItem?.id) {
        val item = currentItem ?: return@LaunchedEffect
        val remembered =
            playbackPreferences.rememberedSeriesPlayback(
                serverId = item.serverId,
                seriesId = item.seriesId,
                itemId = item.id,
            )
        handoverItemId = item.id
        audioRestore = remembered?.audio?.toRestorePreference()
        subtitleRestore = remembered?.primarySubtitle?.toRestorePreference()
        secondarySubtitleRestore = remembered?.secondarySubtitle?.toRestorePreference()
        secondarySubtitleTrackId = null
        restoreSubtitlesOff = remembered?.primarySubtitlesOff == true
        requestedPlaybackSpeed = remembered?.speed ?: 1f
        audioControls =
            audioControls.copy(
                delayMs =
                    audioOutputDelayPreferences.read(
                        lastVerifiedAudioRoute,
                    ) ?: remembered?.audioDelayMs ?: 0L,
                enhancement =
                    remembered
                        ?.audioEnhancement
                        ?.let { stored -> AudioEnhancementMode.entries.firstOrNull { it.name == stored } }
                        ?: AudioEnhancementMode.Off,
            )
        scaleMode =
            remembered
                ?.aspectMode
                ?.let { stored -> VideoScaleMode.entries.firstOrNull { it.name == stored } }
                ?: VideoScaleMode.Fit
        subtitleControls =
            subtitleControls.copy(
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
}

/** Audio delay belongs to the output route: a Bluetooth headset and the speaker need different ones. */
@Composable
internal fun RestoreAudioDelayForOutputRoute(
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    playbackPreferences: PlaybackPreferences,
    audioOutputDelayPreferences: AudioOutputDelayPreferences,
    trackSession: PlayerTrackSession,
) {
    var lastVerifiedAudioRoute by trackSession.lastVerifiedAudioRoute
    var audioControls by trackSession.audioControls
    LaunchedEffect(
        currentItem?.id,
        state.diagnostics.audioOutputRoute,
        state.diagnostics.audioOutputRouteVerified,
    ) {
        val route = state.diagnostics.audioOutputRoute
        if (!state.diagnostics.audioOutputRouteVerified || route.isBlank()) return@LaunchedEffect
        if (route != lastVerifiedAudioRoute) {
            lastVerifiedAudioRoute = route
            val item = currentItem
            val seriesDelay =
                playbackPreferences
                    .rememberedSeriesPlayback(
                        serverId = item?.serverId,
                        seriesId = item?.seriesId,
                        itemId = item?.id,
                    )?.audioDelayMs ?: 0L
            audioControls = audioControls.copy(delayMs = audioOutputDelayPreferences.read(route) ?: seriesDelay)
        }
    }
}

/** The storyboard the queue already carried, or the one loaded lazily for the entry on screen. */
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

@Composable
internal fun ApplyRequestedTracks(
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    player: YPlayer,
    trackSession: PlayerTrackSession,
) {
    var handoverItemId by trackSession.handoverItemId
    var audioRestore by trackSession.audioRestore
    var subtitleRestore by trackSession.subtitleRestore
    var restoreSubtitlesOff by trackSession.restoreSubtitlesOff
    // 详情页 picked a 音轨 / 字幕 before this opened; apply it once the engine has published
    // what the file actually holds. Consumed rather than remembered — see PlaybackTrackRequest.
    val trackRequest = remember { GlobalContext.get().get<PlaybackTrackRequest>() }
    LaunchedEffect(currentItem?.id, state.audioTracks.size, state.subtitleTracks.size) {
        if (state.audioTracks.isEmpty() && state.subtitleTracks.isEmpty()) return@LaunchedEffect
        val requested = trackRequest.consume(currentItem?.id) ?: return@LaunchedEffect
        requested.audioLanguage?.let { language ->
            state.audioTracks.matchingLanguage(language)?.let { trackId ->
                state.audioTracks.firstOrNull { it.id == trackId }?.let { track ->
                    handoverItemId = currentItem?.id
                    audioRestore = state.audioTracks.restorePreferenceFor(track)
                }
                player.selectTrack(YTrackType.Audio, trackId)
            }
        }
        when (val subtitle = requested.subtitleLanguage) {
            null -> Unit
            PlaybackTrackRequest.SUBTITLES_OFF -> {
                handoverItemId = currentItem?.id
                subtitleRestore = null
                restoreSubtitlesOff = true
                player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
            }
            else ->
                state.subtitleTracks
                    .matchingLanguage(subtitle)
                    ?.let { trackId ->
                        state.subtitleTracks.firstOrNull { it.id == trackId }?.let { track ->
                            handoverItemId = currentItem?.id
                            subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                            restoreSubtitlesOff = false
                        }
                        player.selectTrack(YTrackType.Subtitle, trackId)
                    }
        }
    }
}

/**
 * Applies the tracks, speed and offsets that came with a playback handed over from another device,
 * once this engine is ready and has published the tracks they refer to — or the wait ran out.
 */
@Composable
internal fun ApplyHandoffPreferences(
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    personalLibrary: com.yfuse.core.personal.PersonalLibraryRepository?,
    trackSession: PlayerTrackSession,
) {
    val context = LocalContext.current
    var handoverItemId by trackSession.handoverItemId
    var audioRestore by trackSession.audioRestore
    var subtitleRestore by trackSession.subtitleRestore
    var secondarySubtitleRestore by trackSession.secondarySubtitleRestore
    var secondarySubtitleTrackId by trackSession.secondarySubtitleTrackId
    var restoreSubtitlesOff by trackSession.restoreSubtitlesOff
    var requestedPlaybackSpeed by trackSession.requestedPlaybackSpeed
    var subtitleControls by trackSession.subtitleControls
    var audioControls by trackSession.audioControls
    val handoffBridge = remember { GlobalContext.get().getOrNull<com.yfuse.core.handoff.HandoffPlaybackRegistry>() }
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
        handoverItemId = item.id
        resolution.audio?.let { track ->
            audioRestore = state.audioTracks.restorePreferenceFor(track)
            player.selectTrack(YTrackType.Audio, track.id)
        }
        restoreSubtitlesOff = preference?.subtitlesEnabled == false
        if (restoreSubtitlesOff) {
            subtitleRestore = null
            player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
        } else {
            resolution.subtitle?.let { track ->
                subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                player.selectTrack(YTrackType.Subtitle, track.id)
            }
        }
        if (backendExtensions.supportsSecondarySubtitleTrack) {
            val secondary = resolution.secondarySubtitle
            if (backendExtensions.selectSecondarySubtitleTrack(secondary?.id ?: EngineTrack.OFF)) {
                secondarySubtitleRestore = secondary?.let(state.subtitleTracks::restorePreferenceFor)
                secondarySubtitleTrackId = secondary?.id
            } else if (received.secondarySubtitlesEnabled == true) {
                missing += "副字幕"
            }
        }
        requestedPlaybackSpeed = preference?.playbackSpeed ?: 1f
        subtitleControls =
            subtitleControls.copy(
                offsetMs = received.subtitleOffsetMs ?: 0L,
                secondaryOffsetMs = received.secondarySubtitleOffsetMs ?: 0L,
            )
        audioControls = audioControls.copy(delayMs = received.audioOffsetMs ?: 0L)
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

/** The room as the chrome reads it: connection facts from the client, presentation switches from preferences. */
internal fun watchRoomState(
    watchState: WatchTogetherState,
    available: Boolean,
    endpoint: String,
    chatPreviewEnabled: Boolean,
    chatDanmakuEnabled: Boolean,
): WatchRoomState =
    WatchRoomState(
        available = available,
        endpoint = endpoint,
        connecting = watchState.connecting,
        connected = watchState.connected,
        reconnecting = watchState.reconnecting,
        roomCode = watchState.roomCode,
        isHost = watchState.isHost,
        canControl = watchState.canControl,
        controlMode = watchState.controlMode,
        participantCount = watchState.participantCount,
        participants = watchState.participants,
        chatMessages = watchState.chatMessages,
        chatError = watchState.chatError,
        reactions = watchState.reactions,
        chatPreviewEnabled = chatPreviewEnabled,
        chatDanmakuEnabled = chatDanmakuEnabled,
        error = watchState.error ?: watchState.syncWarning,
        controlRequested = watchState.controlRequested,
        controlRequesterName = watchState.controlRequest?.name,
    )

internal fun watchRoomActions(
    watchTogether: WatchTogetherClient,
    watchTogetherPreferences: WatchTogetherPreferences,
    watchState: WatchTogetherState,
    currentItem: PlayerMediaItem?,
    chatDanmakuEnabled: Boolean,
): WatchRoomActions =
    WatchRoomActions(
        onCreate = { endpoint ->
            currentItem?.let { item ->
                watchTogether.createRoom(endpoint, item.watchKey)
            }
        },
        onJoin = { endpoint, roomCode ->
            currentItem?.let { item ->
                watchTogether.joinRoom(endpoint, roomCode, item.watchKey)
            }
        },
        onLeave = watchTogether::leave,
        onRequestControl = watchTogether::requestControl,
        onGrantControl = {
            watchState.controlRequest?.let { watchTogether.grantControl(it.clientId) }
        },
        onDenyControl = {
            watchState.controlRequest?.let { watchTogether.denyControl(it.clientId) }
        },
        onSendChat = watchTogether::sendChat,
        onRetryChat = watchTogether::retryChat,
        onClearChatError = watchTogether::clearChatError,
        onSetControlMode = watchTogether::setControlMode,
        onSetModerator = watchTogether::setModerator,
        onKickParticipant = watchTogether::kickParticipant,
        onToggleChatDanmaku = {
            watchTogetherPreferences.setChatDanmakuEnabled(!chatDanmakuEnabled)
        },
        onReact = { watchTogether.sendReaction(it) },
        onReactionFinished = watchTogether::clearReaction,
    )

private const val END_OF_EPISODE_ARM_WINDOW_MS = 2_000L
private const val SLEEP_TIMER_TICK_MS = 1_000L
private const val SLEEP_TIMER_PAUSED_POLL_MS = 500L
