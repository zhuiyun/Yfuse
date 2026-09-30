package com.yfuse.feature.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.DragAxis
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalTips
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Tips
import com.yfuse.core.designsystem.rememberScreenReaderActive
import com.yfuse.tv.player.TvPlayerChromeCommandType
import com.yfuse.tv.player.TvPlayerChromeLayer
import com.yfuse.tv.player.TvPlayerChromePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 5_000L
private const val CHAT_PREVIEW_MS = 4_000L
private const val GESTURE_HUD_MS = 1_600L

/** How long the lock and its key stay on the picture after locking or after a touch on it. */
private const val LOCKED_CONTROLS_MS = 3_000L

/** How long 回到 stays offered after a held scan lets go. */
private const val SCAN_UNDO_MS = 3_000L

/** How long 跳过片头 / 跳过片尾 stays up on its own once playback enters the segment. */
private const val MANUAL_SKIP_STANDALONE_MS = 6_000L

/**
 * How long the volume slider stays up after the last press or drag.
 *
 * Shorter than [AUTO_HIDE_MS]: it covers part of the picture and answers a question that
 * has already been answered by the time the sound changes.
 */
private const val VOLUME_SLIDER_HIDE_MS = 1_600L

/**
 * What a just-picked track is called, for the acknowledgement HUD.
 *
 * The list is this frame's, so a track that has already gone — a downloaded subtitle replaced by
 * the next search — still gets a name rather than an id. 关闭 is not in any list and never will be.
 */
internal fun trackLabel(
    tracks: List<EngineTrack>,
    id: String,
): String =
    when {
        id == EngineTrack.OFF -> "关闭"
        else -> tracks.firstOrNull { it.id == id }?.label ?: "已切换"
    }

/**
 * True once the current item has finished and stands on its last frame.
 *
 * [PlaybackState.ended] says so for most engines. ExoPlayer told to stop at the end of a queue item
 * — 自动播放下一集 off, 取消 on the next-up card, 睡眠定时's 本集结束 — does not end it: it pauses on
 * the final frame with the next item still queued, and resuming runs straight into that one. To
 * whoever is watching, that pause is the end of the episode, not a pause in the middle of it.
 */
internal fun playbackStoppedAtItemEnd(state: PlaybackState): Boolean =
    state.error == null &&
        (
            state.ended ||
                (
                    state.hasNext &&
                        !state.playing &&
                        !state.buffering &&
                        state.durationMs > 0L &&
                        state.remainingMs <= ITEM_END_SLACK_MS
                )
        )

/** A queue item parked this close to its end has, for the viewer, ended. */
private const val ITEM_END_SLACK_MS = 1_000L

/**
 * The manual 跳过片头 / 跳过片尾 pill: up on its own for the first seconds after playback enters the
 * segment ([segmentJustEntered]) whatever the controls are doing, and with the controls after that.
 * Never while an automatic skip counts down; that pill speaks for the segment then.
 */
internal fun shouldShowManualSkipPill(
    segmentLabel: String?,
    countdownSeconds: Int?,
    controlsVisible: Boolean,
    segmentJustEntered: Boolean,
): Boolean = segmentLabel != null && countdownSeconds == null && (controlsVisible || segmentJustEntered)

/**
 * Settings use one consistent floating panel and one consistent chip family.
 *
 * The player chrome, transcribed from the prototype's landscape player: a gradient
 * top bar, a centred transport cluster, a gradient bottom bar with the scrubber and
 * chip row, plus the lock screen, settings panel and episode drawer.
 *
 * Everything shown comes from [playback], so ExoPlayer and libmpv get the same controls. The rest
 * arrives by area — the transport, the picture, tracks, the source, 更多's pages, 投屏, 弹幕, 一起看
 * and what the player root lends the chrome — each as a state snapshot and its callbacks; see
 * PlayerControlAreas.kt.
 */
