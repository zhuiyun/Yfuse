@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yfuse.feature.player

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.CastTrackKind
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackPerformanceMemory
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.WatchTogetherState
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.tv.player.TvPlayerChromeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.yfuse.core.platform.AppBuildConfig as BuildConfig

/**
 * PlayerControls wired to the player root: each callback the chrome has, answered on the engine,
 * the cast receiver or the room, and the viewer's choices it reads and remembers per series.
 *
 * Moved out of PlayerRoot's runtime content, where this one call ran to ~860 lines, so it compiles
 * to methods of its own (see proguard-rules.pro). It is composed inside the surface's
 * AnimatedVisibility and so leaves composition in 画中画: nothing here may hold state that has to
 * outlive that, and [scope] is PlayerRoot's, so work a callback launches is not cut short by it.
 * What a callback reads when it runs — the playback, cast and room state — arrives as State, read
 * through the same delegates the inline code used, so a callback still sees the live value.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerRootControls(
    ambient: PlayerAmbientBinding,
    engine: VideoEngine,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    build: PlayerEngineBuild,
    choices: PlayerViewerChoices,
    sleepTimer: PlayerSleepTimer,
    gestures: PlayerGestureCommands,
    livePlayback: State<PlaybackState>,
    stateSource: State<PlaybackState>,
    liveLocalState: State<PlaybackState>,
    castStateSource: State<CastState>,
    liveCastState: State<CastState>,
    watchStateSource: State<WatchTogetherState>,
    watchAvailable: Boolean,
    watchEndpoint: String,
    watchChatPreview: Boolean,
    watchChatDanmaku: State<Boolean>,
    activeItems: List<PlayerMediaItem>,
    currentItem: PlayerMediaItem?,
    currentTrickplay: TrickplayStoryboard?,
    bookmarkBinding: Pair<PlaybackBookmarkPanelState, PlaybackBookmarkActions>,
    remoteSubtitles: RemoteSubtitlePanelState,
    remoteSubtitleActions: RemoteSubtitleActions,
    danmaku: PlayerDanmakuController,
    danmakuPicker: DanmakuPicker,
    skip: PlayerSkipController,
    quickCast: PlayerQuickCast,
    chromeExtras: PlayerChromeExtras,
    serverNames: Map<String, String>,
    sourceOptions: List<Pair<String, String>>,
    initialResumeNoticeMs: Long?,
    core2NativeOnlyActive: Boolean,
    autoNext: Boolean,
    customUserAgent: String,
    gestureSettings: PlayerGestureSettings,
    volumeLevel: State<Float>,
    setVolume: (Float) -> Unit,
    brightnessLevel: State<Float>,
    setBrightness: (Float) -> Unit,
    volumeKeyPresses: StateFlow<Long>,
    controlsWakeRequests: Int,
    creditsTakeover: MutableState<Boolean>,
    transition: PlayerTransitionState?,
    remoteChrome: TvPlayerChromeBridge?,
    playbackPreferences: PlaybackPreferences,
    audioOutputDelayPreferences: AudioOutputDelayPreferences,
    failureMemory: PlaybackFailureMemory,
    performanceMemory: PlaybackPerformanceMemory,
    castManager: CastManager,
    playbackGate: WatchGatedPlayback,
    watchTogether: WatchTogetherClient,
    watchTogetherPreferences: WatchTogetherPreferences,
    sourceSwitchCoordinator: PlaybackSourceSwitchCoordinator,
    scope: CoroutineScope,
    requestCastDiscovery: () -> Unit,
    loadCastItem: suspend (String, Int, Long) -> Boolean,
    rememberSeriesPlayback: ((SeriesPlaybackPreference) -> SeriesPlaybackPreference) -> Unit,
    applySubtitlePair: (EngineTrack, EngineTrack) -> Unit,
    switchEngine: (PlayerEngine) -> Unit,
    selectEngineStrategy: (PlaybackEngineSelection) -> Unit,
    selectServer: (String) -> Unit,
    selectVersion: (String) -> Unit,
    onDismissNextUp: () -> Unit,
    onBack: () -> Unit,
    onEnterPictureInPicture: (() -> Unit)?,
    onRefreshEpisodes: () -> Unit,
) {
    val context = LocalContext.current
    val state by stateSource
    val castState by castStateSource
    val watchState by watchStateSource
    PlayerControls(
        systemGestureTopPx =
            maxOf(
                WindowInsets.statusBarsIgnoringVisibility.getTop(LocalDensity.current),
                WindowInsets.systemGestures.getTop(LocalDensity.current),
            ).toFloat(),
        playback = livePlayback,
        bookmarks = bookmarkBinding.first,
        bookmarkActions = bookmarkBinding.second,
        episodes = remember(activeItems) { activeItems.toEpisodeCards() },
        scaleMode = choices.scaleMode,
        ambientLight = ambient.light.takeIf { ambient.enabled },
        ambientLightEnabled = ambient.enabled,
        onToggleAmbientLight = { playbackPreferences.setAmbientLight(!ambient.enabled) },
        onAmbientChromeVisibleChange = ambient.onChromeVisible,
        resumedFromMs = initialResumeNoticeMs,
        onBack = onBack,
        onEnterPictureInPicture = onEnterPictureInPicture,
        onPlayPause = {
            if (castState.hasActiveSession) {
                scope.launch {
                    if (
                        castState.status == CastPlaybackStatus.Playing ||
                        castState.status == CastPlaybackStatus.Buffering ||
                        (
                            castState.status == CastPlaybackStatus.Error &&
                                castState.lastRemoteWasPlaying
                        )
                    ) {
                        castManager.pause()
                    } else {
                        castManager.resume()
                    }
                }
            } else {
                playbackGate.togglePlayPause()
            }
        },
        onRetry = {
            sourceSwitchCoordinator.invalidate()
            val deviceId = castState.activeDeviceId
            if (castState.hasActiveSession && deviceId != null) {
                scope.launch {
                    loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs)
                }
            } else {
                playbackGate.retry()
            }
        },
        onExternalPlayer =
            currentItem?.takeUnless { core2NativeOnlyActive }?.let { item ->
                {
                    val mediaUrl =
                        if (state.transcoding) {
                            item.transcodeUrl.ifBlank { item.fallbackTranscodeUrl }
                        } else {
                            item.url
                        }
                    val handoverHeaders =
                        customUserAgent
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                mapOf(
                                    "User-Agent" to it,
                                )
                            }.orEmpty()
                    if (!openExternalPlayer(
                            context = context,
                            mediaUrl = mediaUrl,
                            title = item.title,
                            positionMs = livePlayback.value.positionMs,
                            headers = handoverHeaders,
                        )
                    ) {
                        Toast
                            .makeText(context, "未找到可处理此视频的外部播放器", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            },
        onSeek = gestures::seek,
        onSelectItem = { index ->
            sourceSwitchCoordinator.invalidate()
            sleepTimer.follow(index, castState.sessionRevision.takeIf { castState.hasActiveSession })
            val deviceId = castState.activeDeviceId
            if (castState.hasActiveSession && deviceId != null) {
                scope.launch { loadCastItem(deviceId, index, 0L) }
            } else {
                playbackGate.selectItem(index)
            }
        },
        onPreviousItem = {
            sourceSwitchCoordinator.invalidate()
            val previous = state.currentIndex - 1
            if (previous in activeItems.indices) {
                sleepTimer.follow(
                    previous,
                    castState.sessionRevision.takeIf { castState.hasActiveSession },
                )
            }
            val deviceId = castState.activeDeviceId
            if (castState.hasActiveSession && deviceId != null && previous in activeItems.indices) {
                scope.launch {
                    if (!castManager.queuePrevious()) loadCastItem(deviceId, previous, 0L)
                }
                true
            } else {
                playbackGate.selectPrevious()
            }
        },
        onDismissNextUp = onDismissNextUp,
        onCreditsTakeover = { creditsTakeover.value = it },
        autoNext = autoNext,
        onNextItem = {
            sourceSwitchCoordinator.invalidate()
            val next = state.currentIndex + 1
            if (next in activeItems.indices) {
                sleepTimer.follow(next, castState.sessionRevision.takeIf { castState.hasActiveSession })
            }
            val deviceId = castState.activeDeviceId
            if (castState.hasActiveSession && deviceId != null && next in activeItems.indices) {
                scope.launch {
                    if (!castManager.queueNext()) loadCastItem(deviceId, next, 0L)
                }
                true
            } else {
                playbackGate.selectNext()
            }
        },
        onRefreshEpisodes = onRefreshEpisodes,
        onSelectAudio = { id ->
            val selectedTrack = state.audioTracks.firstOrNull { it.id == id }
            selectedTrack?.let { track ->
                choices.handoverItemId = currentItem?.id
                choices.audioRestore = state.audioTracks.restorePreferenceFor(track)
                rememberSeriesPlayback { remembered ->
                    remembered.copy(audio = track.toRememberedPlaybackTrack())
                }
            }
            if (castState.hasActiveSession && selectedTrack != null) {
                scope.launch {
                    castManager.selectTrack(
                        kind = CastTrackKind.Audio,
                        language = selectedTrack.language,
                        label = selectedTrack.label,
                    )
                }
            } else {
                player.selectTrack(YTrackType.Audio, id)
            }
        },
        audioControls =
            choices.audioControls.copy(
                measuredAvOffsetMs = state.diagnostics.avSyncOffsetMs,
                available =
                    backendExtensions.supportsAudioDelay ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                enhancementAvailable =
                    backendExtensions.supportsAudioEnhancement ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                unavailableReason =
                    if (
                        build.kind == PlayerEngine.Mpv ||
                        choices.sessionEngineSelection == PlaybackEngineSelection.Auto
                    ) {
                        null
                    } else {
                        "当前锁定模式不支持音频延迟，请在高级设置中改回自动选择。"
                    },
            ),
        audioActions =
            AudioControlActions(
                onDelay = {
                    choices.audioControls = choices.audioControls.copy(delayMs = it)
                    audioOutputDelayPreferences.write(choices.lastVerifiedAudioRoute, it)
                    rememberSeriesPlayback { remembered -> remembered.copy(audioDelayMs = it) }
                },
                onAutoSync = {
                    livePlayback.value.diagnostics.avSyncOffsetMs?.let { measured ->
                        val corrected =
                            calibratedAudioDelayMs(choices.audioControls.delayMs, measured)
                        choices.audioControls = choices.audioControls.copy(delayMs = corrected)
                        audioOutputDelayPreferences.write(choices.lastVerifiedAudioRoute, corrected)
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(audioDelayMs = corrected)
                        }
                        Toast
                            .makeText(context, "已校准音画同步：$corrected ms", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onEnhancement = {
                    choices.audioControls = choices.audioControls.copy(enhancement = it)
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(audioEnhancement = it.name)
                    }
                },
            ),
        onSelectSubtitle = { id ->
            // An explicit pick ends any 没听清 replay subtitle; the pick is what stays.
            choices.subtitlePeek = null
            val track = state.subtitleTracks.firstOrNull { it.id == id }
            if (castState.hasActiveSession) {
                // The receiver applies it; the memory and the restore state are ours,
                // so a hand-back to the phone lands on the same subtitle.
                choices.handoverItemId = currentItem?.id
                choices.subtitleRestore = track?.let { state.subtitleTracks.restorePreferenceFor(it) }
                choices.restoreSubtitlesOff = id == EngineTrack.OFF
                rememberSeriesPlayback { remembered ->
                    remembered.copy(
                        primarySubtitlesOff = id == EngineTrack.OFF,
                        primarySubtitle = track?.toRememberedPlaybackTrack(),
                    )
                }
                scope.launch {
                    castManager.selectTrack(
                        kind = CastTrackKind.Subtitle,
                        language = track?.language,
                        label = track?.label.orEmpty(),
                        enabled = id != EngineTrack.OFF,
                    )
                }
                return@PlayerControls
            }
            if (id == EngineTrack.OFF) {
                choices.handoverItemId = currentItem?.id
                choices.subtitleRestore = null
                choices.restoreSubtitlesOff = true
                player.selectTrack(YTrackType.Subtitle, id)
                rememberSeriesPlayback { remembered ->
                    remembered.copy(
                        primarySubtitlesOff = true,
                        primarySubtitle = null,
                    )
                }
            } else if (
                track?.requiresStyledRenderer == true &&
                build.kind != PlayerEngine.Mpv &&
                choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                !core2NativeOnlyActive
            ) {
                choices.pendingSubtitleLanguage = track.language ?: track.label
                switchEngine(PlayerEngine.Mpv)
                choices.handoverItemId = currentItem?.id
                choices.subtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                choices.restoreSubtitlesOff = false
                if (choices.secondarySubtitleTrackId == id) {
                    choices.secondarySubtitleTrackId = null
                    choices.secondarySubtitleRestore = null
                }
                rememberSeriesPlayback { remembered ->
                    remembered.copy(
                        primarySubtitlesOff = false,
                        primarySubtitle = track.toRememberedPlaybackTrack(),
                        secondarySubtitle =
                            remembered.secondarySubtitle.takeUnless {
                                it == track.toRememberedPlaybackTrack()
                            },
                    )
                }
            } else {
                track?.let {
                    choices.handoverItemId = currentItem?.id
                    choices.subtitleRestore = state.subtitleTracks.restorePreferenceFor(it)
                    choices.restoreSubtitlesOff = false
                    if (choices.secondarySubtitleTrackId == id) {
                        backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
                        choices.secondarySubtitleTrackId = null
                        choices.secondarySubtitleRestore = null
                    }
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            primarySubtitlesOff = false,
                            primarySubtitle = it.toRememberedPlaybackTrack(),
                            secondarySubtitle =
                                remembered.secondarySubtitle.takeUnless { secondary ->
                                    secondary == it.toRememberedPlaybackTrack()
                                },
                        )
                    }
                }
                player.selectTrack(YTrackType.Subtitle, id)
            }
        },
        // 没听清: straight to the engine and nowhere else — no series memory, no preference
        // and no restore state, which is what the handover and the next item read.
        onPeekSubtitle = { peek ->
            if (!castState.hasActiveSession) {
                choices.subtitlePeek = peek
                player.selectTrack(YTrackType.Subtitle, peek.trackId)
            }
        },
        onEndSubtitlePeek = { restoreTrackId ->
            // A handover since the peek began has already carried the viewer's choice
            // across; the old engine's track ids mean nothing to the new one.
            val peeking = choices.subtitlePeek != null
            choices.subtitlePeek = null
            if (peeking && restoreTrackId != null && !castState.hasActiveSession) {
                player.selectTrack(YTrackType.Subtitle, restoreTrackId)
            }
        },
        subtitleControls =
            choices.subtitleControls.copy(
                secondaryTrackId = choices.secondarySubtitleTrackId,
                independentScaleAvailable =
                    engine is YPlayerVideoEngineAdapter ||
                        engine is ExoVideoEngine ||
                        (
                            engine is MpvVideoEngine &&
                                mpvCanStackSubtitles(
                                    state.subtitleTracks,
                                    state.subtitleTracks
                                        .firstOrNull {
                                            it.selected
                                        }?.id,
                                    choices.secondarySubtitleTrackId,
                                )
                        ),
                dualLayoutNote =
                    when (engine) {
                        is MpvVideoEngine -> "文本双字幕在底部排列；图片字幕保留原排版，可切换 YCore 或 Exo 调整。"
                        is MdkVideoEngine -> "此内核保留字幕原排版；底部双字幕与独立字号请切换 YCore 或 Exo。"
                        else -> null
                    },
                secondarySupported = backendExtensions.supportsSecondarySubtitleTrack,
                secondaryOffsetAvailable = backendExtensions.supportsSecondarySubtitleOffset,
                secondaryUnavailableReason =
                    if (backendExtensions.supportsSecondarySubtitleTrack) {
                        null
                    } else {
                        "当前播放管线仅支持单字幕；切换至 Exo、MPV 或 MDK 可启用副字幕。"
                    },
                offsetAvailable =
                    backendExtensions.supportsSubtitleOffset ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                scaleAvailable =
                    backendExtensions.supportsSubtitleScale ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                brightnessAvailable =
                    backendExtensions.supportsSubtitleBrightness ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                positionAvailable =
                    backendExtensions.supportsSubtitlePosition ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                appearanceAvailable =
                    backendExtensions.supportsSubtitleAppearance ||
                        (
                            choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                                !core2NativeOnlyActive
                        ),
                unavailableReason =
                    if (
                        choices.sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                    ) {
                        "调整后将自动切换到支持该功能的播放内核。"
                    } else if (core2NativeOnlyActive) {
                        "YCore Native 纯内核模式不允许兼容内核接管此项调节。"
                    } else {
                        "当前锁定内核不支持此项调节，请在播放内核中选择自动或 MPV。"
                    },
            ),
        subtitleActions =
            SubtitleControlActions(
                onSecondaryScale = { value ->
                    choices.subtitleControls =
                        choices.subtitleControls.copy(secondaryScale = value.coerceIn(0.6f, 1.8f))
                    rememberSeriesPlayback {
                        it.copy(
                            secondarySubtitleScale = choices.subtitleControls.secondaryScale,
                        )
                    }
                },
                onSwap = {
                    val primary = state.subtitleTracks.firstOrNull { it.selected }
                    val secondary =
                        state.subtitleTracks.firstOrNull { it.id == choices.secondarySubtitleTrackId }
                    if (primary != null && secondary != null) applySubtitlePair(secondary, primary)
                },
                onLanguagePair = { pair ->
                    val selected = selectDualSubtitleLanguagePair(state.subtitleTracks, pair)
                    if (selected == null) {
                        Toast.makeText(context, "当前视频缺少该语言组合的字幕", Toast.LENGTH_SHORT).show()
                    } else {
                        applySubtitlePair(selected.first, selected.second)
                    }
                },
                onOffset = {
                    choices.subtitleControls = choices.subtitleControls.copy(offsetMs = it)
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(subtitleOffsetMs = it)
                    }
                },
                onScale = {
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            scale = it,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleScale = it,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onBrightness = {
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            brightness = it,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleBrightness = it,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onPosition = {
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            position = it,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitlePosition = it,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onStylePreset = { preset ->
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            scale = preset.scale,
                            brightness = preset.brightness,
                            position = preset.position,
                            appearance = preset.appearance,
                            stylePreset = preset,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleScale = preset.scale,
                            subtitleBrightness = preset.brightness,
                            subtitlePosition = preset.position,
                            subtitleTextColorArgb = preset.appearance.textColorArgb,
                            subtitleBackgroundColorArgb = preset.appearance.backgroundColorArgb,
                            subtitleOutlineColorArgb = preset.appearance.outlineColorArgb,
                            subtitleOutlineWidth = preset.appearance.outlineWidth,
                            subtitleStylePreset = preset.name,
                        )
                    }
                },
                onTextColor = { color ->
                    val appearance = choices.subtitleControls.appearance.copy(textColorArgb = color)
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            appearance = appearance,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleTextColorArgb = color,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onBackgroundColor = { color ->
                    val appearance =
                        choices.subtitleControls.appearance.copy(
                            backgroundColorArgb = color,
                        )
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            appearance = appearance,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleBackgroundColorArgb = color,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onOutlineColor = { color ->
                    val appearance = choices.subtitleControls.appearance.copy(outlineColorArgb = color)
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            appearance = appearance,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleOutlineColorArgb = color,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onOutlineWidth = { width ->
                    val appearance = choices.subtitleControls.appearance.copy(outlineWidth = width)
                    choices.subtitleControls =
                        choices.subtitleControls.copy(
                            appearance = appearance,
                            stylePreset = SubtitleStylePreset.Custom,
                        )
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(
                            subtitleOutlineWidth = width,
                            subtitleStylePreset = SubtitleStylePreset.Custom.name,
                        )
                    }
                },
                onSecondaryOffset = { offset ->
                    if (backendExtensions.setSecondarySubtitleOffsetMs(offset)) {
                        choices.subtitleControls =
                            choices.subtitleControls.copy(secondaryOffsetMs = offset)
                        rememberSeriesPlayback { it.copy(secondarySubtitleOffsetMs = offset) }
                    }
                },
                onSecondaryTrack = secondary@{ id ->
                    if (id == EngineTrack.OFF) {
                        backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
                        choices.secondarySubtitleTrackId = null
                        choices.secondarySubtitleRestore = null
                        rememberSeriesPlayback { remembered ->
                            remembered.copy(secondarySubtitle = null)
                        }
                        return@secondary
                    }
                    val track =
                        state.subtitleTracks.firstOrNull { it.id == id }
                            ?: return@secondary
                    if (track.selected) {
                        Toast
                            .makeText(context, "主字幕和副字幕不能选择同一轨", Toast.LENGTH_SHORT)
                            .show()
                        return@secondary
                    }
                    if (!backendExtensions.selectSecondarySubtitleTrack(id)) {
                        Toast
                            .makeText(context, "当前播放器内核不支持副字幕", Toast.LENGTH_SHORT)
                            .show()
                        return@secondary
                    }
                    choices.handoverItemId = currentItem?.id
                    choices.secondarySubtitleTrackId = id
                    choices.secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
                    rememberSeriesPlayback { remembered ->
                        remembered.copy(secondarySubtitle = track.toRememberedPlaybackTrack())
                    }
                },
            ),
        remoteSubtitles = remoteSubtitles,
        remoteSubtitleActions = remoteSubtitleActions,
        onSpeed = { newSpeed ->
            choices.requestedPlaybackSpeed = newSpeed
            playbackGate.setSpeed(newSpeed)
            rememberSeriesPlayback { remembered -> remembered.copy(speed = newSpeed) }
        },
        gestures = gestureSettings,
        onSpeedBoost = { boost ->
            gestures.holdBoost(
                rate = boost,
                playbackRequested = { player.playbackRequested },
                play = playbackGate::play,
                pause = { playbackGate.pause() },
                locked = { playbackGate.locked },
            )
        },
        sleepTimer = SleepTimerState(sleepTimer.option),
        sleepTimerActions =
            SleepTimerActions(
                onSelect = { option ->
                    sleepTimer.select(
                        option = option,
                        currentIndex = state.currentIndex,
                        castSessionRevision =
                            castState.sessionRevision.takeIf { castState.hasActiveSession },
                    )
                },
            ),
        onToggleFill = { stretch ->
            choices.scaleMode = choices.scaleMode.toggled(stretch)
            backendExtensions.setVideoScaleMode(choices.scaleMode)
            rememberSeriesPlayback { remembered ->
                remembered.copy(aspectMode = choices.scaleMode.name)
            }
            Toast.makeText(context, "画面：${choices.scaleMode.label}", Toast.LENGTH_SHORT).show()
        },
        // 捏合填充 and F: the same state and series memory as the 画面 button, set to a mode
        // rather than cycled. The controls' HUD says which, so no toast.
        onSetFill = { fill ->
            val mode = if (fill) VideoScaleMode.Fill else VideoScaleMode.Fit
            if (choices.scaleMode != mode) {
                choices.scaleMode = mode
                backendExtensions.setVideoScaleMode(mode)
                rememberSeriesPlayback { remembered ->
                    remembered.copy(aspectMode = mode.name)
                }
            }
        },
        trickplay = currentTrickplay,
        // Readers, not values: read here, every step of a volume or brightness drag
        // recomposed the whole control surface.
        volume = { castState.volume?.takeIf { castState.hasActiveSession } ?: volumeLevel.value },
        onVolume = { requestedVolume ->
            if (castState.hasActiveSession) {
                scope.launch { castManager.setVolume(requestedVolume) }
            } else {
                setVolume(requestedVolume)
            }
        },
        volumeKeyPresses = volumeKeyPresses.collectAsState().value,
        brightness = { brightnessLevel.value },
        onBrightness = { setBrightness(it) },
        engineOptions =
            packagedEngineStrategies().map { selection ->
                val label =
                    selection.lockedEngine?.let { "本视频使用 ${it.label}" }
                        ?: "本视频跟随 YCore 智能策略"
                label to (selection == choices.sessionEngineSelection)
            },
        onSelectEngine = { index ->
            packagedEngineStrategies().getOrNull(index)?.let { selection ->
                selectEngineStrategy(selection)
                Toast
                    .makeText(context, "仅覆盖当前视频；全局播放策略未更改", Toast.LENGTH_SHORT)
                    .show()
            }
        },
        // Manual escape hatch when the picture is black but audio plays. Offered on
        // every engine now — it used to be ExoPlayer-only, which left the native
        // engines with no way out of a file the device can't decode.
        transcodeLabel =
            "转码播放".takeIf {
                !core2NativeOnlyActive &&
                    currentItem?.let { item ->
                        item.transcodeUrl.isNotBlank() || item.fallbackTranscodeUrl.isNotBlank()
                    } == true
            },
        transcodeActive = state.transcoding,
        onTranscode = {
            if (!core2NativeOnlyActive && !state.transcoding) {
                backendExtensions.switchToTranscode("用户手动选择服务器转码")
            }
        },
        onResetAdaptiveLearning = {
            failureMemory.clear()
            performanceMemory.clear()
            Toast
                .makeText(context, "YCore 学习数据已重置", Toast.LENGTH_SHORT)
                .show()
        },
        // A disc jump changes nothing the eye can read — the picture keeps playing and
        // the settings row is behind the finger. Name the destination the way the
        // aspect-ratio toggle names its mode, so the press is answered at all.
        onNextDiscTitle = {
            val disc = state.discNavigation
            if (disc.titleCount > 1) {
                val next = (disc.selectedTitleIndex + 1) % disc.titleCount
                if (backendExtensions.selectDiscTitle(next)) {
                    Toast
                        .makeText(context, discTitleToast(disc, next), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        },
        onNextDiscChapter = {
            val disc = state.discNavigation
            if (disc.chapterCount > 1) {
                val next = (disc.selectedChapterIndex + 1) % disc.chapterCount
                if (backendExtensions.selectDiscChapter(next)) {
                    Toast
                        .makeText(context, discChapterToast(disc, next), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        },
        onShowDiscMenu = {
            backendExtensions.showDiscMenu()
        },
        castDevices = castState.devices.map { it.id to it.name },
        castingDeviceId = castState.activeDeviceId,
        castDiscovering = castState.discovering,
        castError = castState.error,
        castStatus =
            castState.activeDevice?.let {
                "${it.name} · ${castState.status.label}"
            },
        // Connecting or live, as the app's status capsule counts it. A first load that failed
        // keeps its device with an error and no termination; that is not a cast in progress.
        castActive = castState.hasActiveSession || castState.status == CastPlaybackStatus.Connecting,
        castPositionSource = { castPositionLabel(liveCastState.value) },
        castCapabilities = castCapabilitiesLabel(castState),
        onDiscoverCast = requestCastDiscovery,
        onCastTo = { deviceId ->
            val item = activeItems.getOrNull(state.currentIndex) ?: return@PlayerControls
            scope.launch {
                if (loadCastItem(deviceId, state.currentIndex, livePlayback.value.positionMs)) {
                    quickCast.noteCast(deviceId)
                }
            }
        },
        onStopCast = {
            scope.launch {
                val handoffPosition =
                    if (castState.positionConfirmed) {
                        liveCastState.value.positionMs
                    } else {
                        liveLocalState.value.positionMs
                    }
                val resumeLocally = castState.lastRemoteWasPlaying
                if (castManager.stop()) {
                    player.seekTo(handoffPosition)
                    if (resumeLocally) player.play() else player.pause()
                }
            }
        },
        danmaku = danmaku.panelState,
        danmakuActions = danmaku.actions,
        danmakuHeat = danmaku.heat,
        // Only worth naming when there is more than one server to be on. On a
        // single-server install it is a constant, and a constant on a line meant
        // for live facts is noise.
        sourceLabel =
            currentItem
                ?.serverId
                ?.takeIf { serverNames.size > 1 }
                ?.let(serverNames::get),
        sourceOptions = sourceOptions,
        selectedSourceId = currentItem?.serverId,
        onSelectSource = selectServer,
        containerLabel = currentItem?.activeVersion?.container,
        dolbyVision =
            !state.transcoding &&
                state.diagnostics.hasActiveDolbyVisionOutput(),
        dolbyAtmos =
            !state.transcoding &&
                state.diagnostics.hasActiveDolbyAtmosOutput(),
        versions =
            currentItem?.versions.orEmpty().map { version ->
                version.id to
                    listOfNotNull(
                        version.label,
                        version.detail.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
            },
        selectedVersionId = currentItem?.versionId,
        onSelectVersion = { versionId -> selectVersion(versionId) },
        skip = skip.state,
        skipActions = skip.actions,
        chapters = currentItem?.chapters.orEmpty(),
        watch =
            WatchRoomState(
                available = watchAvailable,
                endpoint = watchEndpoint,
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
                chatPreviewEnabled = watchChatPreview,
                chatDanmakuEnabled = watchChatDanmaku.value,
                error = watchState.error ?: watchState.syncWarning,
                controlRequested = watchState.controlRequested,
                controlRequesterName = watchState.controlRequest?.name,
            ),
        watchActions =
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
                    watchTogetherPreferences.setChatDanmakuEnabled(!watchChatDanmaku.value)
                },
                onReact = { watchTogether.sendReaction(it) },
                onReactionFinished = watchTogether::clearReaction,
            ),
        remoteChrome = remoteChrome,
        hardwareKeyboard = hardwareKeyboardAttached(),
        extras = chromeExtras,
        wakeRequests = controlsWakeRequests,
        // Held back while a transition carries the picture in, and gone first on the way out.
        modifier =
            Modifier
                .graphicsLayer { alpha = transition?.chromeAlpha() ?: 1f }
                .danmakuPressWatch(danmakuPicker),
    )
}

/**
 * The per-video engine choices this package can honour, for the settings panel and the error
 * layer's alternatives alike. A native-only package, such as the television build, plays through
 * YCore whatever is locked: offering Exo or mpv only reloaded the same path to fail the same way.
 */
private fun packagedEngineStrategies(): List<PlaybackEngineSelection> =
    PlaybackEngineSelection.selectable.filter { !BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME || it.lockedEngine == null }
