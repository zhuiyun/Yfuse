package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Color
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
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.designsystem.AmbientLight
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackOverlay
import com.yfuse.core.designsystem.DragAxis
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalTips
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Tips
import com.yfuse.core.designsystem.rememberScreenReaderActive
import com.yfuse.core.model.PlaybackChapter
import com.yfuse.tv.player.TvPlayerChromeBridge
import com.yfuse.tv.player.TvPlayerChromeCommandType
import com.yfuse.tv.player.TvPlayerChromeLayer
import com.yfuse.tv.player.TvPlayerChromePanel
import com.yfuse.tv.player.TvPlayerPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import com.yfuse.core.designsystem.ThemeText as Text

/** Controls fade out after this long without interaction, while playing. */
private const val MAX_ERROR_ALTERNATIVES = 3
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

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Single-purpose popups opened by their own playback-page buttons. */
private enum class QuickPopup {
    Source,
    Speed,
}

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
 * Everything shown comes from [playback], so ExoPlayer and libmpv get the same controls.
 */
@Composable
internal fun PlayerControls(
    playback: State<PlaybackState>,
    // The queue, as the strip and the title bar both read it.
    episodes: List<EpisodeCard>,
    scaleMode: VideoScaleMode,
    onBack: () -> Unit,
    onEnterPictureInPicture: (() -> Unit)?,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    /** Where playback resumed from, when it did; shows a brief 从头开始 offer. */
    resumedFromMs: Long? = null,
    onExternalPlayer: (() -> Unit)? = null,
    onSeek: (Long) -> Unit,
    onSelectItem: (Int) -> Unit,
    onPreviousItem: () -> Boolean,
    onNextItem: () -> Boolean,
    /** 取消 on the next-up card: the engine must not advance on its own either. */
    onDismissNextUp: () -> Unit = {},
    /** 片尾接管: true while the credits have the picture in its corner; the caller shrinks the surface. */
    onCreditsTakeover: (Boolean) -> Unit = {},
    /** 自动播放下一集, as the engine was built with it: off, nothing counts down to the next item. */
    autoNext: Boolean = true,
    onRefreshEpisodes: () -> Unit,
    onSelectAudio: (String) -> Unit,
    audioControls: AudioControlState = AudioControlState(),
    audioActions: AudioControlActions = AudioControlActions(),
    onSelectSubtitle: (String) -> Unit,
    /**
     * 没听清: show [SubtitlePeek.trackId] on the engine for the replay. Temporary by contract — the
     * caller keeps it out of series memory, preferences and its own restore state.
     */
    onPeekSubtitle: (SubtitlePeek) -> Unit = {},
    /** 没听清 is over: put the given track back ([EngineTrack.OFF] included), or with null touch nothing. */
    onEndSubtitlePeek: (String?) -> Unit = {},
    subtitleControls: SubtitleControlState = SubtitleControlState(),
    subtitleActions: SubtitleControlActions = SubtitleControlActions(),
    bookmarks: PlaybackBookmarkPanelState = PlaybackBookmarkPanelState(),
    bookmarkActions: PlaybackBookmarkActions = PlaybackBookmarkActions(),
    remoteSubtitles: RemoteSubtitlePanelState = RemoteSubtitlePanelState(),
    remoteSubtitleActions: RemoteSubtitleActions = RemoteSubtitleActions(),
    onSpeed: (Float) -> Unit,
    /**
     * 长按中间: the speed to play at while the middle third is held, or null once it is let go.
     * Temporary by contract — the caller must not remember it as the series' speed.
     */
    onSpeedBoost: (Float?) -> Unit = {},
    /** 手势 from 播放设置: the double-tap step, whether the middle holds a speed, which side is which. */
    gestures: PlayerGestureSettings = PlayerGestureSettings(),
    sleepTimer: SleepTimerState = SleepTimerState(),
    sleepTimerActions: SleepTimerActions = SleepTimerActions(),
    onToggleFill: (stretch: Boolean) -> Unit,
    /** 捏合填充 and the F key: 裁剪填满 (true) or 适应 (false), remembered for the series like the button. */
    onSetFill: (Boolean) -> Unit = {},
    trickplay: TrickplayStoryboard? = null,
    /*
     * System volume, 0f..1f, and its setter — read by the right-edge drag gesture and by the
     * slider the volume rocker raises. There is no on-screen volume control any more. A reader
     * rather than a value: a drag or the rocker changes it many times a second, and as a value
     * each of those recomposed every control on this screen.
     */
    volume: () -> Float = { 0f },
    onVolume: (Float) -> Unit = {},
    /*
     * Increments on each volume key press. Any change raises the vertical slider; the value
     * itself is meaningless, which is what lets a press at the volume ceiling still show it.
     */
    volumeKeyPresses: Long = 0L,
    // Current window brightness, 0f..1f, as a reader for the same reason. Vertical drags on the left half adjust it.
    brightness: () -> Float = { 0.5f },
    onBrightness: (Float) -> Unit = {},
    // Engine picker rows: label to selected.
    engineOptions: List<Pair<String, Boolean>> = emptyList(),
    onSelectEngine: (Int) -> Unit = {},
    // Null when the active engine has no transcode fallback.
    transcodeLabel: String? = null,
    transcodeActive: Boolean = false,
    onTranscode: () -> Unit = {},
    onResetAdaptiveLearning: () -> Unit = {},
    onNextDiscTitle: () -> Unit = {},
    onNextDiscChapter: () -> Unit = {},
    onShowDiscMenu: () -> Unit = {},
    castDevices: List<Pair<String, String>> = emptyList(),
    castingDeviceId: String? = null,
    castDiscovering: Boolean = false,
    castError: String? = null,
    castStatus: String? = null,
    /** A session is connecting or live on a receiver; [castStatus] then names it and its state. */
    castActive: Boolean = false,
    castPosition: String? = null,
    castPositionSource: (() -> String?)? = null,
    castCapabilities: String? = null,
    onDiscoverCast: () -> Unit = {},
    onCastTo: (String) -> Unit = {},
    onStopCast: () -> Unit = {},
    danmaku: DanmakuPanelState = DanmakuPanelState(),
    danmakuActions: DanmakuPanelActions = DanmakuPanelActions(),
    /** 弹幕热度 of the matched comments, read while the rail draws; null when nothing is matched. */
    danmakuHeat: () -> DanmakuHeat? = { null },
    // The server this file is on. Null when there is only ever one server to be on.
    sourceLabel: String? = null,
    // Resolved copies of the current item on other servers.
    sourceOptions: List<Pair<String, String>> = emptyList(),
    selectedSourceId: String? = null,
    onSelectSource: (String) -> Unit = {},
    // `MKV` — the container, which the engine cannot report but the library knows.
    containerLabel: String? = null,
    dolbyVision: Boolean = false,
    dolbyAtmos: Boolean = false,
    // Files the server holds for this entry; a picker appears once there are two.
    versions: List<Pair<String, String>> = emptyList(),
    selectedVersionId: String? = null,
    onSelectVersion: (String) -> Unit = {},
    skip: SkipSegmentState = SkipSegmentState(),
    skipActions: SkipSegmentActions = SkipSegmentActions(),
    /** The file's named chapters: the progress bar is divided at them and the preview names them. */
    chapters: List<PlaybackChapter> = emptyList(),
    watch: WatchRoomState = WatchRoomState(),
    watchActions: WatchRoomActions = WatchRoomActions(),
    /** 点弹幕, 旋转锁 and the press-and-slide keys, supplied by the player root; see [PlayerChromeExtras]. */
    extras: PlayerChromeExtras = PlayerChromeExtras(),
    remoteChrome: TvPlayerChromeBridge? = null,
    /**
     * A hardware keyboard is attached: 键盘快捷键 answer. Ignored with [remoteChrome], because TV keeps
     * its remote controller, which sees every key before the window does.
     */
    hardwareKeyboard: Boolean = false,
    /** 氛围光 for the scrims and seek accent; null while the light is off. */
    ambientLight: State<AmbientLight>? = null,
    ambientLightEnabled: Boolean = true,
    onToggleAmbientLight: () -> Unit = {},
    onAmbientChromeVisibleChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    systemGestureTopPx: Float = 0f,
    /** Bumped by the owner to bring the controls up, as a tap on the picture would. */
    wakeRequests: Int = 0,
) {
    val currentSystemGestureTop by rememberUpdatedState(systemGestureTopPx)
    val latestExtras by rememberUpdatedState(extras)
    val state by rememberPlayerControlSnapshot(playback)
    var visible by remember { mutableStateOf(true) }
    var ambientChromeCount by remember { mutableIntStateOf(0) }
    val latestAmbientVisibility by rememberUpdatedState(onAmbientChromeVisibleChange)
    val ambientPresenceChanged =
        remember {
            { present: Boolean -> ambientChromeCount += if (present) 1 else -1 }
        }
    val ambientChromeVisible = ambientChromeCount > 0
    SideEffect { latestAmbientVisibility(ambientChromeVisible) }
    DisposableEffect(Unit) {
        onDispose { latestAmbientVisibility(false) }
    }
    val hintProgress = rememberPlayerHintProgress(visible)
    var locked by remember { mutableStateOf(false) }
    // The lock's own chrome — the circle and 长按解锁 — comes up for a moment after locking and after
    // each touch on the picture, then leaves it alone. [lockedRevealRevision] restarts that moment.
    var lockedControlsVisible by remember { mutableStateOf(false) }
    var lockedRevealRevision by remember { mutableIntStateOf(0) }
    // Someone has tried to act through the lock: the circle says how to undo it until it fades.
    var lockedExplained by remember { mutableStateOf(false) }
    var settingsPanelKind by remember { mutableStateOf<SettingsPanelKind?>(null) }
    var trackPanelMode by remember { mutableStateOf(TrackPanelMode.Subtitle) }
    var quickPopup by remember { mutableStateOf<QuickPopup?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var gestureHelpOpen by remember { mutableStateOf(false) }
    var watchDialogOpen by remember { mutableStateOf(false) }
    var watchChatOpen by remember { mutableStateOf(false) }
    var chatPreviewVisible by remember { mutableStateOf(false) }
    var previewRoomCode by remember { mutableStateOf<String?>(null) }
    var lastPreviewedChatId by remember { mutableStateOf<Long?>(null) }
    var lastReadChatId by remember { mutableStateOf<Long?>(null) }
    var danmakuSearchOpen by remember { mutableStateOf(false) }
    var danmakuSendOpen by remember { mutableStateOf(false) }
    // 键盘快捷键 on a phone, tablet or Chromebook; TV's remote has its own controller.
    val keyboardShortcuts = hardwareKeyboard && remoteChrome == null
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
    // Bumped by every interaction so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }
    val latestPosition by remember(playback) { derivedStateOf { playback.value.positionMs } }
    val latestDuration by rememberUpdatedState(state.durationMs)
    val latestVolume by rememberUpdatedState(volume)
    val latestBrightness by rememberUpdatedState(brightness)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnVolume by rememberUpdatedState(onVolume)
    val latestOnBrightness by rememberUpdatedState(onBrightness)
    // Timeline controls (play/pause, seek, episode, speed) are read-only for a connected
    // non-host: the room's host drives them, this device only follows. Volume, brightness,
    // subtitle/audio track, aspect ratio, cast and danmaku stay untouched by this — those
    // are per-viewer, not shared.
    val watchLocked = watch.locked
    val latestWatchLocked by rememberUpdatedState(watchLocked)
    // Read by the long-lived gesture detector, which would otherwise keep its first frame's values.
    val latestWatchConnected by rememberUpdatedState(watch.connected)
    val latestCasting by rememberUpdatedState(castingDeviceId != null)
    val latestOnSpeedBoost by rememberUpdatedState(onSpeedBoost)
    val latestGestures by rememberUpdatedState(gestures)
    val latestFilled by rememberUpdatedState(scaleMode != VideoScaleMode.Fit)
    val latestOnSetFill by rememberUpdatedState(onSetFill)
    val remoteChromeState = remoteChrome?.state?.collectAsState()?.value
    LaunchedEffect(remoteChromeState?.seekTargetMs, remoteChromeState?.seeking) {
        val target = remoteChromeState?.seekTargetMs ?: return@LaunchedEffect
        if (remoteChromeState.seeking) {
            gestureState.say("跳转 ${target.asClock()} / ${state.durationMs.asClock()}")
        }
    }

    LaunchedEffect(watch.available) {
        if (!watch.available) {
            watchDialogOpen = false
            watchChatOpen = false
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

    fun poke() {
        interactions++
        visible = true
    }

    /** Why a hold may not speed playback up at this moment; null when it may. */
    fun currentSpeedBoostRefusal(): SpeedBoostRefusal? =
        speedBoostRefusal(
            panelOpen =
                watchChatOpen ||
                    danmakuSendOpen ||
                    danmakuSearchOpen ||
                    quickPopup != null ||
                    settingsPanelKind != null ||
                    drawerOpen,
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
        visible = false
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
    val latestOnEndSubtitlePeek by rememberUpdatedState(onEndSubtitlePeek)

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
                secondarySubtitleTrackId = live.secondarySubtitleTrackId ?: subtitleControls.secondaryTrackId,
                running = subtitlePeek,
                subtitlesAllowed = castingDeviceId == null,
            )
        latestOnSeek(plan.targetMs)
        plan.peek?.takeIf { subtitlePeek == null }?.let(onPeekSubtitle)
        subtitlePeek = plan.peek
        gestureState.say(plan.message)
        poke()
    }

    /** The player as a key press finds it; read at the press, never kept. */
    fun keyContext(): PlayerKeyContext {
        val live = playback.value
        val frames =
            trickplay
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

    fun revealLock(explain: Boolean) {
        if (explain) lockedExplained = true
        lockedRevealRevision++
    }

    // A double tap, a hold, a tap on 长按解锁 or the back gesture while locked: refused out loud,
    // and the way out shown instead of the thing asked for.
    fun refuseWhileLocked() {
        haptics.play(HapticSignal.Reject)
        revealLock(explain = true)
    }

    fun openWatchChat() {
        settingsPanelKind = null
        quickPopup = null
        drawerOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        watchChatOpen = true
        lastReadChatId = watch.chatMessages.lastOrNull()?.id
        chatPreviewVisible = false
        poke()
    }

    fun openSettingsPanel(
        kind: SettingsPanelKind,
        trackMode: TrackPanelMode = TrackPanelMode.Subtitle,
    ) {
        quickPopup = null
        drawerOpen = false
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        trackPanelMode = trackMode
        settingsPanelKind = kind
        poke()
    }

    fun openQuickPopup(popup: QuickPopup) {
        settingsPanelKind = null
        drawerOpen = false
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        quickPopup = popup
        poke()
    }

    fun openEpisodeDrawer() {
        settingsPanelKind = null
        quickPopup = null
        watchChatOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        drawerOpen = true
        poke()
    }

    /** 锁定, from the left-edge key or from 更多: whatever was open closes under the lock. */
    fun lockScreen() {
        settingsPanelKind = null
        quickPopup = null
        drawerOpen = false
        locked = true
        visible = true
    }

    // Also stable for the life of the panel, and for the same reason: read through the
    // transcript rather than closing over this frame's copy of it.
    val latestChatMessages by rememberUpdatedState(watch.chatMessages)
    val closeWatchChat =
        remember {
            {
                watchChatOpen = false
                lastReadChatId = latestChatMessages.lastOrNull()?.id
            }
        }

    fun closeTopRemoteLayer() {
        when {
            danmakuSendOpen -> danmakuSendOpen = false
            danmakuSearchOpen -> danmakuSearchOpen = false
            drawerOpen -> drawerOpen = false
            watch.controlRequesterName != null -> room.onDenyControl()
            watchChatOpen -> closeWatchChat()
            watchDialogOpen -> watchDialogOpen = false
            gestureHelpOpen -> gestureHelpOpen = false
            quickPopup != null -> quickPopup = null
            settingsPanelKind != null -> settingsPanelKind = null
            locked -> locked = false
            visible -> visible = false
        }
    }

    val resumeNotice =
        rememberResumeNotice(
            resumedFromMs = resumedFromMs,
            itemIndex = state.currentIndex,
            ready = state.playing && !state.buffering && state.error == null,
            controlsVisible = visible,
            interrupted =
                interactions > 0 ||
                    locked ||
                    watchLocked ||
                    settingsPanelKind != null ||
                    quickPopup != null ||
                    drawerOpen ||
                    watchChatOpen ||
                    danmakuSearchOpen ||
                    danmakuSendOpen ||
                    gestureHelpOpen ||
                    watchDialogOpen ||
                    state.error != null,
        )

    val remotePanel =
        when {
            danmakuSendOpen -> TvPlayerChromePanel.DanmakuSend
            danmakuSearchOpen -> TvPlayerChromePanel.DanmakuSearch
            drawerOpen -> TvPlayerChromePanel.Episodes
            watch.controlRequesterName != null -> TvPlayerChromePanel.ControlRequest
            watchChatOpen -> TvPlayerChromePanel.WatchChat
            watchDialogOpen -> TvPlayerChromePanel.WatchTogether
            gestureHelpOpen -> TvPlayerChromePanel.GestureHelp
            quickPopup != null -> TvPlayerChromePanel.QuickPicker
            settingsPanelKind != null -> TvPlayerChromePanel.Settings
            else -> null
        }
    val remoteLayer =
        when {
            locked -> TvPlayerChromeLayer.Locked
            remotePanel != null -> TvPlayerChromeLayer.Panel
            visible -> TvPlayerChromeLayer.Controls
            else -> TvPlayerChromeLayer.Hidden
        }
    val latestRemotePanel by rememberUpdatedState(remotePanel)
    val latestRemoteLocked by rememberUpdatedState(locked)
    val latestCloseTopRemoteLayer by rememberUpdatedState { closeTopRemoteLayer() }
    val latestSkip by rememberUpdatedState(skip)
    val latestSkipActions by rememberUpdatedState(skipActions)

    LaunchedEffect(remoteChrome) {
        remoteChrome?.commands?.collect { command ->
            when (command.type) {
                TvPlayerChromeCommandType.ShowControls -> poke()
                TvPlayerChromeCommandType.HideControls -> {
                    if (latestRemotePanel == null && !latestRemoteLocked) visible = false
                }
                TvPlayerChromeCommandType.CloseTop -> latestCloseTopRemoteLayer()
                TvPlayerChromeCommandType.OpenTracks -> openSettingsPanel(SettingsPanelKind.Tracks)
                TvPlayerChromeCommandType.OpenInfo -> openSettingsPanel(SettingsPanelKind.More)
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
        visible,
        locked,
        settingsPanelKind,
        quickPopup,
        drawerOpen,
        danmakuSearchOpen,
        danmakuSendOpen,
        watchChatOpen,
        state.playing || (state.buffering && state.positionMs > 0L),
        interactions,
        accessibilityManager,
        controlsHaveFocus,
        screenReaderActive,
        gestureState.scrubbing,
    ) {
        val overlayOpen =
            gestureHelpOpen ||
                settingsPanelKind != null ||
                quickPopup != null ||
                drawerOpen ||
                danmakuSearchOpen ||
                danmakuSendOpen ||
                watchChatOpen
        // Keep the hide timer stable across NativeDirect's playing <-> buffering handoff.
        // A remote Range stall is still an active playback request, not a user pause.
        // During initial startup we wait until playback has actually advanced or rendered.
        val playbackActive = state.playing || (state.buffering && state.positionMs > 0L)
        if (
            !visible ||
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
        visible = false
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
        watchChatOpen,
        watch.chatPreviewEnabled,
        accessibilityManager,
    ) {
        val latestId = watch.chatMessages.lastOrNull()?.id
        if (previewRoomCode != watch.roomCode) {
            previewRoomCode = watch.roomCode
            lastPreviewedChatId = latestId
            lastReadChatId = latestId
            chatPreviewVisible = false
            if (!watch.connected) watchChatOpen = false
            return@LaunchedEffect
        }
        if (!watch.connected) {
            watchChatOpen = false
            chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (!watch.chatPreviewEnabled) {
            lastPreviewedChatId = latestId
            chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (watchChatOpen) {
            lastReadChatId = latestId
            lastPreviewedChatId = latestId
            chatPreviewVisible = false
            return@LaunchedEffect
        }
        if (watch.chatPreviewEnabled && latestId != null && latestId != lastPreviewedChatId) {
            lastPreviewedChatId = latestId
            chatPreviewVisible = true
            val timeout =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = CHAT_PREVIEW_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = false,
                ) ?: CHAT_PREVIEW_MS
            if (timeout == Long.MAX_VALUE) return@LaunchedEffect
            delay(timeout)
            chatPreviewVisible = false
        }
    }
    // A room or a cast that begins while the middle is held owns the rate from then on.
    LaunchedEffect(watch.connected, castingDeviceId) {
        if (watch.connected || castingDeviceId != null) endSpeedBoost()
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
    LaunchedEffect(state.currentIndex, castingDeviceId) { endSubtitlePeek(null) }
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

    // The volume rocker raises the slider; touching the slider keeps it up. Counted
    // separately from `interactions` so that tapping anywhere else on the picture doesn't
    // silently extend an overlay the user is done with.
    // 取消 applies to this episode's credits only — the next one announces itself again.
    var nextUpDismissed by remember(state.currentIndex) { mutableStateOf(false) }
    var volumeSliderTouches by remember { mutableIntStateOf(0) }
    var volumeSliderVisible by remember { mutableStateOf(false) }
    LaunchedEffect(volumeKeyPresses) {
        // Nothing has been pressed yet on first composition; don't flash the slider up.
        if (volumeKeyPresses == 0L) return@LaunchedEffect
        volumeSliderVisible = true
    }
    LaunchedEffect(volumeKeyPresses, volumeSliderTouches, volumeSliderVisible, accessibilityManager) {
        if (!volumeSliderVisible) return@LaunchedEffect
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = VOLUME_SLIDER_HIDE_MS,
                containsIcons = true,
                containsText = true,
                containsControls = true,
            ) ?: VOLUME_SLIDER_HIDE_MS
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        volumeSliderVisible = false
    }
    LaunchedEffect(wakeRequests) {
        if (wakeRequests > 0) poke()
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
    LaunchedEffect(locked, lockedRevealRevision, interactions, screenReaderActive, accessibilityManager) {
        if (!locked) {
            lockedControlsVisible = false
            lockedExplained = false
            return@LaunchedEffect
        }
        lockedControlsVisible = true
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
        lockedControlsVisible = false
        lockedExplained = false
    }
    // A locked phone keeps the player: the edge swipe the lock is there to survive used to close it
    // outright. A television has no such swipe, and its Back unlocks through [closeTopRemoteLayer].
    // A failure takes the lock's place on screen, and Back is not held for a lock nobody can see.
    PlatformBackHandler(enabled = locked && state.error == null && remoteChrome == null, onBack = ::refuseWhileLocked)

    // 片尾接管下一集: the credits draw the picture into a corner with the next episode beside it.
    // Not the guest's to take, not a cast's, not under the lock or an automatic skip's countdown.
    var creditsTakeoverDismissed by remember(state.currentIndex) { mutableStateOf(false) }
    val creditsPhase by rememberCreditsTakeoverPhase(
        playback = playback,
        credits = skip.credits,
        blocked =
            creditsTakeoverDismissed ||
                nextUpDismissed ||
                watchLocked ||
                castingDeviceId != null ||
                locked ||
                skip.countdownSeconds != null ||
                gestureState.scanning,
    )
    val creditsTakeover = creditsPhase != CreditsTakeoverPhase.Off
    val latestOnCreditsTakeover by rememberUpdatedState(onCreditsTakeover)
    LaunchedEffect(creditsTakeover) { latestOnCreditsTakeover(creditsTakeover) }
    DisposableEffect(Unit) {
        onDispose { latestOnCreditsTakeover(false) }
    }

    // The remote at the end of an episode (TvPlayerPrompt.NextUp): neither card ever takes focus,
    // so OK over the picture plays the next episode and Back stays with the credits. Written through
    // the latest closures: the dismissals are remembered per episode, and a collector that outlives
    // one episode must not write the last one's.
    val nextUpCardShowing by remember(playback, nextUpDismissed) {
        derivedStateOf { nextUpCardVisible(playback.value, nextUpDismissed) }
    }
    val latestPlayNextFromRemote by rememberUpdatedState { onNextItem() }
    val latestKeepCreditsFromRemote by rememberUpdatedState<() -> Unit> {
        if (creditsPhase == CreditsTakeoverPhase.Card) {
            creditsTakeoverDismissed = true
        } else if (!nextUpDismissed) {
            nextUpDismissed = true
            onDismissNextUp()
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
            controlsVisible = visible,
            overlayOpen = remotePanel != null || creditsTakeover,
            locked = locked,
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
                    !locked &&
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
                insetEnd = if (watchChatOpen) WatchChatPanelWidth + 20.dp else 26.dp,
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
                        poke()
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
                            if (gestureState.releaseScan()) poke()
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
                                locked -> revealLock(explain = false)
                                watchChatOpen -> watchChatOpen = false
                                danmakuSendOpen -> danmakuSendOpen = false
                                danmakuSearchOpen -> danmakuSearchOpen = false
                                quickPopup != null -> quickPopup = null
                                settingsPanelKind != null -> settingsPanelKind = null
                                drawerOpen -> drawerOpen = false
                                burstSide != null -> {
                                    burstSeek(burstSide, offset, taps = 1)
                                    haptics.play(HapticSignal.Confirm)
                                }
                                // 点弹幕 with the chrome up only: with it away, a tap always brings it up first.
                                !locked && visible && latestExtras.onPictureTap(offset) -> {
                                    tips?.markUsed(Tips.PLAYER_DANMAKU_PICK)
                                }
                                visible -> visible = false
                                else -> poke()
                            }
                        },
                        onDoubleTap = { offset ->
                            // The lock's own catcher takes the touch before it gets here; this is the
                            // floor under it, so no path through the lock can seek or pause.
                            if (locked) {
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
                            poke()
                        },
                        onLongPress = { offset ->
                            if (locked) {
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
                            poke()
                        },
                    )
                }.pointerInput(
                    state.currentIndex,
                ) {
                    detectPlayerDragGestures(
                        canStart = { origin -> !locked && allowsPlayerDrag(origin.y, currentSystemGestureTop) },
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
                                poke()
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
                        canPinch = { origin -> !locked && allowsPlayerDrag(origin.y, currentSystemGestureTop) },
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
            visible = locked && errorMessage == null,
            modifier = Modifier.fillMaxSize(),
            coversScreen = true,
        ) {
            LockedOverlay(
                controlsVisible = lockedControlsVisible,
                message =
                    when {
                        !lockedExplained -> "屏幕已锁定"
                        remoteChrome != null -> "屏幕已锁定，按返回键解锁"
                        screenReaderActive -> "屏幕已锁定，请先解锁"
                        gestures.unlockByLongPress -> "屏幕已锁定，长按左侧锁键解锁"
                        else -> "屏幕已锁定，点按左侧锁键解锁"
                    },
                screenReaderActive = screenReaderActive,
                unlockByLongPress = gestures.unlockByLongPress,
                onReveal = { revealLock(explain = false) },
                onRefuse = ::refuseWhileLocked,
                onUnlock = {
                    if (locked) {
                        locked = false
                        poke()
                    }
                },
            )
        }
        ChromeVisibility(
            visible = !locked && errorMessage == null,
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
                ChromeVisibility(
                    visible = visible,
                    edge = ChromeEdge.Top,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    DisposableEffect(Unit) {
                        ambientPresenceChanged(true)
                        onDispose { ambientPresenceChanged(false) }
                    }
                    val readout by remember(playback, sourceLabel, containerLabel) {
                        derivedStateOf { playback.value.readoutLine(sourceLabel, containerLabel) }
                    }
                    RefinedTopBar(
                        title = episodes.getOrNull(state.currentIndex)?.title.orEmpty(),
                        subtitle = readout,
                        scaleMode = scaleMode,
                        dolbyVision = dolbyVision,
                        dolbyAtmos = dolbyAtmos,
                        onBack = onBack,
                        onEnterPictureInPicture = onEnterPictureInPicture,
                        onToggleFill = { stretch ->
                            poke()
                            onToggleFill(stretch)
                        },
                        onOpenCast = { openSettingsPanel(SettingsPanelKind.Cast) },
                        onOpenMore = { openSettingsPanel(SettingsPanelKind.More) },
                        ambientLight = ambientLight,
                        castActive = castActive,
                        watchConnected = watch.connected,
                        unreadChat =
                            watch.chatMessages.lastOrNull()?.id?.let { latest ->
                                lastReadChatId?.let { latest > it } ?: true
                            } ?: false,
                        onOpenChat = ::openWatchChat,
                        extras = extras,
                        onKeyActivity = ::poke,
                    )
                }

                ChromeVisibility(
                    visible = visible,
                    edge = ChromeEdge.Bottom,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    DisposableEffect(Unit) {
                        ambientPresenceChanged(true)
                        onDispose {
                            ambientPresenceChanged(false)
                            // A rail taken away mid-drag reports no end of its own.
                            gestureState.endScrub()
                        }
                    }
                    PlaybackTimelineContent(playback) { timelineState ->
                        val remoteSeek = remoteChromeState?.seekTargetMs?.takeIf { remoteChromeState.seeking }
                        RefinedBottomBar(
                            state =
                                timelineState.transportState().let { transport ->
                                    remoteSeek?.let { target ->
                                        transport.copy(
                                            positionMs =
                                                if (transport.durationMs >
                                                    0L
                                                ) {
                                                    target.coerceIn(0L, transport.durationMs)
                                                } else {
                                                    target.coerceAtLeast(0L)
                                                },
                                        )
                                    } ?: transport
                                },
                            seekLocked = watchLocked,
                            onPlayPause = {
                                poke()
                                onPlayPause()
                            },
                            onPrevious = {
                                poke()
                                onPreviousItem()
                            },
                            onNext = {
                                poke()
                                onNextItem()
                            },
                            onSeek = {
                                poke()
                                onSeek(it)
                            },
                            onScrub = {
                                // Every touch sample lands here. `interactions` is read by this
                                // whole control tree and keys two effects, so bumping it per sample
                                // rebuilt ~2,300 lines of chrome each frame of a drag. The hide timer
                                // already waits on `scrubbing`, and the release pokes it afresh.
                                if (gestureState.scrub()) interactions++
                            },
                            onScrubEnd = {
                                gestureState.endScrub()
                                poke()
                            },
                            trickplay = trickplay,
                            progressMarkers =
                                remember(
                                    skip.introStartSeconds,
                                    skip.introEndSeconds,
                                    skip.creditsLeadSeconds,
                                    state.durationMs,
                                    chapters,
                                ) {
                                    playbackProgressMarkers(skip, state.durationMs, chapters.asProgressChapters())
                                },
                            hasEpisodes = state.itemCount > 1,
                            onOpenEpisodes = {
                                onRefreshEpisodes()
                                openEpisodeDrawer()
                            },
                            hasMultipleSources = sourceOptions.size > 1,
                            onOpenSources = { openQuickPopup(QuickPopup.Source) },
                            onOpenSubtitles = {
                                openSettingsPanel(SettingsPanelKind.Tracks, TrackPanelMode.Subtitle)
                            },
                            onOpenAudio = {
                                openSettingsPanel(SettingsPanelKind.Tracks, TrackPanelMode.Audio)
                            },
                            onOpenSpeed = { openQuickPopup(QuickPopup.Speed) },
                            skipSettingsAvailable = skip.seriesName != null,
                            onOpenSkipSettings = { openSettingsPanel(SettingsPanelKind.Skip) },
                            danmakuEnabled = danmaku.enabled,
                            onToggleDanmaku = danmakuActions.onToggle,
                            onOpenDanmaku = { openSettingsPanel(SettingsPanelKind.Danmaku) },
                            // 进度条跟随作品取色: the series poster, or the episode still without one.
                            artworkUrl = episodes.getOrNull(state.currentIndex)?.let { it.posterUrl ?: it.stillUrl },
                            artworkIdentity = state.currentIndex,
                            ambientLight = ambientLight,
                            danmakuHeat = danmakuHeat,
                            onSeekBackwardLongPress = { rewindMissedLine() },
                            playKeyModifier =
                                if (remoteChrome != null) Modifier.focusRequester(playKeyFocus) else Modifier,
                        )
                    }
                }

                // 锁定 without the trip into 更多: the left edge's key, up with the rest of the chrome.
                // Touch screens only — a remote sends no stray touches for a lock to keep out.
                ChromeVisibility(
                    visible = visible && remoteChrome == null,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = LockKeyEdgePadding),
                ) {
                    PlayerLockKey(locked = false, onClick = ::lockScreen)
                }

                // 回到 12:34: a scan that ran past its mark is one tap from where it set out.
                val lastScanUndo = remember { arrayOf("") }
                gestureState.scanUndoMs?.let { lastScanUndo[0] = "回到 ${it.asClock()}" }
                ChromeVisibility(
                    visible = gestureState.scanUndoMs != null,
                    edge = ChromeEdge.Bottom,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                ) {
                    SkipPill(
                        label = lastScanUndo[0],
                        onClick = {
                            gestureState.takeScanUndo()?.let { origin ->
                                latestOnSeek(origin)
                                poke()
                            }
                        },
                    )
                }

                // Auto-skip is a small floating status chip. It is intentionally outside BottomBar's
                // Column so the progress rail never moves when the countdown appears or disappears.
                val lastAutoSkip = remember { arrayOf("", "") }
                skip.countdownSeconds?.let {
                    lastAutoSkip[0] = skipCountdownLabel(skip.segmentLabel, it, remote = remoteChrome != null)
                    lastAutoSkip[1] = skipCountdownAnnouncement(skip.segmentLabel)
                }
                ChromeVisibility(
                    visible = skip.countdownSeconds != null,
                    edge = ChromeEdge.Bottom,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .playerHintOffset(hintProgress, (-60).dp)
                            .padding(end = 22.dp, bottom = 24.dp),
                ) {
                    CompactAutoSkipPill(
                        label = lastAutoSkip[0],
                        announcement = lastAutoSkip[1],
                        onCancel = {
                            if (skip.countdownSeconds != null) {
                                poke()
                                skipActions.onCancelAuto()
                            }
                        },
                    )
                }
                val lastSkipLabel = remember { arrayOf("") }
                skip.segmentLabel?.let { lastSkipLabel[0] = it }
                val manualSkip =
                    shouldShowManualSkipPill(
                        segmentLabel = skip.segmentLabel,
                        countdownSeconds = skip.countdownSeconds,
                        controlsVisible = visible,
                        segmentJustEntered = skipSegmentJustEntered,
                    ) &&
                        !creditsTakeover
                ChromeVisibility(
                    visible = manualSkip,
                    edge = ChromeEdge.Bottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 92.dp),
                ) {
                    SkipPill(
                        label = lastSkipLabel[0],
                        onClick = {
                            if (manualSkip) {
                                poke()
                                skipActions.onSkip()
                            }
                        },
                    )
                }
                // A remote cannot reach the skip pills or the end-of-episode cards while the controls
                // are down, so OK over the picture acts on whichever is showing (TvRemoteInputController
                // reads this). A skip speaks first: it is the more urgent of the two.
                val remotePrompt =
                    when {
                        locked || errorMessage != null -> null
                        manualSkip || skip.countdownSeconds != null -> TvPlayerPrompt.Skip
                        nextUpCardShowing || creditsPhase == CreditsTakeoverPhase.Card -> TvPlayerPrompt.NextUp
                        else -> null
                    }
                DisposableEffect(remoteChrome, remotePrompt) {
                    remoteChrome?.publishPrompt(remotePrompt)
                    onDispose { remoteChrome?.publishPrompt(null) }
                }
                // Said beside the card, since nothing on it can take focus to say it.
                ChromeVisibility(
                    visible = remoteChrome != null && remotePrompt == TvPlayerPrompt.NextUp && !visible,
                    edge = ChromeEdge.End,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 60.dp),
                ) {
                    Text(
                        "按确定播放下一集 · 按返回看完片尾",
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }

                // Every playback function popup uses the same bottom-right anchor. Content may be
                // shorter or taller, but switching buttons never makes the surface jump position.
                val functionPopupModifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 70.dp)

                // The popovers and drawers below play their own way in and out; the presence only
                // keeps them composed while they do.
                PanelPresence(settingsPanelKind, modifier = Modifier.fillMaxSize()) { kind ->
                    BackOverlay(
                        onBack = { settingsPanelKind = null },
                        enabled = settingsPanelKind != null,
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
                            containerLabel = containerLabel,
                            engineOptions = engineOptions,
                            transcodeLabel = transcodeLabel,
                            transcodeActive = transcodeActive,
                            castDevices = castDevices,
                            castingDeviceId = castingDeviceId,
                            castDiscovering = castDiscovering,
                            castError = castError,
                            castStatus = castStatus,
                            castPosition = castPosition,
                            castPositionSource = castPositionSource,
                            castCapabilities = castCapabilities,
                            danmaku = danmaku,
                            danmakuActions = danmakuActions,
                            onOpenDanmakuSearch = {
                                settingsPanelKind = null
                                danmakuActions.onOpenSearch()
                                danmakuSearchOpen = true
                            },
                            onOpenDanmakuSend = {
                                settingsPanelKind = null
                                danmakuSendOpen = true
                            },
                            // Picking a track closes the panel, which used to be the only sign
                            // anything had happened — and the picture behind it rarely says so
                            // within the second. The HUD the gestures already use answers it.
                            onSelectSubtitle = { id ->
                                // A pick is the viewer's own: 没听清 steps aside without putting anything back.
                                endSubtitlePeek(null)
                                onSelectSubtitle(id)
                                gestureState.say("字幕 · ${trackLabel(state.subtitleTracks, id)}")
                                settingsPanelKind = null
                            },
                            subtitleControls = subtitleControls,
                            subtitleActions = subtitleActions,
                            bookmarks = bookmarks,
                            bookmarkActions =
                                bookmarkActions.copy(onSeek = { position ->
                                    if (watchLocked) {
                                        gestureState.say("房主控制播放")
                                    } else {
                                        onSeek(position.coerceIn(0L, state.durationMs.coerceAtLeast(0L)))
                                        settingsPanelKind = null
                                    }
                                }),
                            remoteSubtitles = remoteSubtitles,
                            remoteSubtitleActions = remoteSubtitleActions,
                            audioControls = audioControls,
                            audioActions = audioActions,
                            onSelectAudio = { id ->
                                onSelectAudio(id)
                                gestureState.say("音轨 · ${trackLabel(state.audioTracks, id)}")
                                settingsPanelKind = null
                            },
                            sleepTimer = sleepTimer,
                            sleepTimerActions = sleepTimerActions,
                            onSelectEngine = {
                                onSelectEngine(it)
                                settingsPanelKind = null
                            },
                            onTranscode = {
                                onTranscode()
                                settingsPanelKind = null
                            },
                            onResetAdaptiveLearning = {
                                onResetAdaptiveLearning()
                                settingsPanelKind = null
                            },
                            onNextDiscTitle = onNextDiscTitle,
                            onNextDiscChapter = onNextDiscChapter,
                            onShowDiscMenu = onShowDiscMenu,
                            onExternalPlayer = onExternalPlayer,
                            onDiscoverCast = onDiscoverCast,
                            onCastTo = onCastTo,
                            onStopCast = onStopCast,
                            onLock = ::lockScreen,
                            onOpenGestureHelp = {
                                settingsPanelKind = null
                                gestureHelpOpen = true
                            },
                            watch = watch,
                            onOpenWatchTogether = {
                                settingsPanelKind = null
                                watchDialogOpen = true
                            },
                            versions = versions,
                            selectedVersionId = selectedVersionId,
                            onSelectVersion = {
                                onSelectVersion(it)
                                settingsPanelKind = null
                            },
                            skip = skip,
                            // The panel stays open: setting a boundary is something you check against
                            // the picture behind it, and often two of the three in one visit.
                            skipActions = skipActions,
                            trackPanelMode = trackPanelMode,
                            ambientLightEnabled = ambientLightEnabled,
                            onToggleAmbientLight = onToggleAmbientLight,
                            onDismiss = { settingsPanelKind = null },
                        )
                    }
                }

                PanelPresence(quickPopup, modifier = Modifier.fillMaxSize()) { popup ->
                    BackOverlay(onBack = { quickPopup = null }, enabled = quickPopup != null) {
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
                                        options = sourceOptions,
                                        selectedId = selectedSourceId,
                                        onSelect = {
                                            onSelectSource(it)
                                            quickPopup = null
                                        },
                                        onDismiss = { quickPopup = null },
                                    )

                                QuickPopup.Speed ->
                                    SpeedPickerPopup(
                                        speeds = SPEEDS,
                                        selectedSpeed = state.speed,
                                        onSelect = {
                                            onSpeed(it)
                                            quickPopup = null
                                        },
                                        onDismiss = { quickPopup = null },
                                    )
                            }
                        }
                    }
                }

                if (gestureHelpOpen) {
                    PlayerGestureHelpOverlay(onDismiss = { gestureHelpOpen = false }, gestures = gestures)
                }

                if (watchDialogOpen) {
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
                            watchDialogOpen = false
                        },
                        onRequestControl = room.onRequestControl,
                        onSetControlMode = room.onSetControlMode,
                        onSetModerator = room.onSetModerator,
                        onKickParticipant = room.onKickParticipant,
                        onDismiss = { watchDialogOpen = false },
                    )
                }

                PanelPresence(Unit.takeIf { watchChatOpen && watch.connected }, modifier = Modifier.fillMaxSize()) {
                    BackOverlay(
                        onBack = closeWatchChat,
                        enabled = watchChatOpen,
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
                    visible = !watchChatOpen && chatPreviewVisible && watch.chatMessages.isNotEmpty(),
                    edge = ChromeEdge.Top,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .playerHintOffset(hintProgress, 52.dp)
                            .padding(top = 18.dp, end = 22.dp),
                ) {
                    WatchChatPreview(
                        messages = watch.chatMessages,
                        onOpen = ::openWatchChat,
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

                ChromeVisibility(visible = drawerOpen, edge = ChromeEdge.Bottom, modifier = Modifier.fillMaxSize()) {
                    BackOverlay(
                        enabled = drawerOpen,
                        onBack = { drawerOpen = false },
                    ) {
                        EpisodeStrip(
                            episodes = episodes,
                            currentIndex = state.currentIndex,
                            onSelect =
                                if (watchLocked) {
                                    // Guests can still browse what's in the room's queue; picking is the
                                    // host's move, so tapping explains itself instead of doing nothing.
                                    { gestureState.say("房主控制播放") }
                                } else {
                                    {
                                        onSelectItem(it)
                                        drawerOpen = false
                                    }
                                },
                            onDismiss = { drawerOpen = false },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                PanelPresence(Unit.takeIf { danmakuSearchOpen }, modifier = Modifier.fillMaxSize()) {
                    BackOverlay(
                        enabled = danmakuSearchOpen,
                        onBack = { danmakuSearchOpen = false },
                    ) {
                        DanmakuSearchPanel(
                            state = danmaku,
                            // Picking closes the sheet: the choice is made, and the result of it is
                            // the 弹幕 now running over the picture the sheet is covering.
                            actions =
                                danmakuActions.copy(
                                    onPickEpisode = {
                                        danmakuActions.onPickEpisode(it)
                                        danmakuSearchOpen = false
                                    },
                                ),
                            onDismiss = { danmakuSearchOpen = false },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }

                if (danmakuSendOpen) {
                    DanmakuSendDialog(
                        sending = danmaku.sending,
                        error = danmaku.sendError,
                        onSend = {
                            danmakuActions.onSend(it)
                            danmakuSendOpen = false
                        },
                        onDismiss = { danmakuSendOpen = false },
                    )
                }

                ChromeVisibility(
                    visible = watch.connected && visible,
                    edge = ChromeEdge.Top,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 74.dp),
                ) {
                    WatchRoomNote(
                        reconnecting = watch.reconnecting,
                        isHost = watch.isHost,
                        participantCount = watch.participantCount,
                        onOpenChat = ::openWatchChat,
                    )
                }

                // Standing, like the paused key: the picture is on another screen whether or not the
                // controls are up. It rides below the title bar while that is shown.
                val lastCastStatus = remember { arrayOf("") }
                castStatus?.let { lastCastStatus[0] = it }
                ChromeVisibility(
                    visible = castActive && castStatus != null,
                    edge = ChromeEdge.Top,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .playerHintOffset(hintProgress, 56.dp)
                            .padding(start = 22.dp, top = 18.dp),
                ) {
                    CastSessionPill(
                        status = lastCastStatus[0],
                        onOpen = { openSettingsPanel(SettingsPanelKind.Cast) },
                        onDisconnect = {
                            poke()
                            onStopCast()
                        },
                        announce = castError != null,
                    )
                }

                val stoppedAtItemEnd by remember(playback) {
                    derivedStateOf { playbackStoppedAtItemEnd(playback.value) }
                }

                /**
                 * Paused, with one tap back into playback.
                 *
                 * A double tap in the middle of the frame pauses, and the controls it raised fade a
                 * few seconds later — leaving a still frame with nothing on it to say the film is
                 * paused rather than stalled, and no way back that does not start with a tap to bring
                 * the controls round again. This outlives the control overlay for that reason.
                 *
                 * Not while buffering: `playing` is false throughout startup and every seek, and a
                 * resume button over a frame that is already coming back is a lie. Not once the item
                 * has ended or stopped at its end either — the ending's keys below own that moment,
                 * and "paused" would be the wrong word for it.
                 *
                 * A guest whose room is driven by its host still needs to be told the film is paused,
                 * so the key is drawn for them too — dimmed and inert, since the tap would only be
                 * refused. That is the whole difference between the two states, which is why it is
                 * one control and not two: the pair that used to cover this drew a 28dp 暂停 badge
                 * underneath a translucent 64dp 播放 disc, so both were on screen at once and the
                 * smaller one showed through the larger.
                 */
                val showPausedKey =
                    !state.playing &&
                        !state.buffering &&
                        !state.ended &&
                        state.error == null &&
                        !stoppedAtItemEnd
                // Beneath the 继续播放 key, so that key still resumes. Any other touch, and Back, bring
                // the chrome up, which is what they were for; the layer leaves with the pause it needs.
                PauseInfoLayer(
                    shown = pauseInfoShown,
                    playback = playback,
                    chapters = chapters,
                    onDismiss = ::poke,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp),
                )
                /**
                 * The end of an item that did not roll on into the next one, where nothing used to be.
                 *
                 * A film, the last entry in a series, or an episode that stopped at its end because
                 * 自动播放下一集 is off (or 取消 or the sleep timer said so). The picture stops on its
                 * final frame with no controls, nothing saying the episode is over rather than
                 * stalled, and no way on that does not start with a tap to summon the chrome. Keys at
                 * the same size and in the same place as 继续播放, because it is the same question —
                 * what happens if I touch this — asked one moment later; 下一集 leads when there is one.
                 *
                 * 重播 runs the ending's split backwards before its keys go, and they stay up for that.
                 */
                var endingFlowsBack by remember { mutableStateOf(false) }
                val showEndedKeys = stoppedAtItemEnd || endingFlowsBack
                PlayerCenterKeys(
                    showPausedKey = showPausedKey,
                    showEndedKeys = showEndedKeys,
                    watchLocked = watchLocked,
                    hasNext = state.hasNext,
                    onResume = {
                        onPlayPause()
                        poke()
                    },
                    onNext = {
                        poke()
                        onNextItem()
                    },
                    onReplay = {
                        if (playback.value.ended) {
                            // Back to the first frame, and playing again: the engine reports
                            // the ended item as paused, so the seek alone would leave it
                            // standing on frame one.
                            latestOnSeek(0L)
                            if (!playback.value.playing) onPlayPause()
                        } else {
                            // Parked on the last frame instead of ended: resuming would run
                            // into the next item before the seek landed, so the item is
                            // started again from the top.
                            onSelectItem(state.currentIndex)
                        }
                        poke()
                    },
                    onBack = onBack,
                    onHold = { endingFlowsBack = it },
                )

                // Taught once each, while their gesture is in reach; see [PlayerGestureTips].
                PlayerGestureTips(
                    chromeUp = visible && remoteChrome == null && !locked,
                    seekable = state.durationMs > 0L && !watchLocked,
                    speedBoostable = !watch.connected && castingDeviceId == null,
                    subtitlesAvailable = state.subtitleTracks.isNotEmpty(),
                    danmakuShowing = danmaku.enabled && danmaku.count > 0,
                    fineScrubArmed = gestureState.fineScrubArmed,
                    gestures = gestures,
                )

                // Where the title bar sits — it has stepped aside for the hold — and clear of the
                // subtitles at the bottom and the gesture HUD in the middle.
                SpeedBoostPill(
                    gear = gestureState.boostGear,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
                )

                // 全程缩略图: the frame a swipe across the picture, or a held side, has got to — or,
                // on a television, a held fast-forward or rewind on the remote.
                PictureScrubPreview(
                    storyboard = trickplay,
                    positionMs = { remoteChromeState?.holdPreviewMs ?: gestureState.previewMs },
                    chapters = chapters,
                    modifier = Modifier.align(Alignment.Center),
                )

                // Suppressed while the resume button occupies the same spot: the double tap that
                // pauses would otherwise stack "暂停" directly on top of it.
                PlayerGestureHud(
                    hud = { gestureState.hud },
                    suppressed = showPausedKey || showEndedKeys,
                    modifier = Modifier.align(Alignment.Center),
                )

                SeekBurstFeedback(
                    gestureState.pulseRevision - pulseItemStart,
                    gestureState.pulsePosition,
                    state.currentIndex,
                )
                ChromeVisibility(
                    visible = volumeSliderVisible,
                    edge = ChromeEdge.End,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp),
                ) {
                    VolumeSlider(
                        volume = volume,
                        onVolume = { target ->
                            volumeSliderTouches++
                            onVolume(target)
                        },
                        modifier = Modifier,
                    )
                }

                // Where the ordinary card appears, which takes over from this one for the last seconds.
                ChromeVisibility(
                    visible = creditsPhase == CreditsTakeoverPhase.Card,
                    edge = ChromeEdge.End,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 96.dp),
                ) {
                    CreditsTakeoverCard(
                        title = episodes.getOrNull(state.currentIndex + 1)?.title.orEmpty(),
                        // The picture comes back and the credits play on; the ordinary card still
                        // counts down at the very end.
                        onWatchCredits = { creditsTakeoverDismissed = true },
                        onPlayNext = {
                            if (creditsPhase == CreditsTakeoverPhase.Card) {
                                poke()
                                onNextItem()
                            }
                        },
                    )
                }

                PlayerNextUpOverlay(
                    playback,
                    episodes,
                    nextUpDismissed,
                    onPlayNow = {
                        poke()
                        onNextItem()
                    },
                    onDismiss = {
                        poke()
                        nextUpDismissed = true
                        onDismissNextUp()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 96.dp),
                    autoAdvance = autoNext,
                )
            }
        }

        // Last in the box, so it covers the chrome on its way out rather than fading in under it.
        var showProblem by remember { mutableStateOf(false) }
        if (showProblem) PlaybackProblemDialog(playback = playback, onDismiss = { showProblem = false })
        ChromeContent(errorMessage, modifier = Modifier.fillMaxSize(), coversScreen = true) { message ->
            val otherVersions =
                versions
                    .filter { (id, _) -> id != selectedVersionId }
                    .take(MAX_ERROR_ALTERNATIVES)
                    .map { (id, label) -> "版本 · $label" to { onSelectVersion(id) } }
            // One strategy on offer (a native-only package) is no alternative to itself, whichever
            // row happens to be marked: reloading it replays the same path into the same failure.
            val otherEngines =
                engineOptions
                    .takeIf { it.size > 1 }
                    .orEmpty()
                    .mapIndexedNotNull { index, (label, selected) ->
                        if (selected) null else label to { onSelectEngine(index) }
                    }.take(MAX_ERROR_ALTERNATIVES)
            PlaybackErrorOverlay(
                message = message,
                onRetry = onRetry,
                onExternalPlayer = onExternalPlayer,
                onBack = onBack,
                alternatives = otherVersions + otherEngines,
                onExplain = { showProblem = true },
            )
        }
    }
}