@Composable
internal fun PlayerControls(
    playback: State<PlaybackState>,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    picture: PlayerPictureState,
    pictureActions: PlayerPictureActions,
    tracks: PlayerTrackState = PlayerTrackState(),
    trackActions: PlayerTrackActions,
    source: PlayerSourceState = PlayerSourceState(),
    sourceActions: PlayerSourceActions = PlayerSourceActions(),
    panels: PlayerPanelState = PlayerPanelState(),
    panelActions: PlayerPanelActions = PlayerPanelActions(),
    cast: PlayerCastState = PlayerCastState(),
    castActions: PlayerCastActions = PlayerCastActions(),
    danmaku: PlayerDanmakuState = PlayerDanmakuState(),
    danmakuActions: DanmakuPanelActions = DanmakuPanelActions(),
    watch: WatchRoomState = WatchRoomState(),
    watchActions: WatchRoomActions = WatchRoomActions(),
    host: PlayerChromeHost,
    modifier: Modifier = Modifier,
) {
    val remoteChrome = host.remoteChrome
    val skip = transport.skip
    val skipActions = transportActions.skip
    val currentSystemGestureTop by rememberUpdatedState(picture.systemGestureTopPx)
    val latestExtras by rememberUpdatedState(host.extras)
    // Held as State as well, for the layers below: they read it where the controls do.
    val controlState = rememberPlayerControlSnapshot(playback)
    val state by controlState
    // What the chrome has up and open; see [PlayerChromeState].
    val chrome = remember { PlayerChromeState() }
    var ambientChromeCount by remember { mutableIntStateOf(0) }
    val latestAmbientVisibility by rememberUpdatedState(pictureActions.onAmbientChromeVisibleChange)
    val ambientPresenceChanged =
        remember {
            { present: Boolean -> ambientChromeCount += if (present) 1 else -1 }
        }
    val ambientChromeVisible = ambientChromeCount > 0
    SideEffect { latestAmbientVisibility(ambientChromeVisible) }
    DisposableEffect(Unit) {
        onDispose { latestAmbientVisibility(false) }
    }
    val hintProgress = rememberPlayerHintProgress(chrome.visible)
    // 键盘快捷键 on a phone, tablet or Chromebook; TV's remote has its own controller.
    val keyboardShortcuts = host.hardwareKeyboard && remoteChrome == null
    val keyboardAnchor = remember { FocusRequester() }
    val keyboard = remember { PlayerKeyboardShortcuts() }
    var keyboardAnchorFocused by remember { mutableStateOf(false) }
    // Focus anywhere in the player, against focus on a control: the keyboard anchor holding it is
    // nobody moving through the controls, and must not keep them up.
    var focusInside by remember { mutableStateOf(false) }
    val anchorFocused = keyboardShortcuts && keyboardAnchorFocused
    val controlsHaveFocus = focusInside && !anchorFocused
    // The picture's gestures and the HUD that answers them. This body reads only what the whole
    // screen answers to — a side held, a finger on the rail — never a sample's value.
    val gestureState = rememberPlayerGestureState()
    // 双击's pulse counts from each item's start, so the next item never replays the last one's.
    val pulseItemStart = remember(state.currentIndex) { gestureState.pulseRevision }
    val holdScanStepPx = with(LocalDensity.current) { HoldScanGearStep.toPx() }
    // The app's own vocabulary, not Compose's two-constant one. These two call sites were
    // the last `HapticFeedbackType.LongPress` standing in for something it is not — a
    // confirmed scrub and a refused one, played identically. [HapticSignal.Reject] existed
    // for exactly the locked case and had never been called from anywhere.
    val haptics = LocalHaptics.current
    val tips = LocalTips.current
    // Read once for the whole surface: several transitions below have to collapse together.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val accessibilityManager = LocalAccessibilityManager.current
    // From Android 10 the recommended timeout only follows 操作时长, so TalkBack users still lost
    // the controls after five seconds — and a hidden chrome is not something a spoken cursor finds.
    val screenReaderActive = rememberScreenReaderActive()
    val latestPosition by remember(playback) { derivedStateOf { playback.value.positionMs } }
    val latestDuration by rememberUpdatedState(state.durationMs)
    val latestVolume by rememberUpdatedState(picture.volume)
    val latestBrightness by rememberUpdatedState(picture.brightness)
    val latestOnSeek by rememberUpdatedState(transportActions.onSeek)
    val latestOnPlayPause by rememberUpdatedState(transportActions.onPlayPause)
    val latestOnVolume by rememberUpdatedState(pictureActions.onVolume)
    val latestOnBrightness by rememberUpdatedState(pictureActions.onBrightness)
    // Timeline controls (play/pause, seek, episode, speed) are read-only for a connected
    // non-host: the room's host drives them, this device only follows. Volume, brightness,
    // subtitle/audio track, aspect ratio, cast and danmaku stay untouched by this — those
    // are per-viewer, not shared.
    val watchLocked = watch.locked
    val latestWatchLocked by rememberUpdatedState(watchLocked)
    // Read by the long-lived gesture detector, which would otherwise keep its first frame's values.
    val latestWatchConnected by rememberUpdatedState(watch.connected)
    val latestCasting by rememberUpdatedState(cast.deviceId != null)
    val latestOnSpeedBoost by rememberUpdatedState(transportActions.onSpeedBoost)
    val latestGestures by rememberUpdatedState(picture.gestures)
    val latestFilled by rememberUpdatedState(picture.scaleMode != VideoScaleMode.Fit)
    val latestOnSetFill by rememberUpdatedState(pictureActions.onSetFill)
    val remoteChromeState = remoteChrome?.state?.collectAsState()?.value
    LaunchedEffect(remoteChromeState?.seekTargetMs, remoteChromeState?.seeking) {
        val target = remoteChromeState?.seekTargetMs ?: return@LaunchedEffect
        if (remoteChromeState.seeking) {
            gestureState.say("跳转 ${target.asClock()} / ${state.durationMs.asClock()}")
        }
    }

    LaunchedEffect(watch.available) {
        if (!watch.available) {
            chrome.watchDialogOpen = false
            chrome.watchChatOpen = false
        }
    }

    // One instance of the room's callbacks for the whole session, forwarding to whatever the
    // caller last passed.
    //
    // This chrome recomposes on every position tick — twice a second on mpv — and the caller
    // builds a fresh [WatchRoomActions] each time, which is a fresh set of lambdas. Handed
    // straight down they are never equal to last frame's, so nothing below could skip: the
    // chat transcript rebuilt every visible bubble, and every gradient inside them, several
    // times a second. That is exactly as often as scrolling it stuttered. The panels below
    // now see the same callbacks they saw last frame and skip when nothing else changed.
    val latestWatchActions by rememberUpdatedState(watchActions)
    val room =
        remember {
            WatchRoomActions(
                onCreate = { latestWatchActions.onCreate(it) },
                onJoin = { endpoint, code -> latestWatchActions.onJoin(endpoint, code) },
                onLeave = { latestWatchActions.onLeave() },
                onRequestControl = { latestWatchActions.onRequestControl() },
                onGrantControl = { latestWatchActions.onGrantControl() },
                onDenyControl = { latestWatchActions.onDenyControl() },
                onSendChat = { latestWatchActions.onSendChat(it) },
                onRetryChat = { latestWatchActions.onRetryChat(it) },
                onClearChatError = { latestWatchActions.onClearChatError() },
                onToggleChatDanmaku = { latestWatchActions.onToggleChatDanmaku() },
                onReact = { latestWatchActions.onReact(it) },
                onReactionFinished = { latestWatchActions.onReactionFinished(it) },
                onSetControlMode = { latestWatchActions.onSetControlMode(it) },
                onSetModerator = { id, on -> latestWatchActions.onSetModerator(id, on) },
                onKickParticipant = { latestWatchActions.onKickParticipant(it) },
            )
        }

    /** Why a hold may not speed playback up at this moment; null when it may. */
    fun currentSpeedBoostRefusal(): SpeedBoostRefusal? =
        speedBoostRefusal(
            panelOpen =
                chrome.watchChatOpen ||
                    chrome.danmakuSendOpen ||
                    chrome.danmakuSearchOpen ||
                    chrome.quickPopup != null ||
                    chrome.settingsPanelKind != null ||
                    chrome.drawerOpen,
            watchGuest = latestWatchLocked,
            watchRoom = latestWatchConnected,
            casting = latestCasting,
            durationMs = latestDuration,
            finished = state.ended || state.error != null,
        )

    /**
     * Starts 临时倍速 at 2× for a held middle — or a held side, unless 两侧长按 · 扫描 — false when it
     * may not, having said why where there is a reason.
     */
    fun startSpeedBoost(originX: Float): Boolean {
        val refusal = currentSpeedBoostRefusal()
        if (refusal != null) {
            refusal.message?.let { message ->
                gestureState.say(message)
                haptics.play(HapticSignal.Reject)
            }
            return false
        }
        latestOnSpeedBoost(gestureState.startBoost(originX))
        // The point of holding is to watch: the chrome steps aside and only the pill stays up.
        chrome.visible = false
        haptics.play(HapticSignal.Confirm)
        tips?.markUsed(Tips.PLAYER_CENTER_HOLD)
        return true
    }

    /** Lets go of 长按中间; nothing to do when no boost is held. */
    fun endSpeedBoost() {
        if (gestureState.endBoost()) latestOnSpeedBoost(null)
    }

    // 没听清: the subtitle a held ⟲10 brought up for the replay, until the line has been heard.
    var subtitlePeek by remember { mutableStateOf<SubtitlePeek?>(null) }
    val latestOnEndSubtitlePeek by rememberUpdatedState(trackActions.onEndSubtitlePeek)

    fun endSubtitlePeek(restoreTrackId: String?) {
        if (subtitlePeek == null) return
        subtitlePeek = null
        latestOnEndSubtitlePeek(restoreTrackId)
    }

    fun rewindMissedLine() {
        tips?.markUsed(Tips.PLAYER_MISSED_LINE)
        // The key is dimmed for a guest already; the rewind would be the room's, not theirs.
        if (latestWatchLocked) {
            gestureState.say("房主控制播放")
            return
        }
        val live = playback.value
        val plan =
            missedLineRewind(
                positionMs = live.positionMs,
                subtitleTracks = live.subtitleTracks,
                audioTracks = live.audioTracks,
                secondarySubtitleTrackId = live.secondarySubtitleTrackId ?: tracks.subtitles.secondaryTrackId,
                running = subtitlePeek,
                subtitlesAllowed = cast.deviceId == null,
            )
        latestOnSeek(plan.targetMs)
        plan.peek?.takeIf { subtitlePeek == null }?.let(trackActions.onPeekSubtitle)
        subtitlePeek = plan.peek
        gestureState.say(plan.message)
        chrome.poke()
    }

    /** The player as a key press finds it; read at the press, never kept. */
    fun keyContext(): PlayerKeyContext {
        val live = playback.value
        val frames =
            transport.trickplay
                ?.takeIf { live.durationMs > 0L }
                ?.let { SeekFilmstripFrames(it, live.durationMs) }
                ?.takeIf { it.count > 1 }
        return PlayerKeyContext(
            watchGuest = latestWatchLocked,
            playing = live.playing,
            positionMs = live.positionMs,
            durationMs = live.durationMs,
            stepMs = latestGestures.doubleTapSeekMs,
            previousFrameMs = frames?.let { filmstripStepTargetMs(it, live.positionMs, -1) },
            nextFrameMs = frames?.let { filmstripStepTargetMs(it, live.positionMs, 1) },
        )
    }

    /** 键盘快捷键, done: the same callbacks the gestures use, and the same HUD to say so. */
    fun performKeyAction(action: PlayerKeyAction) {
        when (action) {
            PlayerKeyAction.TogglePlay -> {
                gestureState.say(if (playback.value.playing) "暂停" else "播放")
                latestOnPlayPause()
            }
            is PlayerKeyAction.Seek -> {
                latestOnSeek(action.targetMs)
                gestureState.say(action.message)
            }
            PlayerKeyAction.ToggleFill -> {
                val fill = !latestFilled
                latestOnSetFill(fill)
                gestureState.say(pinchFillMessage(fill))
            }
            PlayerKeyAction.ToggleMute -> {
                val mute = muteToggle(latestVolume(), keyboard.mutedFrom)
                keyboard.mutedFrom = mute.restoreTo
                latestOnVolume(mute.volume)
                gestureState.say(mute.message)
            }
            is PlayerKeyAction.Say -> gestureState.say(action.message)
            PlayerKeyAction.Pass -> Unit
        }
    }

    // A double tap, a hold, a tap on 长按解锁 or the back gesture while locked: refused out loud,
    // and the way out shown instead of the thing asked for.
    fun refuseWhileLocked() {
        haptics.play(HapticSignal.Reject)
        chrome.revealLock(explain = true)
    }

    // Also stable for the life of the panel, and for the same reason: read through the
    // transcript rather than closing over this frame's copy of it.
    val latestChatMessages by rememberUpdatedState(watch.chatMessages)
    val closeWatchChat = remember { { chrome.closeWatchChat(latestChatMessages) } }

    fun closeTopRemoteLayer() {
        when {
            chrome.danmakuSendOpen -> chrome.danmakuSendOpen = false
            chrome.danmakuSearchOpen -> chrome.danmakuSearchOpen = false
            chrome.drawerOpen -> chrome.drawerOpen = false
            watch.controlRequesterName != null -> room.onDenyControl()
            chrome.watchChatOpen -> closeWatchChat()
            chrome.watchDialogOpen -> chrome.watchDialogOpen = false
            chrome.gestureHelpOpen -> chrome.gestureHelpOpen = false
            chrome.quickPopup != null -> chrome.quickPopup = null
            chrome.settingsPanelKind != null -> chrome.settingsPanelKind = null
            chrome.locked -> chrome.locked = false
            chrome.visible -> chrome.visible = false
        }
    }

    val resumeNotice =
        rememberResumeNotice(
            resumedFromMs = transport.resumedFromMs,
            itemIndex = state.currentIndex,
            ready = state.playing && !state.buffering && state.error == null,
            controlsVisible = chrome.visible,
            interrupted =
                chrome.interactions > 0 ||
                    chrome.locked ||
                    watchLocked ||
                    chrome.settingsPanelKind != null ||
                    chrome.quickPopup != null ||
                    chrome.drawerOpen ||
                    chrome.watchChatOpen ||
                    chrome.danmakuSearchOpen ||
                    chrome.danmakuSendOpen ||
                    chrome.gestureHelpOpen ||
                    chrome.watchDialogOpen ||
                    state.error != null,
        )

    val remotePanel =
        when {
            chrome.danmakuSendOpen -> TvPlayerChromePanel.DanmakuSend
            chrome.danmakuSearchOpen -> TvPlayerChromePanel.DanmakuSearch
            chrome.drawerOpen -> TvPlayerChromePanel.Episodes
            watch.controlRequesterName != null -> TvPlayerChromePanel.ControlRequest
            chrome.watchChatOpen -> TvPlayerChromePanel.WatchChat
            chrome.watchDialogOpen -> TvPlayerChromePanel.WatchTogether
            chrome.gestureHelpOpen -> TvPlayerChromePanel.GestureHelp
            chrome.quickPopup != null -> TvPlayerChromePanel.QuickPicker
            chrome.settingsPanelKind != null -> TvPlayerChromePanel.Settings
            else -> null
        }
    val remoteLayer =
        when {
            chrome.locked -> TvPlayerChromeLayer.Locked
            remotePanel != null -> TvPlayerChromeLayer.Panel
            chrome.visible -> TvPlayerChromeLayer.Controls
            else -> TvPlayerChromeLayer.Hidden
        }
    val latestRemotePanel by rememberUpdatedState(remotePanel)
    val latestRemoteLocked by rememberUpdatedState(chrome.locked)
    val latestCloseTopRemoteLayer by rememberUpdatedState { closeTopRemoteLayer() }
    val latestSkip by rememberUpdatedState(skip)
    val latestSkipActions by rememberUpdatedState(skipActions)

    LaunchedEffect(remoteChrome) {
        remoteChrome?.commands?.collect { command ->
            when (command.type) {
                TvPlayerChromeCommandType.ShowControls -> chrome.poke()
                TvPlayerChromeCommandType.HideControls -> {
                    if (latestRemotePanel == null && !latestRemoteLocked) chrome.visible = false
                }
                TvPlayerChromeCommandType.CloseTop -> latestCloseTopRemoteLayer()
                TvPlayerChromeCommandType.OpenTracks -> chrome.openSettingsPanel(SettingsPanelKind.Tracks)
                TvPlayerChromeCommandType.OpenInfo -> chrome.openSettingsPanel(SettingsPanelKind.More)
                // What a tap on the pill does, without the tap's reveal: OK over the picture means
                // "get on with the film", not "show me the controls".
                TvPlayerChromeCommandType.ActivateSkipPrompt -> {
                    if (latestSkip.countdownSeconds != null) {
                        latestSkipActions.onCancelAuto()
                    } else if (latestSkip.segmentLabel != null) {
                        latestSkipActions.onSkip()
                    }
                }
                // The end-of-episode cards live further down; their own collector answers these.
                TvPlayerChromeCommandType.ActivateNextUp,
                TvPlayerChromeCommandType.DismissNextUp,
                -> Unit
            }
        }
    }
    LaunchedEffect(remoteChrome, remoteLayer, remotePanel, controlsHaveFocus) {
        remoteChrome?.publishUiState(
            layer = remoteLayer,
            panel = remotePanel,
            controlsHaveFocus = controlsHaveFocus,
        )
    }
    // With a keyboard and nothing in the player focused — its usual state — the anchor takes focus so
    // the shortcuts have somewhere to land. Anything that asks gets it back: Tab, a panel, a field.
    LaunchedEffect(keyboardShortcuts, focusInside, remotePanel) {
        if (keyboardShortcuts && !focusInside && remotePanel == null) {
            runCatching { keyboardAnchor.requestFocus() }
        }
    }

    // Leaving composition (picture-in-picture) hands the remote back to ordinary dispatch: a stale
    // "controls are up" would otherwise swallow OK and Back with nothing collecting the commands.
    DisposableEffect(remoteChrome) {
        onDispose { remoteChrome?.detach() }
    }
    // A remote has no pointer. The controls used to arrive with nothing focused, so the first OK fell
    // through to play/pause and the first arrow landed wherever focus search began. Whenever they are
    // up with focus nowhere — just raised, a panel closed, the focused key swapped between 播放 and
    // 暂停 — the transport key takes it. A key the viewer has moved to is never taken over.
    val playKeyFocus = remember { FocusRequester() }
    LaunchedEffect(remoteChrome, remoteLayer, controlsHaveFocus) {
        if (remoteChrome == null || remoteLayer != TvPlayerChromeLayer.Controls || controlsHaveFocus) {
            return@LaunchedEffect
        }
        // The bar is composed with the layer; let it attach before asking.
        repeat(2) { withFrameNanos { } }
        runCatching { playKeyFocus.requestFocus() }
    }

    LaunchedEffect(
        chrome.visible,
        chrome.locked,
        chrome.settingsPanelKind,
        chrome.quickPopup,
        chrome.drawerOpen,
        chrome.danmakuSearchOpen,
        chrome.danmakuSendOpen,
        chrome.watchChatOpen,
        state.playing || (state.buffering && state.positionMs > 0L),
        chrome.interactions,
        accessibilityManager,
        controlsHaveFocus,
        screenReaderActive,
        gestureState.scrubbing,
    ) {
        val overlayOpen =
            chrome.gestureHelpOpen ||
                chrome.settingsPanelKind != null ||
                chrome.quickPopup != null ||
                chrome.drawerOpen ||
                chrome.danmakuSearchOpen ||
                chrome.danmakuSendOpen ||
                chrome.watchChatOpen
        // Keep the hide timer stable across NativeDirect's playing <-> buffering handoff.
        // A remote Range stall is still an active playback request, not a user pause.
        // During initial startup we wait until playback has actually advanced or rendered.
        val playbackActive = state.playing || (state.buffering && state.positionMs > 0L)
        if (
            !chrome.visible ||
            !playbackActive ||
            overlayOpen ||
            // Focus holds the controls up for a keyboard, which has no other way to keep them. A
            // remote restarts this timer with every key it sends (ShowControls pokes), so there a
            // focused key alone must not park the controls over the picture for good.
            (controlsHaveFocus && remoteChrome == null) ||
            screenReaderActive ||
            gestureState.scrubbing
        ) {
            return@LaunchedEffect
        }
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = AUTO_HIDE_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: AUTO_HIDE_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        chrome.visible = false
    }
    // Watched from the coroutine rather than keyed: a drag rewrites the HUD on every move, and a key
    // read here would recompose this whole tree once a frame for as long as the finger moved. Each
    // new reading restarts the clock, as a key change did.
    LaunchedEffect(accessibilityManager) {
        snapshotFlow { gestureState.hud }.collectLatest { reading ->
            if (reading == null) return@collectLatest
            val timeout =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = GESTURE_HUD_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = false,
                ) ?: GESTURE_HUD_MS
            if (timeout == Long.MAX_VALUE) return@collectLatest
            delay(timeout)
            gestureState.say(null)
        }
    }
    LaunchedEffect(
        watch.roomCode,
        watch.chatMessages.lastOrNull()?.id,
        chrome.watchChatOpen,
        watch.chatPreviewEnabled,
        accessibilityManager,
    ) {
        val latestId = watch.chatMessages.lastOrNull()?.id
        if (chrome.previewRoomCode != watch.roomCode) {
            chrome.previewRoomCode = watch.roomCode
            chrome.lastPreviewedChatId = latestId
            chrome.lastReadChatId = latestId
            chrome.chatPreviewVisible = false
            if (!watch.connected) chrome.watchChatOpen = false
            return@LaunchedEffect
        }
        if (!watch.connected) {
            chrome.watchChatOpen = false
            chrome.chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (!watch.chatPreviewEnabled) {
            chrome.lastPreviewedChatId = latestId
            chrome.chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (chrome.watchChatOpen) {
            chrome.lastReadChatId = latestId
            chrome.lastPreviewedChatId = latestId
            chrome.chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (watch.chatPreviewEnabled && latestId != null && latestId != chrome.lastPreviewedChatId) {
            chrome.lastPreviewedChatId = latestId
            chrome.chatPreviewVisible = true
            val timeout =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = CHAT_PREVIEW_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = false,
                ) ?: CHAT_PREVIEW_MS
            if (timeout == Long.MAX_VALUE) return@LaunchedEffect
            delay(timeout)
            chrome.chatPreviewVisible = false
        }
    }
    // A room or a cast that begins while the middle is held owns the rate from then on.
    LaunchedEffect(watch.connected, cast.deviceId) {
        if (watch.connected || cast.deviceId != null) endSpeedBoost()
    }
    // Leaving the player mid-hold, or into 画中画, lets go too: the release that ends the boost
    // would otherwise never arrive.
    DisposableEffect(Unit) {
        onDispose { endSpeedBoost() }
    }
    SubtitlePeekEffect(
        peek = subtitlePeek,
        playback = playback,
        onUpdate = { if (subtitlePeek != null) subtitlePeek = it },
        onEnd = { endSubtitlePeek(it) },
    )
    // The next item and a cast bring their own subtitles: the replay's is dropped, not put back.
    LaunchedEffect(state.currentIndex, cast.deviceId) { endSubtitlePeek(null) }
    // Runs for as long as the press is held; cancelled by the release setting the
    // direction back to 0.
    LaunchedEffect(gestureState.scanDirection) {
        gestureState.runScan(
            direction = gestureState.scanDirection,
            stepPx = holdScanStepPx,
            durationMs = { latestDuration },
            onShift = { haptics.play(HapticSignal.Tick) },
            onSeek = { latestOnSeek(it) },
        )
    }

    // 取消 applies to this episode's credits only — the next one announces itself again.
    var nextUpDismissed by remember(state.currentIndex) { mutableStateOf(false) }
    LaunchedEffect(picture.volumeKeyPresses) {
        // Nothing has been pressed yet on first composition; don't flash the slider up.
        if (picture.volumeKeyPresses == 0L) return@LaunchedEffect
        chrome.volumeSliderVisible = true
    }
    LaunchedEffect(
        picture.volumeKeyPresses,
        chrome.volumeSliderTouches,
        chrome.volumeSliderVisible,
        accessibilityManager,
    ) {
        if (!chrome.volumeSliderVisible) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = VOLUME_SLIDER_HIDE_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: VOLUME_SLIDER_HIDE_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        chrome.volumeSliderVisible = false
    }
    LaunchedEffect(host.wakeRequests) {
        if (host.wakeRequests > 0) chrome.poke()
    }
    // The 回到 offer lasts a few seconds (longer under 操作时长), then the scan stands.
    LaunchedEffect(gestureState.scanUndoMs, accessibilityManager) {
        if (gestureState.scanUndoMs == null) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = SCAN_UNDO_MS,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: SCAN_UNDO_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        gestureState.expireScanUndo()
    }
    // A segment's skip offer is only good while playback is inside it, and making the viewer summon
    // the controls first spent a good part of that. Entering one raises the pill on its own for a
    // few seconds; after that it comes and goes with the controls like every other key.
    var skipSegmentJustEntered by remember { mutableStateOf(false) }
    LaunchedEffect(skip.segmentLabel, state.currentIndex, accessibilityManager) {
        skipSegmentJustEntered = skip.segmentLabel != null
        if (!skipSegmentJustEntered) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = MANUAL_SKIP_STANDALONE_MS,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: MANUAL_SKIP_STANDALONE_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        skipSegmentJustEntered = false
    }
    LaunchedEffect(
        chrome.locked,
        chrome.lockedRevealRevision,
        chrome.interactions,
        screenReaderActive,
        accessibilityManager,
    ) {
        if (!chrome.locked) {
            chrome.lockedControlsVisible = false
            chrome.lockedExplained = false
            return@LaunchedEffect
        }
        chrome.lockedControlsVisible = true
        // A spoken cursor cannot find a pill that has faded, so under a screen reader it stays.
        if (screenReaderActive) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = LOCKED_CONTROLS_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: LOCKED_CONTROLS_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        chrome.lockedControlsVisible = false
        chrome.lockedExplained = false
    }
    // A locked phone keeps the player: the edge swipe the lock is there to survive used to close it
    // outright. A television has no such swipe, and its Back unlocks through [closeTopRemoteLayer].
    // A failure takes the lock's place on screen, and Back is not held for a lock nobody can see.
    PlatformBackHandler(
        enabled = chrome.locked && state.error == null && remoteChrome == null,
        onBack = ::refuseWhileLocked,
    )

    // 片尾接管下一集: the credits draw the picture into a corner with the next episode beside it.
    // Not the guest's to take, not a cast's, not under the lock or an automatic skip's countdown.
    var creditsTakeoverDismissed by remember(state.currentIndex) { mutableStateOf(false) }
    val creditsPhaseState =
        rememberCreditsTakeoverPhase(
            playback = playback,
            credits = skip.credits,
            blocked =
                creditsTakeoverDismissed ||
                    nextUpDismissed ||
                    watchLocked ||
                    cast.deviceId != null ||
                    chrome.locked ||
                    skip.countdownSeconds != null ||
                    gestureState.scanning,
        )
    val creditsPhase by creditsPhaseState
    val creditsTakeover = creditsPhase != CreditsTakeoverPhase.Off
    val latestOnCreditsTakeover by rememberUpdatedState(transportActions.onCreditsTakeover)
    LaunchedEffect(creditsTakeover) { latestOnCreditsTakeover(creditsTakeover) }
    DisposableEffect(Unit) {
        onDispose { latestOnCreditsTakeover(false) }
    }

    // The remote at the end of an episode (TvPlayerPrompt.NextUp): neither card ever takes focus,
    // so OK over the picture plays the next episode and Back stays with the credits. Written through
    // the latest closures: the dismissals are remembered per episode, and a collector that outlives
    // one episode must not write the last one's.
    val nextUpCardShowing =
        remember(playback, nextUpDismissed) {
            derivedStateOf { nextUpCardVisible(playback.value, nextUpDismissed) }
        }
    val latestPlayNextFromRemote by rememberUpdatedState { transportActions.onNextItem() }
    val latestKeepCreditsFromRemote by rememberUpdatedState<() -> Unit> {
        if (creditsPhase == CreditsTakeoverPhase.Card) {
            creditsTakeoverDismissed = true
        } else if (!nextUpDismissed) {
            nextUpDismissed = true
            transportActions.onDismissNextUp()
        }
    }
    LaunchedEffect(remoteChrome) {
        remoteChrome?.commands?.collect { command ->
            when (command.type) {
                TvPlayerChromeCommandType.ActivateNextUp -> latestPlayNextFromRemote()
                TvPlayerChromeCommandType.DismissNextUp -> latestKeepCreditsFromRemote()
                else -> Unit
            }
        }
    }

    // 暂停信息层: three seconds into a settled pause with the chrome away. Put away by a touch or a
    // key, it stays away until the pause is disturbed and settles again.
    var pauseInfoShown by remember { mutableStateOf(false) }
    val pauseInfoReady =
        pauseInfoEligible(
            playing = state.playing,
            buffering = state.buffering,
            ended = state.ended,
            failed = state.error != null,
            controlsVisible = chrome.visible,
            overlayOpen = remotePanel != null || creditsTakeover,
            locked = chrome.locked,
        )
    LaunchedEffect(pauseInfoReady) {
        pauseInfoShown = false
        if (!pauseInfoReady) return@LaunchedEffect
        delay(PAUSE_INFO_DELAY_MS)
        pauseInfoShown = true
    }

    Box(
        modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (pauseInfoShown) {
                    // 暂停信息层 goes on any key, and a player key does nothing else on that press.
                    if (event.type == KeyEventType.KeyDown) pauseInfoShown = false
                    return@onKeyEvent event.playerKey(anchorFocused) != null
                }
                // Bubbled up from whatever has focus, so a text field or the seek bar answers first.
                keyboardShortcuts &&
                    !chrome.locked &&
                    remotePanel == null &&
                    state.error == null &&
                    keyboard.handle(
                        event = event,
                        anchorFocused = anchorFocused,
                        context = { keyContext() },
                        perform = { performKeyAction(it) },
                    )
            }.onFocusChanged { focusInside = it.hasFocus }
            .focusGroup(),
    ) {
        if (keyboardShortcuts) {
            PlayerKeyboardAnchor(keyboardAnchor) { keyboardAnchorFocused = it }
        }
        if (watch.connected) {
            WatchChatDanmakuOverlay(
                roomCode = watch.roomCode,
                messages = watch.chatMessages,
                // 弹幕 fly whether or not the chat panel is open. It used to be
                // `chatDanmakuEnabled && !watchChatOpen`, which suppressed every message the
                // sender ever wrote — sending is only possible from inside that panel — and
                // held back the rest of the room's while it was up. The panel is a drawer down
                // one edge; 弹幕 cross the width above it. They were never in each other's way.
                enabled = watch.chatDanmakuEnabled,
            )
            // Above the danmaku and below the controls: a reaction is meant to be seen
            // over the picture, never to swallow a tap aimed at 播放.
            WatchReactionOverlay(
                reactions = watch.reactions,
                onFinished = room.onReactionFinished,
                // Reactions rise up the bottom-right corner, which is where the chat panel
                // opens. Left where they were they played out entirely behind it.
                insetEnd = if (chrome.watchChatOpen) WatchChatPanelWidth + 20.dp else 26.dp,
            )
        }

        // Tap catcher sits below the controls, so buttons win the gesture.
        Box(
            Modifier
                .fillMaxSize()
                // Hidden chrome leaves no node behind, and touch exploration never sends the tap
                // that brings it back: the picture itself is the control that does.
                .semantics {
                    contentDescription = "播放画面"
                    onClick(label = "显示播放控件") {
                        chrome.poke()
                        true
                    }
                }
                // Keyed on nothing: `settingsPanelKind`, `drawerOpen` and `visible` are read
                // through their state delegates below, so the detector already sees the
                // current values without being torn down. Keying on them meant any of
                // them changing restarted the gesture stream mid-press — which the hold
                // to fast-forward cannot survive, since it is the release that lands the
                // seek and `poke()` flips `visible` the moment the hold starts.
                .pointerInput(Unit) {
                    fun burstSeek(
                        direction: Int,
                        at: Offset,
                        taps: Int,
                    ) {
                        // 双击步长, as 播放设置 last left it.
                        val target =
                            gestureState.burstSeek(
                                direction = direction,
                                at = at,
                                taps = taps,
                                stepMs = latestGestures.doubleTapSeekMs,
                                positionMs = latestPosition,
                                durationMs = latestDuration,
                            )
                        tips?.markUsed(Tips.PLAYER_DOUBLE_TAP)
                        latestOnSeek(target)
                    }
                    detectTapGestures(
                        onPress = {
                            // The engine follows merged held ticks; release only stops the producer.
                            tryAwaitRelease()
                            if (gestureState.releaseScan()) chrome.poke()
                            // 长按中间 goes back to how it found things, and leaves the chrome
                            // hidden: the hold was for watching.
                            endSpeedBoost()
                        },
                        onTap = { offset ->
                            // Once a double tap is seeking, a tap on the same side keeps it going.
                            val burstSide =
                                pictureThird(offset.x, size.width).takeIf { side ->
                                    gestureState.burstContinues(side) &&
                                        !latestWatchLocked &&
                                        allowsPlayerDrag(offset.y, currentSystemGestureTop)
                                }
                            when {
                                chrome.locked -> chrome.revealLock(explain = false)
                                chrome.watchChatOpen -> chrome.watchChatOpen = false
                                chrome.danmakuSendOpen -> chrome.danmakuSendOpen = false
                                chrome.danmakuSearchOpen -> chrome.danmakuSearchOpen = false
                                chrome.quickPopup != null -> chrome.quickPopup = null
                                chrome.settingsPanelKind != null -> chrome.settingsPanelKind = null
                                chrome.drawerOpen -> chrome.drawerOpen = false
                                burstSide != null -> {
                                    burstSeek(burstSide, offset, taps = 1)
                                    haptics.play(HapticSignal.Confirm)
                                }
                                // 点弹幕 with the chrome up only: with it away, a tap always brings it up first.
                                !chrome.locked && chrome.visible && latestExtras.onPictureTap(offset) -> {
                                    tips?.markUsed(Tips.PLAYER_DANMAKU_PICK)
                                }
                                chrome.visible -> chrome.visible = false
                                else -> chrome.poke()
                            }
                        },
                        onDoubleTap = { offset ->
                            // The lock's own catcher takes the touch before it gets here; this is the
                            // floor under it, so no path through the lock can seek or pause.
                            if (chrome.locked) {
                                refuseWhileLocked()
                                return@detectTapGestures
                            }
                            if (!allowsPlayerDrag(offset.y, currentSystemGestureTop)) return@detectTapGestures
                            if (latestWatchLocked) {
                                gestureState.say("房主控制播放")
                                haptics.play(HapticSignal.Reject)
                            } else {
                                when {
                                    // 双击 · 全屏暂停: the whole picture is one play/pause key, as in
                                    // the domestic apps whose double tap never seeks.
                                    latestGestures.doubleTapPausesAnywhere -> {
                                        latestOnPlayPause()
                                        gestureState.say(if (state.playing) "暂停" else "播放")
                                    }
                                    // A double tap inside a running burst is two more of its taps.
                                    offset.x < size.width / 3f -> burstSeek(-1, offset, taps = 2)
                                    offset.x > size.width * 2f / 3f -> burstSeek(1, offset, taps = 2)
                                    else -> {
                                        latestOnPlayPause()
                                        gestureState.say(if (state.playing) "暂停" else "播放")
                                    }
                                }
                                haptics.play(HapticSignal.Confirm)
                            }
                            chrome.poke()
                        },
                        onLongPress = { offset ->
                            if (chrome.locked) {
                                refuseWhileLocked()
                                return@detectTapGestures
                            }
                            if (!allowsPlayerDrag(offset.y, currentSystemGestureTop)) return@detectTapGestures
                            // Thirds, exactly as the double tap divides the picture: left
                            // rewinds, right fast-forwards, and the middle — where the double
                            // tap plays and pauses rather than seeking — plays faster for as
                            // long as it is held. The hold used to split the frame in halves,
                            // so the same spot on the picture meant 播放 to one gesture and 快进
                            // to the other.
                            val direction = pictureThird(offset.x, size.width)
                            // 中间长按 and 两侧长按 in 播放设置 decide; 中间长按 · 关闭 leaves the held
                            // middle to do nothing, as it once did.
                            val action =
                                pictureHoldAction(
                                    direction = direction,
                                    centerHoldSpeedBoost = latestGestures.centerHoldSpeedBoost,
                                    sideHoldScans = latestGestures.sideHoldScans,
                                    boostRefusal = currentSpeedBoostRefusal(),
                                )
                            when (action) {
                                PictureHoldAction.SpeedBoost ->
                                    if (startSpeedBoost(offset.x)) return@detectTapGestures
                                PictureHoldAction.Nothing -> Unit
                                PictureHoldAction.Scan ->
                                    when {
                                        latestWatchLocked -> {
                                            gestureState.say("房主控制播放")
                                            haptics.play(HapticSignal.Reject)
                                        }
                                        latestDuration <= 0L -> Unit
                                        else -> {
                                            gestureState.startScan(direction, offset.x, latestPosition)
                                            // A hold that has taken hold — the same signal a long
                                            // press gets everywhere else in the app.
                                            haptics.play(HapticSignal.Confirm)
                                        }
                                    }
                            }
                            chrome.poke()
                        },
                    )
                }.pointerInput(
                    state.currentIndex,
                ) {
                    detectPlayerDragGestures(
                        canStart = { origin -> !chrome.locked && allowsPlayerDrag(origin.y, currentSystemGestureTop) },
                        onDragStart = { offset ->
                            gestureState.startDrag(offset.x, latestPosition, latestVolume(), latestBrightness())
                        },
                        onDragEnd = {
                            val landing = gestureState.endDrag(latestDuration, latestWatchLocked)
                            // 长按中间 ends in its own release, with the chrome left hidden.
                            if (!gestureState.boosting) {
                                landing?.let { target ->
                                    latestOnSeek(target)
                                    tips?.markUsed(Tips.PLAYER_SWIPE_SEEK)
                                }
                                if (gestureState.dragAxis == DragAxis.Vertical) tips?.markUsed(Tips.PLAYER_SIDE_DRAG)
                                chrome.poke()
                            }
                        },
                        onDragCancel = { gestureState.cancelDrag() },
                    ) { change, amount ->
                        change.consume()
                        val level =
                            gestureState.drag(
                                dx = amount.x,
                                dy = amount.y,
                                dtMs = change.uptimeMillis - change.previousUptimeMillis,
                                width = size.width,
                                height = size.height,
                                density = density,
                                positionMs = latestPosition,
                                durationMs = latestDuration,
                                watchGuest = latestWatchLocked,
                                swapBrightnessVolume = latestGestures.swapBrightnessVolume,
                            )
                        when (level) {
                            is PictureLevel.Brightness -> latestOnBrightness(level.level)
                            is PictureLevel.Volume -> latestOnVolume(level.level)
                            null -> Unit
                        }
                    }
                }.pointerInput(Unit) {
                    // The sideways slide between gears, for 长按中间 and for 长按扫描 alike. Neither
                    // detector above can follow it: once a long press has fired, the tap detector
                    // consumes every move until release, and the drag detector abandons a gesture
                    // on the first consumed move it sees before its slop. This one only watches,
                    // and only while a hold is on — it consumes nothing and decides nothing else.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (gestureState.scanning) {
                                val finger = event.changes.firstOrNull { it.pressed } ?: continue
                                val shifted =
                                    gestureState.followScan(finger.position.x, HoldScanGearStep.toPx(), latestDuration)
                                if (shifted) haptics.play(HapticSignal.Tick)
                                continue
                            }
                            if (!gestureState.boosting) continue
                            val finger = event.changes.firstOrNull { it.pressed } ?: continue
                            gestureState.followBoost(finger.position.x, SpeedBoostGearStep.toPx())?.let { speed ->
                                latestOnSpeedBoost(speed)
                                haptics.play(HapticSignal.Tick)
                            }
                        }
                    }
                }.pointerInput(Unit) {
                    // 捏合填充. Last on the picture on purpose: the main pass reaches it before the
                    // detectors above, so the changes it consumes are what cancel their gestures.
                    detectPinchFill(
                        canPinch = { origin -> !chrome.locked && allowsPlayerDrag(origin.y, currentSystemGestureTop) },
                        filled = { latestFilled },
                        onSecondFinger = {
                            if (gestureState.secondFinger()) latestOnSpeedBoost(null)
                        },
                        onFill = { fill ->
                            latestOnSetFill(fill)
                            tips?.markUsed(Tips.PLAYER_PINCH_FILL)
                            gestureState.say(pinchFillMessage(fill))
                            haptics.play(HapticSignal.Threshold)
                        },
                    )
                },
        )

        // The failure surface takes over from the chrome instead of deleting it.
        //
        // This block used to end in `return@Box`, which tore every control out of the tree on
        // the frame the error arrived: the chrome disappeared in one frame underneath an overlay
        // that was still fading in, and came back the same way on a successful retry. Both sides
        // animate now — the chrome leaves through the same [ChromeVisibility] it arrives
        // through, and the error surface enters through [ChromeContent] below, which also keeps
        // the message on screen for the length of its own exit. Neither is composed at all once
        // its exit has finished, so nothing behind an error is live or reachable.
        val errorMessage = state.error

        ChromeVisibility(
            visible = chrome.locked && errorMessage == null,
            modifier = Modifier.fillMaxSize(),
            coversScreen = true,
        ) {
            LockedOverlay(
                controlsVisible = chrome.lockedControlsVisible,
                message =
                    when {
                        !chrome.lockedExplained -> "屏幕已锁定"
                        remoteChrome != null -> "屏幕已锁定，按返回键解锁"
                        screenReaderActive -> "屏幕已锁定，请先解锁"
                        picture.gestures.unlockByLongPress -> "屏幕已锁定，长按左侧锁键解锁"
                        else -> "屏幕已锁定，点按左侧锁键解锁"
                    },
                screenReaderActive = screenReaderActive,
                unlockByLongPress = picture.gestures.unlockByLongPress,
                onReveal = { chrome.revealLock(explain = false) },
                onRefuse = ::refuseWhileLocked,
                onUnlock = {
                    if (chrome.locked) {
                        chrome.locked = false
                        chrome.poke()
                    }
                },
            )
        }
        ChromeVisibility(
            visible = !chrome.locked && errorMessage == null,
            modifier = Modifier.fillMaxSize(),
            coversScreen = true,
        ) {
            Box(Modifier.fillMaxSize()) {
                PlayerResumeNotice(
                    notice = resumeNotice,
                    onRestart = {
                        if (resumeNotice.visible) {
                            resumeNotice.dismiss()
                            latestOnSeek(0L)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 120.dp),
                )

                // Top-level actions (投屏/更多) live with the title; media navigation stays below.
                PlayerTopChrome(
                    chrome = chrome,
                    playback = playback,
                    controlState = controlState,
                    transport = transport,
                    picture = picture,
                    pictureActions = pictureActions,
                    source = source,
                    cast = cast,
                    watch = watch,
                    host = host,
                    onAmbientPresence = ambientPresenceChanged,
                )

                PlayerBottomChrome(
                    chrome = chrome,
                    gestureState = gestureState,
                    playback = playback,
                    controlState = controlState,
                    remoteChromeState = remoteChromeState,
                    watchLocked = watchLocked,
                    transport = transport,
                    transportActions = transportActions,
                    picture = picture,
                    source = source,
                    danmaku = danmaku,
                    danmakuActions = danmakuActions,
                    onMissedLine = { rewindMissedLine() },
                    playKeyModifier = if (remoteChrome != null) Modifier.focusRequester(playKeyFocus) else Modifier,
                    onAmbientPresence = ambientPresenceChanged,
                )

                // 锁定 without the trip into 更多: the left edge's key, up with the rest of the chrome.
                // Touch screens only — a remote sends no stray touches for a lock to keep out.
                ChromeVisibility(
                    visible = chrome.visible && remoteChrome == null,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = LockKeyEdgePadding),
                ) {
                    PlayerLockKey(locked = false, onClick = chrome::lockScreen)
                }

                PlayerSkipPrompts(
                    chrome = chrome,
                    gestureState = gestureState,
                    skip = skip,
                    skipActions = skipActions,
                    onSeek = { latestOnSeek(it) },
                    hintProgress = hintProgress,
                    remoteChrome = remoteChrome,
                    segmentJustEntered = skipSegmentJustEntered,
                    creditsTakeover = creditsTakeover,
                    errorMessage = errorMessage,
                    nextUpCardShowing = nextUpCardShowing,
                    creditsPhase = creditsPhaseState,
                )

                PlayerSettingsLayers(
                    chrome = chrome,
                    playback = playback,
                    controlState = controlState,
                    gestureState = gestureState,
                    watchLocked = watchLocked,
                    transport = transport,
                    transportActions = transportActions,
                    picture = picture,
                    pictureActions = pictureActions,
                    tracks = tracks,
                    trackActions = trackActions,
                    source = source,
                    sourceActions = sourceActions,
                    panels = panels,
                    panelActions = panelActions,
                    cast = cast,
                    castActions = castActions,
                    danmaku = danmaku,
                    danmakuActions = danmakuActions,
                    watch = watch,
                    reduceMotion = reduceMotion,
                    onEndSubtitlePeek = { endSubtitlePeek(null) },
                )

                PlayerWatchLayers(
                    chrome = chrome,
                    watch = watch,
                    room = room,
                    closeWatchChat = closeWatchChat,
                    hintProgress = hintProgress,
                )

                PlayerSheetLayers(
                    chrome = chrome,
                    controlState = controlState,
                    gestureState = gestureState,
                    watchLocked = watchLocked,
                    transport = transport,
                    transportActions = transportActions,
                    danmaku = danmaku,
                    danmakuActions = danmakuActions,
                )

                ChromeVisibility(
                    visible = watch.connected && chrome.visible,
                    edge = ChromeEdge.Top,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 74.dp),
                ) {
                    WatchRoomNote(
                        reconnecting = watch.reconnecting,
                        isHost = watch.isHost,
                        participantCount = watch.participantCount,
                        onOpenChat = { chrome.openWatchChat(watch.chatMessages) },
                    )
                }

                PlayerCastPill(
                    chrome = chrome,
                    cast = cast,
                    castActions = castActions,
                    hintProgress = hintProgress,
                )

                PlayerPictureStatus(
                    chrome = chrome,
                    gestureState = gestureState,
                    playback = playback,
                    controlState = controlState,
                    pulseItemStart = pulseItemStart,
                    pauseInfoShown = pauseInfoShown,
                    watchLocked = watchLocked,
                    speedBoostable = !watch.connected && cast.deviceId == null,
                    remoteChrome = remoteChrome,
                    remoteChromeState = remoteChromeState,
                    transport = transport,
                    transportActions = transportActions,
                    onSeek = { latestOnSeek(it) },
                    onBack = host.onBack,
                    picture = picture,
                    pictureActions = pictureActions,
                    danmaku = danmaku,
                )

                PlayerEndOfItemCards(
                    chrome = chrome,
                    playback = playback,
                    controlState = controlState,
                    creditsPhase = creditsPhaseState,
                    nextUpDismissed = nextUpDismissed,
                    transport = transport,
                    transportActions = transportActions,
                    onWatchCredits = { creditsTakeoverDismissed = true },
                    onNextUpDismissed = { nextUpDismissed = true },
                )
            }
        }

        // Last in the box, so it covers the chrome on its way out rather than fading in under it.
        PlayerErrorLayer(
            errorMessage = errorMessage,
            playback = playback,
            source = source,
            sourceActions = sourceActions,
            onRetry = transportActions.onRetry,
            onBack = host.onBack,
        )
    }
}
