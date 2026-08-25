package com.yfuse.feature.player

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackOverlay
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.glass
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 5_000L
private const val CHAT_PREVIEW_MS = 4_000L
private const val GESTURE_HUD_MS = 1_600L

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

internal fun shouldShowManualSkipPill(
    segmentLabel: String?,
    countdownSeconds: Int?,
    controlsVisible: Boolean,
): Boolean = controlsVisible && countdownSeconds == null && segmentLabel != null

/**
 * 长按快进/快退 — how fast the playhead runs while a press is held down.
 *
 * Holding used to jump to 2× playback, which is a different thing than it looks like:
 * the picture keeps playing and the finger has to stay down to keep it there, so
 * skipping a minute of credits meant holding for thirty seconds and watching them. A
 * held press now runs along the timeline instead, at [HOLD_SEEK_STEP_MS] per
 * [HOLD_SEEK_TICK_MS] — 10× to start, [HOLD_SEEK_FAST_STEP_MS] (30×) once the press has
 * lasted [HOLD_SEEK_RAMP_MS]. This keeps short holds precise while still allowing a long
 * hold to cross an episode.
 *
 * The engine is now sought every 300ms while held, so the decoded picture visibly follows
 * the HUD without hammering a remote direct-play stream with frame-rate-frequency seeks.
 */
private const val HOLD_SEEK_TICK_MS = 300L
private const val HOLD_SEEK_STEP_MS = 3_000L
private const val HOLD_SEEK_FAST_STEP_MS = 9_000L
private const val HOLD_SEEK_RAMP_MS = 3_000L

/**
 * Settings use one consistent floating panel and one consistent chip family.
 *
 * The player chrome, transcribed from the prototype's landscape player: a gradient
 * top bar, a centred transport cluster, a gradient bottom bar with the scrubber and
 * chip row, plus the lock screen, settings panel and episode drawer.
 *
 * Everything shown comes from [state], so ExoPlayer and libmpv get the same controls.
 */
