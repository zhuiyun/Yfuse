package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * How long the player's own chrome takes to arrive or leave.
 *
 * Shorter than [Motion.MODAL]: this is the most-repeated animation in the app — a two-hour
 * film is dozens of taps — and the one thing it must never do is stand between the user and
 * the picture. Long enough to read as a movement, short enough that nobody waits for it.
 *
 * None of this existed. `if (visible) { TopBar(); BottomBar() }` was a bare conditional, and
 * so were the settings panel, the episode drawer and both 弹幕 panels: every surface in the
 * player appeared and vanished between two frames, in the one part of the app where chrome
 * is *supposed* to come and go politely over content the user is watching.
 */
private const val CHROME_MS = 220

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 4_000L
private const val CHAT_PREVIEW_MS = 4_000L

/**
 * How long the volume slider stays up after the last press or drag.
 *
 * Shorter than [AUTO_HIDE_MS]: it covers part of the picture and answers a question that
 * has already been answered by the time the sound changes.
 */
private const val VOLUME_SLIDER_HIDE_MS = 1_600L

/** A forgiving touch target around the visually slim playback track. */
private val SeekBarTouchHeight = 28.dp

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * 长按快进/快退 — how fast the playhead runs while a press is held down.
 *
 * Holding used to jump to 2× playback, which is a different thing than it looks like:
 * the picture keeps playing and the finger has to stay down to keep it there, so
 * skipping a minute of credits meant holding for thirty seconds and watching them. A
 * held press now runs along the timeline instead, at [HOLD_SEEK_STEP_MS] per
 * [HOLD_SEEK_TICK_MS] — 20× to start, [HOLD_SEEK_FAST_STEP_MS] (60×) once the press has
 * lasted [HOLD_SEEK_RAMP_MS], so a short hold nudges and a long one crosses an episode.
 *
 * Like the horizontal drag, this previews: the HUD tracks the target and the engine is
 * only asked to seek once, on release. Seeking every tick would mean twenty seeks a
 * second at a remote server that answers each one by rebuilding the stream.
 */
private const val HOLD_SEEK_TICK_MS = 150L
private const val HOLD_SEEK_STEP_MS = 3_000L
private const val HOLD_SEEK_FAST_STEP_MS = 9_000L
private const val HOLD_SEEK_RAMP_MS = 3_000L

/** Settings use one consistent floating panel and one consistent chip family. */
internal enum class Tab(val label: String) {
    Danmaku("弹幕"),
    Subtitle("字幕"),
    Cast("投屏"),
    Diagnostics("诊断"),
    More("更多"),
}

/**
 * The player chrome, transcribed from the prototype's landscape player: a gradient
 * top bar, a centred transport cluster, a gradient bottom bar with the scrubber and
 * chip row, plus the lock screen, settings panel and episode drawer.
 *
 * Everything shown comes from [state], so ExoPlayer and libmpv get the same controls.
 */
