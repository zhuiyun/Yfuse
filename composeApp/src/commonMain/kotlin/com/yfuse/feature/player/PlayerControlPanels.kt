package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.BackOverlay
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.Motion

// To 3×: a 短剧 viewer skims a plot-heavy episode at 2.5× or 3×; 2× was as fast as the menu went.
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/*
 * The panels, sheets and dialogs PlayerControls opens over the picture. Which of them is open is
 * [PlayerChromeState]'s; each closes itself through it. [controlState] is the controls' snapshot of
 * the playback, read through the same delegate and in the same places the controls read it.
 */

/**
 * The popups anchored bottom-right: the settings panel (字幕, 音轨, 弹幕, 投屏, 片头片尾, 更多), the
 * quick popup (线路, 倍速), and 手势说明.
 */
@Composable
internal fun BoxScope.PlayerSettingsLayers(
    chrome: PlayerChromeState,
    playback: State<PlaybackState>,
    controlState: State<PlaybackState>,
    gestureState: PlayerGestureState,
    watchLocked: Boolean,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    picture: PlayerPictureState,
    pictureActions: PlayerPictureActions,
    tracks: PlayerTrackState,
    trackActions: PlayerTrackActions,
    source: PlayerSourceState,
    sourceActions: PlayerSourceActions,
    panels: PlayerPanelState,
    panelActions: PlayerPanelActions,
    cast: PlayerCastState,
    castActions: PlayerCastActions,
    danmaku: PlayerDanmakuState,
    danmakuActions: DanmakuPanelActions,
    watch: WatchRoomState,
    reduceMotion: Boolean,
    /** 没听清 steps aside for a pick of the viewer's own, putting nothing back. */
    onEndSubtitlePeek: () -> Unit,
) {
    val state by controlState
    LaunchedEffect(transport.speedUnavailableReason) {
        if (transport.speedUnavailableReason != null && chrome.quickPopup == QuickPopup.Speed) {
            chrome.quickPopup = null
        }
    }
    // Every playback function popup uses the same bottom-right anchor. Content may be
    // shorter or taller, but switching buttons never makes the surface jump position.
    val functionPopupModifier =
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 18.dp, bottom = 70.dp)

    // The popovers and drawers below play their own way in and out; the presence only
    // keeps them composed while they do.
    PanelPresence(chrome.settingsPanelKind, modifier = Modifier.fillMaxSize()) { kind ->
        BackOverlay(
            onBack = { chrome.settingsPanelKind = null },
            enabled = chrome.settingsPanelKind != null,
        ) {
            SettingsPanel(
                modifier = functionPopupModifier,
                kind = kind,
                // The projection for everything the panel lists, and the live state
                // for the two pages that genuinely read a clock. Handing the whole
                // panel `playback.value` put twenty-five lines of diagnostics
                // formatting, and every list around them, on the position tick.
                state = state,
                playback = playback,
                containerLabel = source.containerLabel,
                engineOptions = source.engineOptions,
                transcodeLabel = source.transcodeLabel,
                transcodeActive = source.transcodeActive,
                castDevices = cast.devices,
                castingDeviceId = cast.deviceId,
                castDiscovering = cast.discovering,
                castError = cast.error,
                castStatus = cast.status,
                castPosition = cast.position,
                castPositionSource = cast.positionSource,
                castCapabilities = cast.capabilities,
                castTransport = cast.transport,
                danmaku = danmaku.panel,
                danmakuActions = danmakuActions,
                onOpenDanmakuSearch = {
                    chrome.settingsPanelKind = null
                    danmakuActions.onOpenSearch()
                    chrome.danmakuSearchOpen = true
                },
                onOpenDanmakuSend = {
                    chrome.settingsPanelKind = null
                    chrome.danmakuSendAwaited = false
                    chrome.danmakuSendTried = false
                    chrome.danmakuSendOpen = true
                },
                // Picking a track closes the panel, which used to be the only sign
                // anything had happened — and the picture behind it rarely says so
                // within the second. The HUD the gestures already use answers it.
                onSelectSubtitle = { id ->
                    // A pick is the viewer's own: 没听清 steps aside without putting anything back.
                    onEndSubtitlePeek()
                    trackActions.onSelectSubtitle(id)
                    gestureState.say("字幕 · ${trackLabel(state.subtitleTracks, id)}")
                    chrome.settingsPanelKind = null
                },
                subtitleControls = tracks.subtitles,
                subtitleActions = trackActions.subtitles,
                bookmarks = panels.bookmarks,
                bookmarkActions =
                    panelActions.bookmarks.copy(onSeek = { position ->
                        if (watchLocked) {
                            gestureState.say("房主控制播放")
                        } else {
                            transportActions.onSeek(
                                position.coerceIn(0L, state.durationMs.coerceAtLeast(0L)),
                            )
                            chrome.settingsPanelKind = null
                        }
                    }),
                remoteSubtitles = tracks.remoteSubtitles,
                remoteSubtitleActions = trackActions.remoteSubtitles,
                audioControls = tracks.audio,
                audioActions = trackActions.audio,
                onSelectAudio = { id ->
                    trackActions.onSelectAudio(id)
                    gestureState.say("音轨 · ${trackLabel(state.audioTracks, id)}")
                    chrome.settingsPanelKind = null
                },
                sleepTimer = panels.sleepTimer,
                sleepTimerActions = panelActions.sleepTimer,
                onSelectEngine = {
                    sourceActions.onSelectEngine(it)
                    chrome.settingsPanelKind = null
                },
                onTranscode = {
                    sourceActions.onTranscode()
                    chrome.settingsPanelKind = null
                },
                onResetAdaptiveLearning = {
                    sourceActions.onResetAdaptiveLearning()
                    chrome.settingsPanelKind = null
                },
                onNextDiscTitle = sourceActions.onNextDiscTitle,
                onNextDiscChapter = sourceActions.onNextDiscChapter,
                onShowDiscMenu = sourceActions.onShowDiscMenu,
                onExternalPlayer = sourceActions.onExternalPlayer,
                onDiscoverCast = castActions.onDiscover,
                onCastTo = castActions.onCastTo,
                onStopCast = castActions.onStop,
                onLock = chrome::lockScreen,
                onOpenGestureHelp = {
                    chrome.settingsPanelKind = null
                    chrome.gestureHelpOpen = true
                },
                watch = watch,
                onOpenWatchTogether = {
                    chrome.settingsPanelKind = null
                    chrome.watchDialogOpen = true
                },
                versions = source.versions,
                selectedVersionId = source.selectedVersionId,
                onSelectVersion = {
                    sourceActions.onSelectVersion(it)
                    chrome.settingsPanelKind = null
                },
                skip = transport.skip,
                // The panel stays open: setting a boundary is something you check against
                // the picture behind it, and often two of the three in one visit.
                skipActions = transportActions.skip,
                trackPanelMode = chrome.trackPanelMode,
                ambientLightEnabled = picture.ambientLightEnabled,
                onToggleAmbientLight = pictureActions.onToggleAmbientLight,
                autoNextEnabled = transport.autoNext,
                onToggleAutoNext = transportActions.onToggleAutoNext,
                shortDramaMode = panels.shortDramaMode,
                onSelectShortDramaMode = panelActions.onSelectShortDramaMode,
                onDismiss = { chrome.settingsPanelKind = null },
            )
        }
    }

    PanelPresence(chrome.quickPopup, modifier = Modifier.fillMaxSize()) { popup ->
        BackOverlay(onBack = { chrome.quickPopup = null }, enabled = chrome.quickPopup != null) {
            // 线路 and 倍速 share this anchor and this shell, so going from one to the
            // other is a change of contents rather than of surface: the panel stays
            // where it is and settles into the new list's height instead of being
            // replaced by a differently-sized one in a single frame.
            AnimatedContent(
                targetState = popup,
                contentKey = { it },
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else Motion.STATE_HANDOFF
                    (
                        fadeIn(tween(duration, easing = Motion.Curve)) togetherWith
                            fadeOut(tween(duration, easing = Motion.Curve))
                    ).using(
                        SizeTransform(clip = false) { _, _ ->
                            if (reduceMotion) snap() else Motion.settle()
                        },
                    )
                },
                contentAlignment = Alignment.BottomEnd,
                modifier = functionPopupModifier,
                label = "player-quick-popup",
            ) { current ->
                when (current) {
                    QuickPopup.Source ->
                        SourcePickerPopup(
                            options = source.sourceOptions,
                            selectedId = source.selectedSourceId,
                            onSelect = {
                                sourceActions.onSelectSource(it)
                                chrome.quickPopup = null
                            },
                            onDismiss = { chrome.quickPopup = null },
                        )

                    QuickPopup.Speed ->
                        SpeedPickerPopup(
                            speeds = SPEEDS,
                            selectedSpeed = state.speed,
                            onSelect = {
                                if (transport.speedUnavailableReason == null && !watchLocked && !cast.active) {
                                    transportActions.onSpeed(it)
                                }
                                chrome.quickPopup = null
                            },
                            onDismiss = { chrome.quickPopup = null },
                        )
                }
            }
        }
    }

    if (chrome.gestureHelpOpen) {
        PlayerGestureHelpOverlay(
            onDismiss = { chrome.gestureHelpOpen = false },
            gestures = picture.gestures,
        )
    }
}