@Composable
internal fun PlayerControls(
    state: PlaybackState,
    // The queue, as the strip and the title bar both read it.
    episodes: List<EpisodeCard>,
    filled: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectItem: (Int) -> Unit,
    onPreviousItem: () -> Boolean,
    onNextItem: () -> Boolean,
    onRefreshEpisodes: () -> Unit,
    onSelectAudio: (String) -> Unit,
    audioControls: AudioControlState = AudioControlState(),
    audioActions: AudioControlActions = AudioControlActions(),
    onSelectSubtitle: (String) -> Unit,
    subtitleControls: SubtitleControlState = SubtitleControlState(),
    subtitleActions: SubtitleControlActions = SubtitleControlActions(),
    remoteSubtitles: RemoteSubtitlePanelState = RemoteSubtitlePanelState(),
    remoteSubtitleActions: RemoteSubtitleActions = RemoteSubtitleActions(),
    onSpeed: (Float) -> Unit,
    sleepTimer: SleepTimerState = SleepTimerState(),
    sleepTimerActions: SleepTimerActions = SleepTimerActions(),
    onToggleFill: () -> Unit,
    trickplay: TrickplayStoryboard? = null,
    /*
     * System volume, 0f..1f, and its setter — read by the right-edge drag gesture and by the
     * slider the volume rocker raises. There is no on-screen volume control any more.
     */
    volume: Float = 0f,
    onVolume: (Float) -> Unit = {},
    /*
     * Increments on each volume key press. Any change raises the vertical slider; the value
     * itself is meaningless, which is what lets a press at the volume ceiling still show it.
     */
    volumeKeyPresses: Long = 0L,
    // Current window brightness, 0f..1f. Vertical drags on the left half adjust it.
    brightness: Float = 0.5f,
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
    castPosition: String? = null,
    castCapabilities: String? = null,
    onDiscoverCast: () -> Unit = {},
    onCastTo: (String) -> Unit = {},
    onStopCast: () -> Unit = {},
    danmaku: DanmakuPanelState = DanmakuPanelState(),
    danmakuActions: DanmakuPanelActions = DanmakuPanelActions(),
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
    watch: WatchRoomState = WatchRoomState(),
    watchActions: WatchRoomActions = WatchRoomActions(),
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
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
    var gestureHud by remember { mutableStateOf<String?>(null) }
    var controlsHaveFocus by remember { mutableStateOf(false) }
    // -1 while a held press is rewinding, +1 while it is fast-forwarding, 0 when no press
    // is held. [holdSeekTarget] is the last position already sent to the playback engine.
    var holdSeekDirection by remember { mutableIntStateOf(0) }
    var holdSeekTarget by remember { mutableLongStateOf(0L) }
    // The app's own vocabulary, not Compose's two-constant one. These two call sites were
    // the last `HapticFeedbackType.LongPress` standing in for something it is not — a
    // confirmed scrub and a refused one, played identically. [HapticSignal.Reject] existed
    // for exactly the locked case and had never been called from anywhere.
    val haptics = LocalHaptics.current
    val accessibilityManager = LocalAccessibilityManager.current
    // Bumped by every interaction so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }
    val latestPosition by rememberUpdatedState(state.positionMs)
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

    LaunchedEffect(
        visible,
        locked,
        settingsPanelKind,
        quickPopup,
        drawerOpen,
        danmakuSearchOpen,
        danmakuSendOpen,
        watchChatOpen,
        state.playing,
        interactions,
        accessibilityManager,
        controlsHaveFocus,
    ) {
        val overlayOpen =
            gestureHelpOpen ||
                settingsPanelKind != null ||
                quickPopup != null ||
                drawerOpen ||
                danmakuSearchOpen ||
                danmakuSendOpen ||
                watchChatOpen
        if (
            !visible ||
            !state.playing ||
            overlayOpen ||
            controlsHaveFocus
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
    LaunchedEffect(gestureHud, accessibilityManager) {
        if (gestureHud != null) {
            val timeout =
                accessibilityManager?.calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis = GESTURE_HUD_MS,
                    containsIcons = false,
                    containsText = true,
                    containsControls = false,
                ) ?: GESTURE_HUD_MS
            if (timeout == Long.MAX_VALUE) return@LaunchedEffect
            delay(timeout)
            gestureHud = null
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
    // Runs for as long as the press is held; cancelled by the release setting the
    // direction back to 0. Re-stamping the HUD every tick also keeps the 850ms
    // auto-clear above from taking it away mid-hold.
    LaunchedEffect(holdSeekDirection) {
        val direction = holdSeekDirection
        if (direction == 0) return@LaunchedEffect
        var heldMs = 0L
        while (true) {
            val span = latestDuration.coerceAtLeast(1L)
            val step = if (heldMs < HOLD_SEEK_RAMP_MS) HOLD_SEEK_STEP_MS else HOLD_SEEK_FAST_STEP_MS
            holdSeekTarget = (holdSeekTarget + direction * step).coerceIn(0L, span)
            // Real seek while held: decoded video follows the timeline instead of waiting for release.
            latestOnSeek(holdSeekTarget)
            gestureHud = "${if (direction < 0) "快退" else "快进"} " +
                "${holdSeekTarget.asClock()} / ${span.asClock()}"
            delay(HOLD_SEEK_TICK_MS)
            heldMs += HOLD_SEEK_TICK_MS
        }
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

    Box(
        modifier
            .fillMaxSize()
            // Fullscreen video may extend under a cutout or rounded edge; interactive chrome
            // and the gesture detector stay inside the actual safe landscape width.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .onFocusChanged { controlsHaveFocus = it.hasFocus }
            .focusGroup(),
    ) {
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
                // Keyed on nothing: `settingsPanelKind`, `drawerOpen` and `visible` are read
                // through their state delegates below, so the detector already sees the
                // current values without being torn down. Keying on them meant any of
                // them changing restarted the gesture stream mid-press — which the hold
                // to fast-forward cannot survive, since it is the release that lands the
                // seek and `poke()` flips `visible` the moment the hold starts.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            // The engine is already following every held tick; release only stops it.
                            tryAwaitRelease()
                            if (holdSeekDirection != 0) {
                                holdSeekDirection = 0
                                poke()
                            }
                        },
                        onTap = {
                            when {
                                watchChatOpen -> watchChatOpen = false
                                danmakuSendOpen -> danmakuSendOpen = false
                                danmakuSearchOpen -> danmakuSearchOpen = false
                                quickPopup != null -> quickPopup = null
                                settingsPanelKind != null -> settingsPanelKind = null
                                drawerOpen -> drawerOpen = false
                                visible -> visible = false
                                else -> poke()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (latestWatchLocked) {
                                gestureHud = "房主控制播放"
                                haptics.play(HapticSignal.Reject)
                            } else {
                                when {
                                    offset.x < size.width / 3f -> {
                                        latestOnSeek(
                                            (latestPosition - 10_000L)
                                                .coerceIn(0L, latestDuration),
                                        )
                                        gestureHud = "快退 10 秒"
                                    }
                                    offset.x > size.width * 2f / 3f -> {
                                        latestOnSeek(
                                            (latestPosition + 10_000L)
                                                .coerceIn(0L, latestDuration),
                                        )
                                        gestureHud = "快进 10 秒"
                                    }
                                    else -> {
                                        latestOnPlayPause()
                                        gestureHud = if (state.playing) "暂停" else "播放"
                                    }
                                }
                                haptics.play(HapticSignal.Confirm)
                            }
                            poke()
                        },
                        onLongPress = { offset ->
                            // Left half rewinds, right half fast-forwards — the same split
                            // the double tap already uses, so one gesture explains the other.
                            when {
                                latestWatchLocked -> {
                                    gestureHud = "房主控制播放"
                                    haptics.play(HapticSignal.Reject)
                                }
                                latestDuration <= 0L -> Unit
                                else -> {
                                    holdSeekTarget = latestPosition
                                    holdSeekDirection = if (offset.x < size.width / 2f) -1 else 1
                                    // A hold that has taken hold — the same signal a long
                                    // press gets everywhere else in the app.
                                    haptics.play(HapticSignal.Confirm)
                                }
                            }
                            poke()
                        },
                    )
                }.pointerInput(
                    state.currentIndex,
                ) {
                    var totalX = 0f
                    var totalY = 0f
                    var startX = 0f
                    var seekTarget = latestPosition
                    var volumeAtDragStart = latestVolume
                    var brightnessAtDragStart = latestBrightness
                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            totalX = 0f
                            totalY = 0f
                            seekTarget = latestPosition
                            volumeAtDragStart = latestVolume
                            brightnessAtDragStart = latestBrightness
                        },
                        onDragEnd = {
                            if (
                                holdSeekDirection == 0 &&
                                abs(totalX) > abs(totalY) &&
                                latestDuration > 0 &&
                                !latestWatchLocked
                            ) {
                                latestOnSeek(seekTarget)
                            }
                            poke()
                        },
                        onDragCancel = { gestureHud = null },
                    ) { change, amount ->
                        change.consume()
                        // A finger that drifts while held is still holding, not scrubbing:
                        // the hold owns the timeline until it lets go.
                        if (holdSeekDirection != 0) return@detectDragGestures
                        totalX += amount.x
                        totalY += amount.y
                        if (abs(totalX) > abs(totalY)) {
                            // Brightness/volume drags stay available to guests; only the
                            // horizontal scrub is the host's to make.
                            if (latestWatchLocked) {
                                gestureHud = "房主控制播放"
                                return@detectDragGestures
                            }
                            val span = latestDuration.coerceAtLeast(1L)
                            seekTarget =
                                (
                                    latestPosition + totalX / size.width * span * 0.45f
                                ).toLong().coerceIn(0L, span)
                            gestureHud = "${seekTarget.asClock()} / ${span.asClock()}"
                        } else {
                            val delta = -totalY / size.height
                            if (startX < size.width / 2f) {
                                val target = (brightnessAtDragStart + delta).coerceIn(0.02f, 1f)
                                latestOnBrightness(target)
                                gestureHud = "亮度 ${(target * 100).toInt()}%"
                            } else {
                                val target = (volumeAtDragStart + delta).coerceIn(0f, 1f)
                                latestOnVolume(target)
                                gestureHud = "音量 ${(target * 100).toInt()}%"
                            }
                        }
                    }
                },
        )

        state.error?.let { message ->
            PlaybackErrorOverlay(
                message = message,
                onRetry = onRetry,
                onBack = onBack,
            )
            return@Box
        }

        if (locked) {
            LockedOverlay(onUnlock = {
                locked = false
                poke()
            })
            return@Box
        }

        // Top-level actions (投屏/更多) live with the title; media navigation stays below.
        ChromeVisibility(
            visible = visible,
            edge = ChromeEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            RefinedTopBar(
                title = episodes.getOrNull(state.currentIndex)?.title.orEmpty(),
                subtitle = state.readoutLine(sourceLabel, containerLabel),
                filled = filled,
                dolbyVision = dolbyVision,
                dolbyAtmos = dolbyAtmos,
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onToggleFill = {
                    poke()
                    onToggleFill()
                },
                onOpenCast = { openSettingsPanel(SettingsPanelKind.Cast) },
                onOpenMore = { openSettingsPanel(SettingsPanelKind.More) },
                watchConnected = watch.connected,
                unreadChat =
                    watch.chatMessages.lastOrNull()?.id?.let { latest ->
                        lastReadChatId?.let { latest > it } ?: true
                    } ?: false,
                onOpenChat = ::openWatchChat,
            )
        }

        ChromeVisibility(
            visible = visible,
            edge = ChromeEdge.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            RefinedBottomBar(
                state = state,
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
                onScrub = { interactions++ },
                trickplay = trickplay,
                progressMarkers = playbackProgressMarkers(skip, state.durationMs),
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
                onOpenDanmaku = { openSettingsPanel(SettingsPanelKind.Danmaku) },
            )
        }

        // Auto-skip is a small floating status chip. It is intentionally outside BottomBar's
        // Column so the progress rail never moves when the countdown appears or disappears.
        skip.countdownSeconds?.let { seconds ->
            CompactAutoSkipPill(
                label = skipCountdownLabel(skip.segmentLabel, seconds),
                onCancel = {
                    poke()
                    skipActions.onCancelAuto()
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 22.dp,
                            bottom = if (visible) 84.dp else 24.dp,
                        ),
            )
        }

        if (
            shouldShowManualSkipPill(
                segmentLabel = skip.segmentLabel,
                countdownSeconds = skip.countdownSeconds,
                controlsVisible = visible,
            )
        ) {
            SkipPill(
                label = checkNotNull(skip.segmentLabel),
                onClick = {
                    poke()
                    skipActions.onSkip()
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 92.dp),
            )
        }

        // Every playback function popup uses the same bottom-right anchor. Content may be
        // shorter or taller, but switching buttons never makes the surface jump position.
        val functionPopupModifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 70.dp)

        settingsPanelKind?.let { kind ->
            BackOverlay(
                onBack = { settingsPanelKind = null },
            ) {
                SettingsPanel(
                    modifier = functionPopupModifier,
                    kind = kind,
                    state = state,
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
                    onSelectSubtitle = {
                        onSelectSubtitle(it)
                        settingsPanelKind = null
                    },
                    subtitleControls = subtitleControls,
                    subtitleActions = subtitleActions,
                    remoteSubtitles = remoteSubtitles,
                    remoteSubtitleActions = remoteSubtitleActions,
                    audioControls = audioControls,
                    audioActions = audioActions,
                    onSelectAudio = {
                        onSelectAudio(it)
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
                    onDiscoverCast = onDiscoverCast,
                    onCastTo = onCastTo,
                    onStopCast = onStopCast,
                    onLock = {
                        settingsPanelKind = null
                        locked = true
                        visible = true
                    },
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
                    onDismiss = { settingsPanelKind = null },
                )
            }
        }

        quickPopup?.let { popup ->
            BackOverlay(onBack = { quickPopup = null }) {
                when (popup) {
                    QuickPopup.Source ->
                        SourcePickerPopup(
                            options = sourceOptions,
                            selectedId = selectedSourceId,
                            onSelect = {
                                onSelectSource(it)
                                quickPopup = null
                            },
                            onDismiss = { quickPopup = null },
                            modifier = functionPopupModifier,
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
                            modifier = functionPopupModifier,
                        )
                }
            }
        }

        if (gestureHelpOpen) {
            PlayerGestureHelpOverlay(onDismiss = { gestureHelpOpen = false })
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

        if (watchChatOpen && watch.connected) {
            BackOverlay(
                onBack = closeWatchChat,
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
                    .padding(top = if (visible) 70.dp else 18.dp, end = 22.dp),
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

        if (drawerOpen) {
            BackOverlay(
                onBack = { drawerOpen = false },
            ) {
                EpisodeStrip(
                    episodes = episodes,
                    currentIndex = state.currentIndex,
                    onSelect =
                        if (watchLocked) {
                            // Guests can still browse what's in the room's queue; picking is the
                            // host's move, so tapping explains itself instead of doing nothing.
                            { gestureHud = "房主控制播放" }
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

        if (danmakuSearchOpen) {
            BackOverlay(
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

        // Standing explanation for why the transport is dimmed. Also the only place the
        // reconnect state surfaces during playback — the room stays live and controls stay
        // in place, so a dropped socket reads as "catching up", not as the room vanishing.
        if (watch.connected && visible) {
            val roomNote =
                when {
                    watch.reconnecting -> "一起看 · 重连中… · 聊天"
                    !watch.isHost -> "一起看 · 房主控制播放 · 聊天"
                    else -> "一起看 · 你是房主 · ${watch.participantCount} 人 · 聊天"
                }
            Text(
                roomNote,
                style = AppTypography.caption.medium,
                color = if (watch.reconnecting) DarkPalette.onErrorContainer else Color.White.copy(alpha = 0.92f),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 74.dp)
                        .glass(
                            shape = GlassShapes.chip,
                            fill =
                                if (watch.reconnecting) {
                                    DarkPalette.errorContainer
                                } else {
                                    Color.Black.copy(alpha = 0.52f)
                                },
                            border = Color.White.copy(alpha = 0.24f),
                        ).noRippleClickable(::openWatchChat)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
            )
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
         * has ended either — 下一集 owns that moment, and "paused" would be the wrong word
         * for it.
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
                state.error == null
        if (showPausedKey) {
            CircleControl(
                // 播放, never 暂停. This is an affordance, not a readout — it says what the
                // tap does, the way every transport key in the app does.
                icon = AppIcons.Play,
                description = if (watchLocked) "已暂停，等待房主继续" else "继续播放",
                size = CenterKeySize,
                iconSize = CenterKeyIconSize,
                enabled = !watchLocked,
                // The one control that has to be found at a glance in a dark room, so it
                // takes the filled treatment the transport keys leave to it.
                filled = true,
                onClick = {
                    onPlayPause()
                    poke()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Suppressed while the resume button occupies the same spot: the double tap that
        // pauses would otherwise stack "暂停" directly on top of it.
        gestureHud?.takeIf { !showPausedKey }?.let { value ->
            Text(
                value,
                style = AppTypography.body.strong,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .glass(
                            shape = AppShapes.pill,
                            fill = Color.Black.copy(alpha = 0.56f),
                            border = Color.White.copy(alpha = 0.24f),
                        ).padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }

        if (volumeSliderVisible) {
            VolumeSlider(
                volume = volume,
                onVolume = { target ->
                    volumeSliderTouches++
                    onVolume(target)
                },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 26.dp),
            )
        }

        if (state.hasNext && state.durationMs > 0L && !nextUpDismissed) {
            val remainingMs = state.durationMs - state.positionMs
            if (remainingMs in 1L..NEXT_UP_WINDOW_MS) {
                NextUpCard(
                    title = episodes.getOrNull(state.currentIndex + 1)?.title.orEmpty(),
                    remainingMs = remainingMs,
                    onPlayNow = {
                        poke()
                        onNextItem()
                    },
                    onDismiss = {
                        poke()
                        nextUpDismissed = true
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 22.dp, bottom = 96.dp),
                )
            }
        }
    }
}