@Composable
internal fun PlayerControls(
    state: PlaybackState,
    /** The queue, as the strip and the title bar both read it. */
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
    onSelectSubtitle: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFill: () -> Unit,
    /**
     * System volume, 0f..1f, and its setter — read by the right-edge drag gesture and by the
     * slider the volume rocker raises. There is no on-screen volume control any more.
     */
    volume: Float = 0f,
    onVolume: (Float) -> Unit = {},
    /**
     * Increments on each volume key press. Any change raises the vertical slider; the value
     * itself is meaningless, which is what lets a press at the volume ceiling still show it.
     */
    volumeKeyPresses: Long = 0L,
    /** Current window brightness, 0f..1f. Vertical drags on the left half adjust it. */
    brightness: Float = 0.5f,
    onBrightness: (Float) -> Unit = {},
    /** Engine picker rows: label to selected. */
    engineOptions: List<Pair<String, Boolean>> = emptyList(),
    onSelectEngine: (Int) -> Unit = {},
    /** Null when the active engine has no transcode fallback. */
    transcodeLabel: String? = null,
    transcodeActive: Boolean = false,
    onTranscode: () -> Unit = {},
    castDevices: List<Pair<String, String>> = emptyList(),
    castingDeviceId: String? = null,
    castDiscovering: Boolean = false,
    castError: String? = null,
    onDiscoverCast: () -> Unit = {},
    onCastTo: (String) -> Unit = {},
    onStopCast: () -> Unit = {},
    danmaku: DanmakuPanelState = DanmakuPanelState(),
    danmakuActions: DanmakuPanelActions = DanmakuPanelActions(),
    /** The server this file is on. Null when there is only ever one server to be on. */
    sourceLabel: String? = null,
    /** `MKV` — the container, which the engine cannot report but the library knows. */
    containerLabel: String? = null,
    dolbyVision: Boolean = false,
    dolbyAtmos: Boolean = false,
    /** Files the server holds for this entry; a picker appears once there are two. */
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
    var settingsTab by remember { mutableStateOf<Tab?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var watchDialogOpen by remember { mutableStateOf(false) }
    var watchChatOpen by remember { mutableStateOf(false) }
    var chatPreviewVisible by remember { mutableStateOf(false) }
    var previewRoomCode by remember { mutableStateOf<String?>(null) }
    var lastPreviewedChatId by remember { mutableStateOf<Long?>(null) }
    var lastReadChatId by remember { mutableStateOf<Long?>(null) }
    var danmakuSearchOpen by remember { mutableStateOf(false) }
    var danmakuSendOpen by remember { mutableStateOf(false) }
    var gestureHud by remember { mutableStateOf<String?>(null) }
    // -1 while a held press is rewinding, +1 while it is fast-forwarding, 0 when no press
    // is held. [holdSeekTarget] is where the timeline has run to, committed on release.
    var holdSeekDirection by remember { mutableIntStateOf(0) }
    var holdSeekTarget by remember { mutableLongStateOf(0L) }
    // The app's own vocabulary, not Compose's two-constant one. These two call sites were
    // the last `HapticFeedbackType.LongPress` standing in for something it is not — a
    // confirmed scrub and a refused one, played identically. [HapticSignal.Reject] existed
    // for exactly the locked case and had never been called from anywhere.
    val haptics = LocalHaptics.current
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
    val room = remember {
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
        settingsTab = null
        drawerOpen = false
        danmakuSearchOpen = false
        danmakuSendOpen = false
        watchDialogOpen = false
        watchChatOpen = true
        lastReadChatId = watch.chatMessages.lastOrNull()?.id
        chatPreviewVisible = false
        poke()
    }

    // Also stable for the life of the panel, and for the same reason: read through the
    // transcript rather than closing over this frame's copy of it.
    val latestChatMessages by rememberUpdatedState(watch.chatMessages)
    val closeWatchChat = remember {
        {
            watchChatOpen = false
            lastReadChatId = latestChatMessages.lastOrNull()?.id
        }
    }

    PlatformBackHandler(enabled = watchChatOpen, onBack = closeWatchChat)

    LaunchedEffect(
        visible,
        locked,
        settingsTab,
        drawerOpen,
        danmakuSearchOpen,
        danmakuSendOpen,
        watchChatOpen,
        state.playing,
        interactions,
    ) {
        val overlayOpen = settingsTab != null || drawerOpen || danmakuSearchOpen ||
            danmakuSendOpen || watchChatOpen
        if (!visible || !state.playing || overlayOpen) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        visible = false
    }
    LaunchedEffect(gestureHud) {
        if (gestureHud != null) {
            delay(850)
            gestureHud = null
        }
    }
    LaunchedEffect(
        watch.roomCode,
        watch.chatMessages.lastOrNull()?.id,
        watchChatOpen,
        watch.chatPreviewEnabled,
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
            delay(CHAT_PREVIEW_MS)
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
    LaunchedEffect(volumeKeyPresses, volumeSliderTouches, volumeSliderVisible) {
        if (!volumeSliderVisible) return@LaunchedEffect
        delay(VOLUME_SLIDER_HIDE_MS)
        volumeSliderVisible = false
    }

    Box(modifier.fillMaxSize()) {
        if (watch.connected) {
            WatchChatDanmakuOverlay(
                roomCode = watch.roomCode,
                messages = watch.chatMessages,
                enabled = watch.chatDanmakuEnabled,
                // The panel covers the picture, so nothing flies while it is up — but it is
                // held, not dropped. Sending is done from inside that panel, and 弹幕 that
                // skips the sender is 弹幕 the sender cannot tell they sent.
                held = watchChatOpen,
            )
            // Above the danmaku and below the controls: a reaction is meant to be seen
            // over the picture, never to swallow a tap aimed at 播放.
            WatchReactionOverlay(
                reactions = watch.reactions,
                onFinished = room.onReactionFinished,
            )
        }

        // Tap catcher sits below the controls, so buttons win the gesture.
        Box(
            Modifier
                .fillMaxSize()
                // Keyed on nothing: `settingsTab`, `drawerOpen` and `visible` are read
                // through their state delegates below, so the detector already sees the
                // current values without being torn down. Keying on them meant any of
                // them changing restarted the gesture stream mid-press — which the hold
                // to fast-forward cannot survive, since it is the release that lands the
                // seek and `poke()` flips `visible` the moment the hold starts.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            // Fires on every press; only a press that turned into a hold
                            // has a seek to land. `tryAwaitRelease` also returns after the
                            // long-press path consumes its way to the up event.
                            tryAwaitRelease()
                            if (holdSeekDirection != 0) {
                                val target = holdSeekTarget
                                holdSeekDirection = 0
                                latestOnSeek(target)
                                poke()
                            }
                        },
                        onTap = {
                            when {
                                watchChatOpen -> watchChatOpen = false
                                danmakuSendOpen -> danmakuSendOpen = false
                                danmakuSearchOpen -> danmakuSearchOpen = false
                                settingsTab != null -> settingsTab = null
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
                }
                .pointerInput(
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
                            seekTarget = (
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
                }
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
            LockedOverlay(onUnlock = { locked = false; poke() })
            return@Box
        }

        // 播放器 chrome — the top bar drops from the top edge, the transport row rises from
        // the bottom, and both crossfade. See [ChromeVisibility].
        ChromeVisibility(
            visible = visible,
            edge = ChromeEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                title = episodes.getOrNull(state.currentIndex)?.title.orEmpty(),
                subtitle = state.readoutLine(sourceLabel, containerLabel),
                filled = filled,
                hasEpisodes = state.itemCount > 1,
                dolbyVision = dolbyVision,
                dolbyAtmos = dolbyAtmos,
                onBack = onBack,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onOpenDrawer = {
                    poke()
                    onRefreshEpisodes()
                    drawerOpen = true
                },
                onToggleFill = { poke(); onToggleFill() },
                watchConnected = watch.connected,
                unreadChat = watch.chatMessages.lastOrNull()?.id?.let { latest ->
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
            BottomBar(
                state = state,
                seekLocked = watchLocked,
                onPlayPause = { poke(); onPlayPause() },
                onPrevious = { poke(); onPreviousItem() },
                onNext = { poke(); onNextItem() },
                onRewind = { poke(); onSeek((state.positionMs - 10_000L).coerceAtLeast(0L)) },
                onForward = { poke(); onSeek(state.positionMs + 10_000L) },
                skipCountdownLabel = skip.countdownSeconds?.let {
                    skipCountdownLabel(skip.segmentLabel, it)
                },
                onCancelAutoSkip = { poke(); skipActions.onCancelAuto() },
                onSeek = { poke(); onSeek(it) },
                onScrub = { interactions++ },
                onOpenTab = { poke(); settingsTab = it },
                casting = castingDeviceId != null,
                danmakuEnabled = danmaku.enabled,
                onOpenDanmaku = {
                    poke()
                    settingsTab = Tab.Danmaku
                },
                onToggleCast = {
                    poke()
                    settingsTab = Tab.Cast
                    if (castDevices.isEmpty()) onDiscoverCast()
                },
            )
        }

        // A stable paused-state marker stays in the middle even after chrome auto-hides.
        // The icon names the state; tapping it resumes, and a centre double-tap does the same.
        if (!state.playing && !state.buffering && state.error == null && !locked) {
            CircleControl(
                icon = AppIcons.Pause,
                description = "已暂停，点击继续",
                size = 42.dp,
                iconSize = 17.dp,
                enabled = !watchLocked,
                filled = true,
                interactive = false,
                onClick = {},
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // An armed countdown replaces the manual pill rather than sitting beside it — both
        // would be about the same segment, and the countdown already skips on its own.
        // While the controls are up it is drawn under the progress bar by [BottomBar]; this
        // is the same pill for when they aren't.
        when {
            skip.countdownSeconds != null && !visible -> SkipPill(
                label = skipCountdownLabel(skip.segmentLabel, skip.countdownSeconds),
                onClick = { poke(); skipActions.onCancelAuto() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 24.dp),
            )

            skip.countdownSeconds == null && skip.segmentLabel != null -> SkipPill(
                label = skip.segmentLabel,
                onClick = { poke(); skipActions.onSkip() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = if (visible) 92.dp else 24.dp),
            )
        }

        // The panel outlives `settingsTab` by one animation, so the tab it was showing has to
        // outlive it too — otherwise the content blanks on the frame the exit begins.
        var lastSettingsTab by remember { mutableStateOf<Tab?>(null) }
        LaunchedEffect(settingsTab) { settingsTab?.let { lastSettingsTab = it } }
        ChromeVisibility(visible = settingsTab != null) {
            lastSettingsTab?.let { tab ->
            SettingsPanel(
                tab = tab,
                state = state,
                speeds = SPEEDS,
                engineOptions = engineOptions,
                transcodeLabel = transcodeLabel,
                transcodeActive = transcodeActive,
                castDevices = castDevices,
                castingDeviceId = castingDeviceId,
                castDiscovering = castDiscovering,
                castError = castError,
                danmaku = danmaku,
                danmakuActions = danmakuActions,
                onOpenDanmakuSearch = {
                    settingsTab = null
                    danmakuActions.onOpenSearch()
                    danmakuSearchOpen = true
                },
                onOpenDanmakuSend = {
                    settingsTab = null
                    danmakuSendOpen = true
                },
                onTab = { settingsTab = it },
                onSelectSubtitle = { onSelectSubtitle(it); settingsTab = null },
                onSelectAudio = { onSelectAudio(it); settingsTab = null },
                onSpeed = { onSpeed(it); settingsTab = null },
                onSelectEngine = { onSelectEngine(it); settingsTab = null },
                onTranscode = { onTranscode(); settingsTab = null },
                onDiscoverCast = onDiscoverCast,
                onCastTo = onCastTo,
                onStopCast = onStopCast,
                onLock = {
                    settingsTab = null
                    locked = true
                    visible = true
                },
                watch = watch,
                onOpenWatchTogether = {
                    settingsTab = null
                    watchDialogOpen = true
                },
                versions = versions,
                selectedVersionId = selectedVersionId,
                onSelectVersion = { onSelectVersion(it); settingsTab = null },
                skip = skip,
                // The panel stays open: setting a boundary is something you check against
                // the picture behind it, and often two of the three in one visit.
                skipActions = skipActions,
                onDismiss = { settingsTab = null },
            )
            }
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

        ChromeVisibility(
            visible = watchChatOpen && watch.connected,
            edge = ChromeEdge.End,
            modifier = Modifier.align(Alignment.CenterEnd),
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
                onReact = room.onReact,
                onDismiss = closeWatchChat,
            )
        }

        // The preview is what the panel replaces, so it is gated on the panel being shut
        // rather than chained to it — an `else` here would have torn the preview down on the
        // frame the panel started opening, before either had moved.
        ChromeVisibility(
            visible = !watchChatOpen && chatPreviewVisible && watch.chatMessages.isNotEmpty(),
            edge = ChromeEdge.Top,
            modifier = Modifier
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

        ChromeVisibility(
            visible = drawerOpen,
            edge = ChromeEdge.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            EpisodeStrip(
                episodes = episodes,
                currentIndex = state.currentIndex,
                onSelect = if (watchLocked) {
                    // Guests can still browse what's in the room's queue; picking is the
                    // host's move, so tapping explains itself instead of doing nothing.
                    { gestureHud = "房主控制播放" }
                } else {
                    { onSelectItem(it); drawerOpen = false }
                },
                onDismiss = { drawerOpen = false },
            )
        }

        ChromeVisibility(
            visible = danmakuSearchOpen,
            edge = ChromeEdge.End,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            DanmakuSearchPanel(
                state = danmaku,
                // Picking closes the sheet: the choice is made, and the result of it is
                // the 弹幕 now running over the picture the sheet is covering.
                actions = danmakuActions.copy(
                    onPickEpisode = {
                        danmakuActions.onPickEpisode(it)
                        danmakuSearchOpen = false
                    },
                ),
                onDismiss = { danmakuSearchOpen = false },
            )
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
            val roomNote = when {
                watch.reconnecting -> "一起看 · 重连中… · 聊天"
                !watch.isHost -> "一起看 · 房主控制播放 · 聊天"
                else -> "一起看 · 你是房主 · ${watch.participantCount} 人 · 聊天"
            }
            Text(
                roomNote,
                style = sc(11.5f, 600),
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp)
                    .glass(
                        shape = GlassShapes.chip,
                        fill = if (watch.reconnecting) {
                            Brand.Danger.copy(alpha = 0.42f)
                        } else {
                            Color.Black.copy(alpha = 0.52f)
                        },
                        border = Color.White.copy(alpha = 0.24f),
                    )
                    .noRippleClickable(::openWatchChat)
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
         * resume button over a frame that is already coming back is a lie. Not for a guest
         * whose room is driven by its host either — the tap would only be refused.
         */
        val showResume = !state.playing &&
            !state.buffering &&
            !state.ended &&
            state.error == null &&
            !watchLocked
        if (showResume) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .glass(
                        shape = CircleShape,
                        fill = Color.Black.copy(alpha = 0.46f),
                        border = Color.White.copy(alpha = 0.28f),
                    )
                    .noRippleClickable {
                        onPlayPause()
                        poke()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Play,
                    contentDescription = "继续播放",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Suppressed while the resume button occupies the same spot: the double tap that
        // pauses would otherwise stack "暂停" directly on top of it.
        gestureHud?.takeIf { !showResume }?.let { value ->
            Text(
                value,
                style = sc(15f, 700),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .glass(
                        shape = continuousRounded(22.dp),
                        fill = Color.Black.copy(alpha = 0.56f),
                        border = Color.White.copy(alpha = 0.24f),
                    )
                    .padding(horizontal = 22.dp, vertical = 13.dp),
            )
        }

        if (volumeSliderVisible) {
            VolumeSlider(
                volume = volume,
                onVolume = { target ->
                    volumeSliderTouches++
                    onVolume(target)
                },
                modifier = Modifier
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
                    onPlayNow = { poke(); onNextItem() },
                    onDismiss = { poke(); nextUpDismissed = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 96.dp),
                )
            }
        }
    }
}

/** How long before the end 下一集 announces itself. */
private const val NEXT_UP_WINDOW_MS = 10_000L

/**
 * 片尾自动连播 — the countdown the spec drew and nobody built.
 *
 * [PlayerTokens.nextUpFill], `nextUpRing`, `nextUpRingTrack` and `nextUpCore` were all
 * declared for this card and referenced nowhere in the app; what shipped instead was one
 * line of text reading 「下一集将在 N 秒后播放」, with no way to start it early and no way
 * to stop it. The ring drains as the episode does, the core starts the next one on tap,
 * and 取消 leaves the credits alone.
 */
@Composable
private fun NextUpCard(
    title: String,
    remainingMs: Long,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = (remainingMs.toFloat() / NEXT_UP_WINDOW_MS).coerceIn(0f, 1f)
    Row(
        modifier
            .shadow(Shadows.tabBar, GlassShapes.card)
            .glass(
                shape = GlassShapes.card,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text("即将播放", style = mr(9.5f, 700), color = PlayerTokens.footerText)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = sc(12f, 700),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            }
        }
        Text(
            "取消",
            style = sc(11.5f, 600),
            color = PlayerTokens.timeText,
            modifier = Modifier
                .pressable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(
            Modifier.size(38.dp).pressable(haptic = HapticSignal.Confirm, onClick = onPlayNow),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f
                drawCircle(
                    color = PlayerTokens.nextUpCore,
                    radius = radius - stroke / 2f,
                )
                drawCircle(
                    color = PlayerTokens.nextUpRingTrack,
                    radius = radius,
                    style = Stroke(width = stroke),
                )
                // Drains clockwise from the top as the episode runs out.
                drawArc(
                    color = PlayerTokens.nextUpRing,
                    startAngle = -90f,
                    sweepAngle = -360f * (1f - progress),
                    useCenter = false,
                    topLeft = Offset(
                        (size.width - radius * 2f) / 2f,
                        (size.height - radius * 2f) / 2f,
                    ),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Icon(
                AppIcons.Play,
                contentDescription = "立即播放下一集",
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private fun Long.asClock(): String {
    val seconds = (this / 1_000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("播放遇到问题", style = sc(17f, 700), color = Color.White)
            Text(
                message,
                style = mr(12f, 400),
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "返回",
                    style = sc(12f, 600),
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier
                        .glass(
                            shape = continuousRounded(18.dp),
                            fill = Color.White.copy(alpha = 0.10f),
                            border = Color.White.copy(alpha = 0.28f),
                        )
                        .noRippleClickable(onBack)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    "重试",
                    style = sc(12f, 700),
                    color = Color(0xFF1B2436),
                    modifier = Modifier
                        .glass(
                            shape = continuousRounded(18.dp),
                            fill = Color.White.copy(alpha = 0.68f),
                            border = Color.White.copy(alpha = 0.88f),
                        )
                        .noRippleClickable(onRetry)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** `padding:14px 22px`, `linear-gradient(180deg,rgba(0,0,0,.5),transparent)`. */
@Composable
private fun TopBar(
    title: String,
    /**
     * `alphatv · 1080P · EXO · HEVC · 18.1 Mbps · 60.0 fps` — where this is coming from and
     * how it is actually being played.
     *
     * This line used to be `1080P · 音轨 2 · 字幕 3`, which counts things the 字幕 tab
     * already lists and answers no question anyone has while watching. What is worth
     * standing here is the pair that cannot be found anywhere else without opening a panel:
     * which server the file is on, and whether it is direct-playing at full bitrate. Both
     * are what you look at when the picture stutters.
     */
    subtitle: String,
    filled: Boolean,
    hasEpisodes: Boolean,
    dolbyVision: Boolean,
    dolbyAtmos: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleFill: () -> Unit,
    watchConnected: Boolean,
    unreadChat: Boolean,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    180f,
                    0f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                ),
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleControl(AppIcons.Close, "关闭播放器", 28.dp, 12.dp, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        title,
                        style = sc(14f, 700),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Only what this file actually carries — the same rule the detail page
                    // follows. A badge that is always there says nothing.
                    if (dolbyVision) DolbyChip("VISION", Color.White.copy(alpha = 0.88f))
                    if (dolbyAtmos) DolbyChip("ATMOS", Color.White.copy(alpha = 0.88f))
                }
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = mr(10f, 500),
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (watchConnected) {
                CircleControl(
                    AppIcons.Chat,
                    if (unreadChat) "房间聊天，有新消息" else "房间聊天",
                    28.dp,
                    12.dp,
                    filled = unreadChat,
                    onClick = onOpenChat,
                )
            }
            if (hasEpisodes) {
                CircleControl(AppIcons.Menu, "剧集列表", 28.dp, 12.dp, onClick = onOpenDrawer)
            }
            CircleControl(
                AppIcons.PictureInPicture,
                "小窗播放",
                28.dp,
                12.dp,
                onClick = onEnterPictureInPicture,
            )
            CircleControl(
                icon = if (filled) AppIcons.Collapse else AppIcons.Expand,
                description = "切换画面比例",
                size = 28.dp,
                iconSize = 12.dp,
                onClick = onToggleFill,
            )
        }
    }
}

/**
 * 快退 / 播放 / 快进 — 26 / 30 / 26 rings at the left of the bottom bar.
 *
 * It used to float in the centre of the frame at 48 / 58 / 48, with a filled white disc on
 * the play button. That is the worst place to put anything: the middle of a shot is where
 * the subject is, so the one control that is always on screen was always over a face. Down
 * here it shares the gradient the scrubber already needs, and the picture keeps its middle.
 *
 * Rings rather than plates, and small enough that the row reads as one strip with the
 * chips opposite it. The touch target does not shrink with the ring — see [CircleControl].
 *
 * [locked] dims the cluster to half opacity and stops it taking taps: a connected guest can
 * see what the room is doing but does not drive it. Dimming rather than hiding keeps the
 * transport where the eye expects it and makes the reason legible alongside the
 * 「房主控制播放」 banner.
 */
@Composable
private fun TransportRow(
    state: PlaybackState,
    locked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.graphicsLayer { alpha = if (locked) 0.45f else 1f },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleControl(
            AppIcons.Previous,
            "上一集",
            26.dp,
            12.dp,
            enabled = !locked && state.hasPrevious,
            onClick = onPrevious,
        )
        CircleControl(
            AppIcons.Rewind,
            "快退 10 秒",
            26.dp,
            13.dp,
            enabled = !locked,
            onClick = onRewind,
        )

        if (state.buffering) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            CircleControl(
                if (state.playing) AppIcons.Pause else AppIcons.Play,
                if (state.playing) "暂停" else "播放",
                30.dp,
                14.dp,
                enabled = !locked,
                filled = true,
                onClick = onPlayPause,
            )
        }

        CircleControl(
            AppIcons.Forward,
            "快进 10 秒",
            26.dp,
            13.dp,
            enabled = !locked,
            onClick = onForward,
        )
        CircleControl(
            AppIcons.Next,
            "下一集",
            26.dp,
            12.dp,
            enabled = !locked && state.hasNext,
            onClick = onNext,
        )
    }
}

/**
 * The vertical volume bar the rocker raises, in place of the system's own panel.
 *
 * Draggable rather than a read-only readout: once it is on screen and under the thumb, the
 * remaining distance is usually more than a couple of rocker steps, and the alternative
 * (the edge-drag gesture) means dismissing this first.
 *
 * Fill grows upward, so the gesture matches both the rocker and the bar's own shape.
 */
@Composable
private fun VolumeSlider(volume: Float, onVolume: (Float) -> Unit, modifier: Modifier = Modifier) {
    val fraction = volume.coerceIn(0f, 1f)
    var height by remember { mutableIntStateOf(1) }
    Column(
        modifier
            .glass(
                shape = continuousRounded(22.dp),
                fill = Color.Black.copy(alpha = 0.56f),
                border = Color.White.copy(alpha = 0.24f),
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(fraction * 100).toInt()}", style = mr(11f, 700), color = Color.White)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(140.dp)
                .clip(continuousRounded(3.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    // Bottom of the track is 0, top is 1 — hence the inversion.
                    detectTapGestures { offset ->
                        onVolume((1f - offset.y / height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        onVolume((1f - change.position.y / height).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Muted draws no fill at all rather than a zero-height sliver.
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(continuousRounded(3.dp))
                        .background(Color.White),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/**
 * The pill offering to move the playhead past a 片头 / 片尾.
 *
 * Deliberately outside the show/hide of the rest of the controls: the offer is only good
 * for as long as playback is inside the segment, and making the user summon the controls
 * first would spend a chunk of that window.
 */
@Composable
private fun SkipPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = sc(12.5f, 700),
        color = Color.White,
        modifier = modifier
            .glass(
                shape = continuousRounded(18.dp),
                fill = Color.Black.copy(alpha = 0.64f),
                border = Color.White.copy(alpha = 0.28f),
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** "片头结束 · 90 秒" / "片头结束 · 未设置". */
internal fun skipBoundaryLabel(name: String, seconds: Long): String =
    if (seconds > 0L) "$name · $seconds 秒" else "$name · 未设置"

/**
 * "片尾开始 · 距结束 120 秒" / "片尾开始 · 未设置".
 *
 * Spelled out rather than reusing [skipBoundaryLabel] because the number means the
 * opposite thing: 120 here is near the end of the episode, not two minutes into it.
 */
internal fun skipCreditsLabel(seconds: Long): String =
    if (seconds > 0L) "片尾开始 · 距结束 $seconds 秒" else "片尾开始 · 未设置"

/** "3 秒后跳过片头 · 点击取消" — the label says what will happen and how to stop it. */
private fun skipCountdownLabel(skipSegmentLabel: String?, seconds: Int): String {
    // 跳过片头 -> 片头. The type's own label is the only place this wording lives.
    val what = skipSegmentLabel?.removePrefix("跳过").orEmpty()
    return "$seconds 秒后跳过$what · 点击取消"
}

/** `padding:14px 22px 16px`, `linear-gradient(0deg,rgba(0,0,0,.55),transparent)`. */
@Composable
private fun BottomBar(
    state: PlaybackState,
    /** Guest in a room: the scrubber becomes a read-only progress indicator. */
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    /** Non-null while an automatic skip is counting down; shown under the progress row. */
    skipCountdownLabel: String?,
    onCancelAutoSkip: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    casting: Boolean,
    danmakuEnabled: Boolean,
    onOpenDanmaku: () -> Unit,
    onToggleCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Non-null only while the thumb is held; the engine's position is ignored then
    // so the bar doesn't snap back between ticks.
    var scrubbed by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = scrubbed ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val bufferedFraction = if (state.durationMs > 0L) {
        (state.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownPosition = scrubbed?.let { scrubPositionMs(it, state.durationMs) } ?: state.positionMs

    Column(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    0f,
                    0f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            )
            .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 16.dp),
    ) {
        // Progress row — `gap:10px`, `400 11px Manrope`, `rgba(255,255,255,.75)`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(formatTime(shownPosition), style = mr(11f, 400), color = PlayerTokens.timeTextLandscape)
            SeekBar(
                fraction = fraction,
                bufferedFraction = bufferedFraction,
                enabled = !seekLocked && state.durationMs > 0L,
                onScrubTo = {
                    scrubbed = it
                    onScrub()
                },
                onCommit = {
                    onSeek(scrubPositionMs(it, state.durationMs))
                    scrubbed = null
                },
                onCancel = { scrubbed = null },
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(state.durationMs), style = mr(11f, 400), color = PlayerTokens.timeTextLandscape)
        }

        if (skipCountdownLabel != null) {
            Spacer(Modifier.height(8.dp))
            SkipPill(
                label = skipCountdownLabel,
                onClick = onCancelAutoSkip,
                modifier = Modifier.align(Alignment.End),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Transport lives here now rather than floating in the middle of the frame.
                // A 58dp disc centred on the picture is 58dp of picture you cannot see, and
                // it lands on the subject's face more often than not — the one part of the
                // shot the controls should never be over. Along the bottom edge it sits on
                // the gradient that is already there for the scrubber.
                TransportRow(
                    state = state,
                    locked = seekLocked,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onRewind = onRewind,
                    onForward = onForward,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(
                    AppIcons.Danmaku,
                    "弹幕",
                    26.dp,
                    12.dp,
                    filled = danmakuEnabled,
                    onClick = onOpenDanmaku,
                )
                // 字幕与音轨在同一个面板 tab 里（[Tab.Subtitle] 两组都列），所以这里
                // 只出一个 chip；先前的两个 chip 打开的是同一块面板，纯粹重复。
                val hasSubtitles = state.subtitleTracks.isNotEmpty()
                val hasAudioChoice = state.audioTracks.size > 1
                if (hasSubtitles || hasAudioChoice) {
                    CircleControl(
                        AppIcons.Subtitle,
                        if (hasSubtitles && hasAudioChoice) "字幕与音轨" else if (hasSubtitles) {
                            "字幕"
                        } else {
                            "音轨"
                        },
                        26.dp,
                        12.dp,
                        onClick = { onOpenTab(Tab.Subtitle) },
                    )
                }
                CircleControl(
                    AppIcons.Cast,
                    if (casting) "停止投送" else "投屏",
                    26.dp,
                    12.dp,
                    filled = casting,
                    onClick = onToggleCast,
                )
                CircleControl(
                    AppIcons.More,
                    "更多",
                    26.dp,
                    12.dp,
                    onClick = { onOpenTab(Tab.More) },
                )
            }
        }
    }
}

/**
 * `4px` track, `radius:2px`, `rgba(255,255,255,.24)`, filled with
 * `linear-gradient(90deg,#7FA2E8,#A7C0F2)`. Tap to seek, drag to scrub.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    bufferedFraction: Float,
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val latestOnScrubTo by rememberUpdatedState(onScrubTo)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnCancel by rememberUpdatedState(onCancel)

    Box(
        modifier
            // Keep the painted track at 4dp while the whole 28dp row accepts the gesture.
            .height(SeekBarTouchHeight)
            .let { base ->
                if (!enabled) return@let base
                base
                    .pointerInput(enabled) {
                        detectTapGestures { offset ->
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            latestOnCommit((offset.x / width).coerceIn(0f, 1f))
                        }
                    }.pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                dragging = true
                                dragFraction = (offset.x / width).coerceIn(0f, 1f)
                                latestOnScrubTo(dragFraction)
                            },
                            onDragEnd = {
                                dragging = false
                                latestOnCommit(dragFraction)
                            },
                            onDragCancel = {
                                dragging = false
                                latestOnCancel()
                            },
                        ) { change, _ ->
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                            latestOnScrubTo(dragFraction)
                        }
                    }
            }.padding(vertical = if (dragging) 11.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .clip(continuousRounded(2.dp))
                .background(PlayerTokens.trackFillLandscape),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                    .clip(continuousRounded(2.dp))
                    .background(Color.White.copy(alpha = 0.44f)),
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(continuousRounded(2.dp))
                    .background(PlayerTokens.progress),
            )
        }
    }
}

/** Converts a scrubber fraction to a clamped media position. */
internal fun scrubPositionMs(
    fraction: Float,
    durationMs: Long,
): Long {
    val duration = durationMs.coerceAtLeast(0L)
    return (fraction.coerceIn(0f, 1f).toDouble() * duration).toLong().coerceIn(0L, duration)
}

/**
 * 字幕 / 音轨 / 投屏 / 更多 are one control family, so they share one height, one radius
 * and one material. Only the width flexes — a label needs more room than a glyph, and a
 * text chip that shrank to fit its text used to sit two thirds the height of the icon
 * ones next to it.
 */
private val ChipHeight = 40.dp
private val ChipMinWidth = 46.dp
private val ChipShape = continuousRounded(14.dp)

/** Labelled chip — `radius:14px`, `600 11.5px Manrope`, `rgba(255,255,255,.92)`. */
@Composable
private fun Chip(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(ChipHeight)
            .widthIn(min = ChipMinWidth)
            .glass(
                shape = ChipShape,
                fill = if (active) Brand.Primary.copy(alpha = 0.7f) else PlayerTokens.chipFill,
                border = Color.White.copy(alpha = if (active) 0.36f else 0.24f),
            )
            .noRippleClickable(onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = mr(11.5f, 600),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
        )
    }
}

/** Glyph chip — same box as [Chip], sized to [ChipMinWidth] since it holds one icon. */
@Composable
private fun IconChip(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(ChipMinWidth)
            .height(ChipHeight)
            .glass(
                shape = ChipShape,
                fill = if (active) {
                    Brand.Primary.copy(alpha = 0.7f)
                } else {
                    PlayerTokens.chipFill
                },
                border = if (active) {
                    Color.White.copy(alpha = 0.36f)
                } else {
                    Color.White.copy(alpha = 0.24f)
                },
            )
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** `rgba(255,255,255,.16)` circle over a `rgba(255,255,255,.28)` hairline. */
@Composable
private fun CircleControl(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    interactive: Boolean = true,
    /** Filled rather than outlined. The one control that earns it is 播放/暂停. */
    filled: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The ring is what you see; the touch target is bigger than the ring. Sizing them
    // together is what made these controls big enough to cover a face — a 48dp disc over
    // the middle of the picture is 48dp of picture you cannot see.
    Box(
        modifier
            .size(size + ControlTouchPadding * 2)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .let { if (enabled && interactive) it.noRippleClickable(onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .border(1.dp, Color.White.copy(alpha = if (filled) 0.42f else 0.62f), CircleShape)
                .let {
                    if (filled) it.background(PlayerTokens.playFill, CircleShape) else it
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (filled) PlayerTokens.onPlay else Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Slack around a control's ring, so a small ring still has a thumb-sized target. */
private val ControlTouchPadding = 7.dp

/**
 * Lock screen — a 52px circle over `屏幕已锁定` at `gap:14px`, with the
 * `解锁` pill at `right:22px; bottom:40px`.
 */
@Composable
private fun LockedOverlay(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .glass(
                        shape = CircleShape,
                        fill = Color.White.copy(alpha = 0.09f),
                        border = Color.White.copy(alpha = 0.24f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text("屏幕已锁定", style = mr(12f, 500), color = Color.White.copy(alpha = 0.57f))
        }

        Text(
            "解锁",
            style = sc(12f, 600),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 40.dp)
                .glass(
                    shape = continuousRounded(20.dp),
                    fill = Color.White.copy(alpha = 0.10f),
                    border = Color.White.copy(alpha = 0.28f),
                )
                .noRippleClickable(onUnlock)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}


@Composable
internal fun DiagnosticRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = Color.White.copy(alpha = 0.06f),
                border = Color.White.copy(alpha = 0.10f),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = mr(11f, 500), color = Color.White.copy(alpha = 0.54f))
        Text(
            value,
            style = sc(11.5f, 600),
            color = Color.White.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

internal fun Long.asBitrate(): String {
    if (this <= 0L) return "等待数据"
    val tenths = this / 100_000L
    return "${tenths / 10}.${tenths % 10} Mbps"
}

internal fun Float.asFrameRate(): String {
    val tenths = (this * 10f).toInt()
    return "${tenths / 10}.${tenths % 10} fps"
}

/**
 * One row, several mutually exclusive answers — for a setting whose options are short
 * enough to sit side by side and are read as a spectrum rather than a list.
 *
 * A stack of [OptionRow]s says the same thing in three times the height and reads as three
 * independent switches until you notice only one is ticked.
 */
@Composable
internal fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val accent = Brand.PrimaryGradTop
            Text(
                label,
                style = sc(11.5f, if (active) 700 else 500),
                color = if (active) Color.White else Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .glass(
                        shape = GlassShapes.thumb,
                        fill = if (active) {
                            accent.copy(alpha = 0.20f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        },
                        border = if (active) {
                            accent.copy(alpha = 0.38f)
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                    )
                    .noRippleClickable { onSelect(index) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** `600 11px Manrope`, above each group in the 字幕·音轨 tab. */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        style = mr(11f, 600),
        color = Color.White.copy(alpha = 0.48f),
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

/**
 * `padding:9px 10px`, `radius:10px`. The panel reads on a dark glass plate over video,
 * so the selected row takes [Brand.PrimaryGradTop] — the accent's light end. The spec's
 * `#3D64C9` is a light-theme ink and goes muddy against this fill.
 */
@Composable
internal fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /**
     * Right-hand action, shown in place of the check mark. Null hides it, which is what
     * every row that is a plain choice passes — only 片头片尾 has something to undo.
     */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val accent = Brand.PrimaryGradTop
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = GlassShapes.thumb,
                fill = if (selected) {
                    accent.copy(alpha = 0.20f)
                } else {
                    Color.White.copy(alpha = 0.06f)
                },
                border = if (selected) {
                    accent.copy(alpha = 0.38f)
                } else {
                    Color.White.copy(alpha = 0.10f)
                },
            )
            .noRippleClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = sc(12.5f, if (selected) 700 else 500),
            color = if (selected) accent else Color.White.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        when {
            actionLabel != null -> Text(
                actionLabel,
                style = sc(11.5f, 600),
                color = Brand.Danger,
                maxLines = 1,
                modifier = Modifier
                    .noRippleClickable(onAction)
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
            )

            selected -> Icon(AppIcons.Check, null, tint = accent, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Taps on the overlay shouldn't flash a ripple over the picture.
 *
 * It suppressed the ripple and put nothing in its place, so thirty player controls
 * answered a press with nothing at all. Delegating to [pressable] keeps the name's promise
 * — still no ripple — and gives them the same dip every other control in the app has.
 */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    pressable(onClick = onClick)

/**
 * `alphatv · 1080P · EXO · HEVC · 18.1 Mbps · 60.0 fps` — the line under the title.
 *
 * Every part is dropped the moment it has nothing to say: one server means no server name,
 * a first frame that has not arrived means no resolution, an engine that has not measured a
 * bitrate yet means no bitrate. The line grows into itself over the first second or two
 * rather than printing 未知 six times.
 */
private fun PlaybackState.readoutLine(
    sourceLabel: String?,
    containerLabel: String?,
): String = listOfNotNull(
    sourceLabel?.takeIf { it.isNotBlank() },
    resolutionLabel(videoHeight),
    containerLabel?.takeIf { it.isNotBlank() },
    diagnostics.engine.takeIf { it.isNotBlank() }?.let(::engineShortLabel),
    diagnostics.videoCodec.takeIf { it.isNotBlank() && it != "未知" }?.uppercase(),
    diagnostics.bitrateBitsPerSecond.takeIf { it > 0L }?.asBitrate(),
    diagnostics.frameRate.takeIf { it > 0f }?.asFrameRate(),
).joinToString(" · ")

/**
 * `Media3 / ExoPlayer` reads as a sentence; this line has room for a word.
 *
 * Unmatched names pass through as their first word rather than being dropped — a future
 * engine should show up here without anyone remembering to add it to a list.
 */
private fun engineShortLabel(engine: String): String = when {
    engine.contains("exo", ignoreCase = true) -> "EXO"
    engine.contains("mpv", ignoreCase = true) -> "MPV"
    engine.contains("mdk", ignoreCase = true) -> "MDK"
    else -> engine.substringBefore(' ').uppercase()
}

private fun resolutionLabel(height: Int): String? = when {
    height <= 0 -> null
    height >= 2000 -> "4K"
    height >= 1400 -> "2K"
    height >= 1000 -> "1080P"
    height >= 700 -> "720P"
    else -> "${height}P"
}

internal fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

// ---------------------------------------------------------------- chrome transitions

/**
 * Which edge a piece of player chrome belongs to, and therefore where it comes from.
 *
 * Chrome that is anchored to an edge should arrive from that edge — it is the difference
 * between a panel that slid in from where it lives and one that materialised on top of the
 * film. [None] is for surfaces that own the whole screen and have no edge of their own.
 */
internal enum class ChromeEdge { Top, Bottom, End, None }

/**
 * Entrance and exit for one piece of player chrome.
 *
 * The travel is deliberately a fraction of the surface rather than a fixed distance: the top
 * bar, the transport row and the episode drawer are wildly different heights, and a shared
 * dp would read as a nudge on one and a lurch on another. A sixth of the surface's own size
 * looks like the same gesture on all of them.
 *
 * Under 减弱动态效果 the movement goes and the crossfade stays: chrome appearing instantly
 * over a moving picture is harder to follow than chrome that fades, and a fade is not the
 * kind of motion that setting is there to suppress.
 */
@Composable
internal fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    edge: ChromeEdge = ChromeEdge.None,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val fade = tween<Float>(CHROME_MS, easing = Motion.Curve)
    val slide = tween<IntOffset>(CHROME_MS, easing = Motion.Curve)
    val travel: (Int) -> Int = { full -> full / 6 }
    val moving = !reduceMotion

    val enter = when {
        !moving -> fadeIn(fade)
        edge == ChromeEdge.Top -> fadeIn(fade) + slideInVertically(slide) { -travel(it) }
        edge == ChromeEdge.Bottom -> fadeIn(fade) + slideInVertically(slide) { travel(it) }
        edge == ChromeEdge.End -> fadeIn(fade) + slideInHorizontally(slide) { travel(it) }
        // Anchored inside its own full-screen box, so it grows out of the corner it sits in
        // rather than sliding the invisible dismiss catcher around with it.
        else -> fadeIn(fade) + scaleIn(
            tween(CHROME_MS, easing = Motion.Curve),
            initialScale = 0.94f,
            transformOrigin = TransformOrigin(1f, 1f),
        )
    }
    val exit = when {
        !moving -> fadeOut(fade)
        edge == ChromeEdge.Top -> fadeOut(fade) + slideOutVertically(slide) { -travel(it) }
        edge == ChromeEdge.Bottom -> fadeOut(fade) + slideOutVertically(slide) { travel(it) }
        edge == ChromeEdge.End -> fadeOut(fade) + slideOutHorizontally(slide) { travel(it) }
        else -> fadeOut(fade) + scaleOut(
            tween(CHROME_MS, easing = Motion.Curve),
            targetScale = 0.94f,
            transformOrigin = TransformOrigin(1f, 1f),
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}