/**
 * 一起看 over the picture: the room dialog, the chat panel and the preview it replaces, and a
 * request for control from someone in the room.
 */
@Composable
internal fun BoxScope.PlayerWatchLayers(
    chrome: PlayerChromeState,
    watch: WatchRoomState,
    /** The room's callbacks, one instance for the session; see PlayerControls. */
    room: WatchRoomActions,
    /** Closes the chat with what has been read; stable for the life of the panel. */
    closeWatchChat: () -> Unit,
    hintProgress: State<Float>,
) {
    if (chrome.watchDialogOpen) {
        WatchTogetherDialog(
            endpoint = watch.endpoint,
            connecting = watch.connecting,
            connected = watch.connected,
            roomCode = watch.roomCode,
            isHost = watch.isHost,
            canControl = watch.canControl,
            controlMode = watch.controlMode,
            participantCount = watch.participantCount,
            participants = watch.participants,
            error = watch.error,
            controlRequested = watch.controlRequested,
            onCreate = room.onCreate,
            onJoin = room.onJoin,
            onLeave = {
                room.onLeave()
                chrome.watchDialogOpen = false
            },
            onRequestControl = room.onRequestControl,
            onSetControlMode = room.onSetControlMode,
            onSetModerator = room.onSetModerator,
            onKickParticipant = room.onKickParticipant,
            onDismiss = { chrome.watchDialogOpen = false },
        )
    }

    PanelPresence(
        Unit.takeIf { chrome.watchChatOpen && watch.connected },
        modifier = Modifier.fillMaxSize(),
    ) {
        BackOverlay(
            onBack = closeWatchChat,
            enabled = chrome.watchChatOpen,
        ) {
            WatchChatPanel(
                participants = watch.participants,
                messages = watch.chatMessages,
                error = watch.chatError,
                sendingEnabled = !watch.reconnecting,
                danmakuEnabled = watch.chatDanmakuEnabled,
                onSend = room.onSendChat,
                onRetry = room.onRetryChat,
                onClearError = room.onClearChatError,
                onToggleDanmaku = room.onToggleChatDanmaku,
                onDismiss = closeWatchChat,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    // The preview is what the panel replaces, so it is gated on the panel being shut
    // rather than chained to it — an `else` here would have torn the preview down on the
    // frame the panel started opening, before either had moved.
    ChromeVisibility(
        visible = !chrome.watchChatOpen && chrome.chatPreviewVisible && watch.chatMessages.isNotEmpty(),
        edge = ChromeEdge.Top,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .playerHintOffset(hintProgress, 52.dp)
                .padding(top = 18.dp, end = 22.dp),
    ) {
        WatchChatPreview(
            messages = watch.chatMessages,
            onOpen = { chrome.openWatchChat(watch.chatMessages) },
        )
    }

    // Outside `watchDialogOpen` on purpose: a request arrives when the asker taps, not
    // when the host happens to have the room dialog open, and an unanswered one leaves
    // that person waiting on a prompt nobody ever sees.
    watch.controlRequesterName?.let { requester ->
        ControlRequestDialog(
            requesterName = requester,
            onGrant = room.onGrantControl,
            // Dismissing is an answer too. Closing without one would leave the asker
            // waiting indefinitely, which is what `denyControl` exists to avoid.
            onDeny = room.onDenyControl,
        )
    }
}

/** The sheets over the picture: the episode strip, 弹幕's search, and 发弹幕. */
@Composable
internal fun BoxScope.PlayerSheetLayers(
    chrome: PlayerChromeState,
    controlState: State<PlaybackState>,
    gestureState: PlayerGestureState,
    watchLocked: Boolean,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    danmaku: PlayerDanmakuState,
    danmakuActions: DanmakuPanelActions,
    /** A remote drives the player: the strip takes focus on the episode playing as it opens. */
    takeFocus: Boolean = false,
) {
    val state by controlState
    ChromeVisibility(
        visible = chrome.drawerOpen,
        edge = ChromeEdge.Bottom,
        modifier = Modifier.fillMaxSize(),
    ) {
        BackOverlay(
            enabled = chrome.drawerOpen,
            onBack = { chrome.drawerOpen = false },
        ) {
            EpisodeStrip(
                episodes = transport.episodes,
                currentIndex = state.currentIndex,
                onSelect =
                    if (watchLocked) {
                        // Guests can still browse what's in the room's queue; picking is the
                        // host's move, so tapping explains itself instead of doing nothing.
                        { gestureState.say("房主控制播放") }
                    } else {
                        {
                            transportActions.onSelectItem(it)
                            chrome.drawerOpen = false
                        }
                    },
                onDismiss = { chrome.drawerOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
                takeFocus = takeFocus,
            )
        }
    }

    PanelPresence(Unit.takeIf { chrome.danmakuSearchOpen }, modifier = Modifier.fillMaxSize()) {
        BackOverlay(
            enabled = chrome.danmakuSearchOpen,
            onBack = { chrome.danmakuSearchOpen = false },
        ) {
            DanmakuSearchPanel(
                state = danmaku.panel,
                // Picking closes the sheet: the choice is made, and the result of it is
                // the 弹幕 now running over the picture the sheet is covering.
                actions =
                    danmakuActions.copy(
                        onPickEpisode = {
                            danmakuActions.onPickEpisode(it)
                            chrome.danmakuSearchOpen = false
                        },
                    ),
                onDismiss = { chrome.danmakuSearchOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    // Held through its exit: it closes itself once its line has gone through (see PlayerControls).
    DialogPresence(Unit.takeIf { chrome.danmakuSendOpen }) {
        DanmakuSendDialog(
            sending = danmaku.panel.sending,
            error = danmaku.panel.sendError.takeIf { chrome.danmakuSendTried },
            onSend = { text ->
                // One line at a time: the keyboard's send key does not wait out 发送中… the way the
                // button does.
                if (!chrome.danmakuSendAwaited && !danmaku.panel.sending) {
                    chrome.danmakuSendAwaited = true
                    chrome.danmakuSendTried = true
                    danmakuActions.onSend(text)
                }
            },
            onDismiss = { chrome.danmakuSendOpen = false },
        )
    }
}
