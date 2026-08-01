package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.data.SkipMode
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.sync.WatchInvite
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 4_000L

/**
 * How long the volume slider stays up after the last press or drag.
 *
 * Shorter than [AUTO_HIDE_MS]: it covers part of the picture and answers a question that
 * has already been answered by the time the sound changes.
 */
private const val VOLUME_SLIDER_HIDE_MS = 1_600L

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
private enum class Tab(val label: String) {
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
fun PlayerControls(
    state: PlaybackState,
    titles: List<String>,
    filled: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectItem: (Int) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFill: () -> Unit,
    /** System volume, 0f..1f, and its setter — drives the bottom-left level chip. */
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
    skipSegmentLabel: String? = null,
    onSkipSegment: () -> Unit = {},
    /** Seconds left before an automatic skip fires, or null when none is armed. */
    skipCountdownSeconds: Int? = null,
    onCancelAutoSkip: () -> Unit = {},
    /** Files the server holds for this entry; a picker appears once there are two. */
    versions: List<Pair<String, String>> = emptyList(),
    selectedVersionId: String? = null,
    onSelectVersion: (String) -> Unit = {},
    /** Non-null when this entry belongs to a series, which is what skip times are kept per. */
    skipSeriesName: String? = null,
    skipIntroStartSeconds: Long = 0L,
    skipIntroEndSeconds: Long = 0L,
    /** 片尾 starts this many seconds before the end — see `SkipTimes.creditsLeadSeconds`. */
    skipCreditsLeadSeconds: Long = 0L,
    skipMode: SkipMode = SkipMode.Button,
    onSetSkipTimes: (Long, Long, Long) -> Unit = { _, _, _ -> },
    onSelectSkipMode: (SkipMode) -> Unit = {},
    watchEndpoint: String = "",
    watchConnecting: Boolean = false,
    watchConnected: Boolean = false,
    /** True while a previously-established room connection is retrying. Distinct from
     *  [watchConnected] — the room and its controls stay visible throughout, this only
     *  adds a small "重连中" indicator. */
    watchReconnecting: Boolean = false,
    watchRoomCode: String? = null,
    watchIsHost: Boolean = false,
    watchParticipantCount: Int = 0,
    watchError: String? = null,
    /** This device has asked the host for control and hasn't been answered yet. */
    watchControlRequested: Boolean = false,
    /** Host side: who is asking for control right now, by name. Null when nobody is. */
    watchControlRequesterName: String? = null,
    onCreateWatchRoom: (String) -> Unit = {},
    onJoinWatchRoom: (String, String) -> Unit = { _, _ -> },
    onLeaveWatchRoom: () -> Unit = {},
    onRequestControl: () -> Unit = {},
    onGrantControl: () -> Unit = {},
    onDenyControl: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableStateOf<Tab?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var watchDialogOpen by remember { mutableStateOf(false) }
    var danmakuSearchOpen by remember { mutableStateOf(false) }
    var danmakuSendOpen by remember { mutableStateOf(false) }
    var gestureHud by remember { mutableStateOf<String?>(null) }
    // -1 while a held press is rewinding, +1 while it is fast-forwarding, 0 when no press
    // is held. [holdSeekTarget] is where the timeline has run to, committed on release.
    var holdSeekDirection by remember { mutableIntStateOf(0) }
    var holdSeekTarget by remember { mutableLongStateOf(0L) }
    val haptics = LocalHapticFeedback.current
    // Bumped by every interaction so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }
    val latestPosition by rememberUpdatedState(state.positionMs)
    val latestDuration by rememberUpdatedState(state.durationMs)
    val latestVolume by rememberUpdatedState(volume)
    val latestBrightness by rememberUpdatedState(brightness)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnVolume by rememberUpdatedState(onVolume)
    val latestOnBrightness by rememberUpdatedState(onBrightness)
    // Timeline controls (play/pause, seek, episode, speed) are read-only for a connected
    // non-host: the room's host drives them, this device only follows. Volume, brightness,
    // subtitle/audio track, aspect ratio, cast and danmaku stay untouched by this — those
    // are per-viewer, not shared.
    val watchLocked = watchConnected && !watchIsHost
    val latestWatchLocked by rememberUpdatedState(watchLocked)

    fun poke() {
        interactions++
        visible = true
    }

    LaunchedEffect(
        visible,
        locked,
        settingsTab,
        drawerOpen,
        danmakuSearchOpen,
        danmakuSendOpen,
        state.playing,
        interactions,
    ) {
        val overlayOpen = settingsTab != null || drawerOpen || danmakuSearchOpen || danmakuSendOpen
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
                            } else {
                                val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                                latestOnSeek((latestPosition + delta).coerceIn(0L, latestDuration))
                                gestureHud = if (delta < 0) "快退 10 秒" else "快进 10 秒"
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            poke()
                        },
                        onLongPress = { offset ->
                            // Left half rewinds, right half fast-forwards — the same split
                            // the double tap already uses, so one gesture explains the other.
                            when {
                                latestWatchLocked -> gestureHud = "房主控制播放"
                                latestDuration <= 0L -> Unit
                                else -> {
                                    holdSeekTarget = latestPosition
                                    holdSeekDirection = if (offset.x < size.width / 2f) -1 else 1
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

        if (visible) {
            TopBar(
                title = titles.getOrNull(state.currentIndex).orEmpty(),
                subtitle = state.readoutLine(sourceLabel, containerLabel),
                filled = filled,
                hasEpisodes = state.itemCount > 1,
                dolbyVision = dolbyVision,
                dolbyAtmos = dolbyAtmos,
                onBack = onBack,
                onOpenDrawer = { poke(); drawerOpen = true },
                onToggleFill = { poke(); onToggleFill() },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            BottomBar(
                state = state,
                volume = volume,
                seekLocked = watchLocked,
                onPlayPause = { poke(); onPlayPause() },
                onRewind = { poke(); onSeek((state.positionMs - 10_000L).coerceAtLeast(0L)) },
                onForward = { poke(); onSeek(state.positionMs + 10_000L) },
                skipCountdownLabel = skipCountdownSeconds?.let {
                    skipCountdownLabel(skipSegmentLabel, it)
                },
                onCancelAutoSkip = { poke(); onCancelAutoSkip() },
                onVolume = { poke(); onVolume(it) },
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // An armed countdown replaces the manual pill rather than sitting beside it — both
        // would be about the same segment, and the countdown already skips on its own.
        // While the controls are up it is drawn under the progress bar by [BottomBar]; this
        // is the same pill for when they aren't.
        when {
            skipCountdownSeconds != null && !visible -> SkipPill(
                label = skipCountdownLabel(skipSegmentLabel, skipCountdownSeconds),
                onClick = { poke(); onCancelAutoSkip() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 24.dp),
            )

            skipCountdownSeconds == null && skipSegmentLabel != null -> SkipPill(
                label = skipSegmentLabel,
                onClick = { poke(); onSkipSegment() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = if (visible) 92.dp else 24.dp),
            )
        }

        settingsTab?.let { tab ->
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
                watchConnected = watchConnected,
                watchReconnecting = watchReconnecting,
                watchIsHost = watchIsHost,
                watchParticipantCount = watchParticipantCount,
                watchRoomCode = watchRoomCode,
                onOpenWatchTogether = {
                    settingsTab = null
                    watchDialogOpen = true
                },
                versions = versions,
                selectedVersionId = selectedVersionId,
                onSelectVersion = { onSelectVersion(it); settingsTab = null },
                skipSeriesName = skipSeriesName,
                skipIntroStartSeconds = skipIntroStartSeconds,
                skipIntroEndSeconds = skipIntroEndSeconds,
                skipCreditsLeadSeconds = skipCreditsLeadSeconds,
                skipMode = skipMode,
                // The panel stays open: setting a boundary is something you check against
                // the picture behind it, and often two of the three in one visit.
                onSetSkipTimes = onSetSkipTimes,
                onSelectSkipMode = onSelectSkipMode,
                onDismiss = { settingsTab = null },
            )
        }

        if (watchDialogOpen) {
            WatchTogetherDialog(
                endpoint = watchEndpoint,
                connecting = watchConnecting,
                connected = watchConnected,
                roomCode = watchRoomCode,
                isHost = watchIsHost,
                participantCount = watchParticipantCount,
                error = watchError,
                controlRequested = watchControlRequested,
                onCreate = onCreateWatchRoom,
                onJoin = onJoinWatchRoom,
                onLeave = {
                    onLeaveWatchRoom()
                    watchDialogOpen = false
                },
                onRequestControl = onRequestControl,
                onDismiss = { watchDialogOpen = false },
            )
        }

        // Outside `watchDialogOpen` on purpose: a request arrives when the asker taps, not
        // when the host happens to have the room dialog open, and an unanswered one leaves
        // that person waiting on a prompt nobody ever sees.
        watchControlRequesterName?.let { requester ->
            ControlRequestDialog(
                requesterName = requester,
                onGrant = onGrantControl,
                // Dismissing is an answer too. Closing without one would leave the asker
                // waiting indefinitely, which is what `denyControl` exists to avoid.
                onDeny = onDenyControl,
            )
        }

        if (drawerOpen) {
            EpisodeDrawer(
                titles = titles,
                currentIndex = state.currentIndex,
                onSelect = if (watchLocked) {
                    // Guests can still browse what's in the room's queue; picking is the
                    // host's move, so tapping explains itself instead of doing nothing.
                    { gestureHud = "房主控制播放" }
                } else {
                    { onSelectItem(it); drawerOpen = false }
                },
                onDismiss = { drawerOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (danmakuSearchOpen) {
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
                modifier = Modifier.align(Alignment.CenterEnd),
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
        if (watchConnected && visible) {
            val roomNote = when {
                watchReconnecting -> "一起看 · 重连中…"
                !watchIsHost -> "一起看 · 房主控制播放"
                else -> "一起看 · 你是房主 · $watchParticipantCount 人"
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
                        fill = if (watchReconnecting) {
                            Brand.Danger.copy(alpha = 0.42f)
                        } else {
                            Color.Black.copy(alpha = 0.52f)
                        },
                        border = Color.White.copy(alpha = 0.24f),
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        gestureHud?.let { value ->
            Text(
                value,
                style = sc(15f, 700),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .glass(
                        shape = RoundedCornerShape(22.dp),
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

        if (state.hasNext && state.durationMs > 0L) {
            val remainingSeconds = ((state.durationMs - state.positionMs) / 1_000L)
            if (remainingSeconds in 1L..10L) {
                Text(
                    "下一集将在 ${remainingSeconds} 秒后播放",
                    style = sc(12f, 700),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 96.dp)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = Color.Black.copy(alpha = 0.5f),
                            border = Color.White.copy(alpha = 0.22f),
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
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
                            shape = RoundedCornerShape(18.dp),
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
                            shape = RoundedCornerShape(18.dp),
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
    onOpenDrawer: () -> Unit,
    onToggleFill: () -> Unit,
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
            CircleControl(AppIcons.ChevronLeft, "返回", 30.dp, 13.dp, onClick = onBack)
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
            if (hasEpisodes) {
                CircleControl(AppIcons.Menu, "剧集列表", 30.dp, 13.dp, onClick = onOpenDrawer)
            }
            CircleControl(
                icon = if (filled) AppIcons.Collapse else AppIcons.Expand,
                description = "切换画面比例",
                size = 30.dp,
                iconSize = 13.dp,
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
                shape = RoundedCornerShape(22.dp),
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
                .clip(RoundedCornerShape(3.dp))
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
                        .clip(RoundedCornerShape(3.dp))
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
                shape = RoundedCornerShape(18.dp),
                fill = Color.Black.copy(alpha = 0.64f),
                border = Color.White.copy(alpha = 0.28f),
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** "片头结束 · 90 秒" / "片头结束 · 未设置". */
private fun skipBoundaryLabel(name: String, seconds: Long): String =
    if (seconds > 0L) "$name · $seconds 秒" else "$name · 未设置"

/**
 * "片尾开始 · 距结束 120 秒" / "片尾开始 · 未设置".
 *
 * Spelled out rather than reusing [skipBoundaryLabel] because the number means the
 * opposite thing: 120 here is near the end of the episode, not two minutes into it.
 */
private fun skipCreditsLabel(seconds: Long): String =
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
    volume: Float,
    /** Guest in a room: the scrubber becomes a read-only progress indicator. */
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    /** Non-null while an automatic skip is counting down; shown under the progress row. */
    skipCountdownLabel: String?,
    onCancelAutoSkip: () -> Unit,
    onVolume: (Float) -> Unit,
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
    val shownPosition = scrubbed?.let { (it * duration).toLong() } ?: state.positionMs

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
                enabled = !seekLocked,
                onScrubTo = { scrubbed = it; onScrub() },
                onCommit = {
                    onSeek((it * duration).toLong())
                    scrubbed = null
                },
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
                    onRewind = onRewind,
                    onForward = onForward,
                )
                VolumeChip(volume, onVolume)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("弹幕", active = danmakuEnabled, onClick = onOpenDanmaku)
                // 字幕与音轨在同一个面板 tab 里（[Tab.Subtitle] 两组都列），所以这里
                // 只出一个 chip；先前的两个 chip 打开的是同一块面板，纯粹重复。
                val hasSubtitles = state.subtitleTracks.isNotEmpty()
                val hasAudioChoice = state.audioTracks.size > 1
                if (hasSubtitles || hasAudioChoice) {
                    val label = when {
                        hasSubtitles && hasAudioChoice -> "字幕 / 音轨"
                        hasSubtitles -> "字幕"
                        else -> "音轨"
                    }
                    Chip(label) { onOpenTab(Tab.Subtitle) }
                }
                IconChip(
                    icon = AppIcons.Cast,
                    description = if (casting) "停止投送" else "投屏",
                    active = casting,
                    onClick = onToggleCast,
                )
                IconChip(
                    icon = AppIcons.More,
                    description = "更多",
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
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var width by remember { mutableStateOf(1f) }
    var dragFraction by remember { mutableStateOf(0f) }

    Box(
        modifier
            // A 4px bar is unhittable; pad the touch target without moving the bar.
            .padding(vertical = 10.dp)
            .height(4.dp)
            .let { base ->
                if (!enabled) return@let base
                base
                    .pointerInput(Unit) {
                        width = size.width.toFloat().coerceAtLeast(1f)
                        detectTapGestures { offset -> onCommit((offset.x / width).coerceIn(0f, 1f)) }
                    }
                    .pointerInput(Unit) {
                        width = size.width.toFloat().coerceAtLeast(1f)
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragFraction = (offset.x / width).coerceIn(0f, 1f)
                                onScrubTo(dragFraction)
                            },
                            onDragEnd = { onCommit(dragFraction) },
                            onDragCancel = { onCommit(dragFraction) },
                        ) { change, dragAmount ->
                            change.consume()
                            dragFraction = (dragFraction + dragAmount / width).coerceIn(0f, 1f)
                            onScrubTo(dragFraction)
                        }
                    }
            }
            .clip(RoundedCornerShape(2.dp))
            .background(PlayerTokens.trackFillLandscape),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(PlayerTokens.progress),
        )
    }
}

/**
 * `gap:10px`, `rgba(255,255,255,.14)` over `rgba(255,255,255,.22)`, `radius:16px`,
 * `padding:6px 12px`, with a 70×3 level track. Shares [ChipHeight] with the chip row
 * opposite it, so the whole bottom row sits on one baseline.
 */
@Composable
private fun VolumeChip(volume: Float, onVolume: (Float) -> Unit) {
    Row(
        Modifier
            .height(ChipHeight)
            .glass(
                shape = ChipShape,
                fill = PlayerTokens.chipFill,
                border = Color.White.copy(alpha = 0.24f),
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Box(
            Modifier
                .width(70.dp)
                .height(ChipHeight)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onVolume((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onVolume((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            onVolume(
                                (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.28f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(volume.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
            }
        }
    }
}

/**
 * 字幕 / 音轨 / 投屏 / 更多 are one control family, so they share one height, one radius
 * and one material. Only the width flexes — a label needs more room than a glyph, and a
 * text chip that shrank to fit its text used to sit two thirds the height of the icon
 * ones next to it.
 */
private val ChipHeight = 40.dp
private val ChipMinWidth = 46.dp
private val ChipShape = RoundedCornerShape(14.dp)

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
    /** Filled rather than outlined. The one control that earns it is 播放/暂停. */
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    // The ring is what you see; the touch target is bigger than the ring. Sizing them
    // together is what made these controls big enough to cover a face — a 48dp disc over
    // the middle of the picture is 48dp of picture you cannot see.
    Box(
        Modifier
            .size(size + ControlTouchPadding * 2)
            .let { if (enabled) it.noRippleClickable(onClick) else it },
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
                    shape = RoundedCornerShape(20.dp),
                    fill = Color.White.copy(alpha = 0.10f),
                    border = Color.White.copy(alpha = 0.28f),
                )
                .noRippleClickable(onUnlock)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/**
 * Settings panel — `right:120px; bottom:70px; width:230px`, `rgba(255,255,255,.92)`,
 * `radius:18px`, `padding:6px 0 12px`, `0 20px 50px -12px rgba(30,40,70,.3)`.
 */
@Composable
private fun SettingsPanel(
    tab: Tab,
    state: PlaybackState,
    speeds: List<Float>,
    engineOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    castDevices: List<Pair<String, String>>,
    castingDeviceId: String?,
    castDiscovering: Boolean,
    castError: String?,
    danmaku: DanmakuPanelState,
    danmakuActions: DanmakuPanelActions,
    onOpenDanmakuSearch: () -> Unit,
    onOpenDanmakuSend: () -> Unit,
    onTab: (Tab) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onSelectEngine: (Int) -> Unit,
    onTranscode: () -> Unit,
    onDiscoverCast: () -> Unit,
    onCastTo: (String) -> Unit,
    onStopCast: () -> Unit,
    onLock: () -> Unit,
    watchConnected: Boolean,
    watchReconnecting: Boolean,
    watchIsHost: Boolean,
    watchParticipantCount: Int,
    watchRoomCode: String?,
    onOpenWatchTogether: () -> Unit,
    versions: List<Pair<String, String>>,
    selectedVersionId: String?,
    onSelectVersion: (String) -> Unit,
    skipSeriesName: String?,
    skipIntroStartSeconds: Long,
    skipIntroEndSeconds: Long,
    skipCreditsLeadSeconds: Long,
    skipMode: SkipMode,
    onSetSkipTimes: (Long, Long, Long) -> Unit,
    onSelectSkipMode: (SkipMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = buildList {
        add(Tab.Danmaku)
        // A lone audio track is not a choice — matching the chip's condition keeps the
        // tab from appearing with nothing switchable in it.
        if (state.subtitleTracks.isNotEmpty() || state.audioTracks.size > 1) add(Tab.Subtitle)
        add(Tab.Cast)
        add(Tab.Diagnostics)
        add(Tab.More)
    }
    val shape = RoundedCornerShape(20.dp)

    // Dismiss catcher only — the old full-screen `rgba(0,0,0,.35)` scrim dimmed the film
    // itself every time a track list opened. The panel earns its separation from its own
    // material and shadow instead, so the picture behind it stays untouched.
    Box(Modifier.fillMaxSize().noRippleClickable(onDismiss))
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                // Sits directly above the chip row that opens it, on the same right edge.
                .padding(end = 22.dp, bottom = 84.dp)
                .width(248.dp)
                .shadow(Shadows.playerSheet, shape)
                .glass(
                    shape = shape,
                    fill = PlayerTokens.drawerFillLandscape,
                    border = Color.White.copy(alpha = 0.20f),
                )
                .noRippleClickable { }
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            // Segmented tab row. The pill alone carries the active state; the 2px rule
            // underneath it was a second signal saying the same thing.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEach { entry ->
                    val active = entry == tab
                    Text(
                        entry.label,
                        style = sc(12.5f, if (active) 700 else 500),
                        color = if (active) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.58f)
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .glass(
                                shape = GlassShapes.thumb,
                                fill = if (active) {
                                    Color.White.copy(alpha = 0.18f)
                                } else {
                                    Color.Transparent
                                },
                                border = if (active) {
                                    Color.White.copy(alpha = 0.26f)
                                } else {
                                    null
                                },
                            )
                            .noRippleClickable { onTab(entry) }
                            .padding(vertical = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            )

            // `padding:10px 14px 2px; max-height:150px`.
            Column(
                Modifier
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (tab) {
                    Tab.Danmaku -> DanmakuTab(
                        state = danmaku,
                        actions = danmakuActions,
                        onOpenSearch = onOpenDanmakuSearch,
                        onOpenSend = onOpenDanmakuSend,
                    )

                    Tab.Subtitle -> {
                        if (state.subtitleTracks.isNotEmpty()) {
                            GroupLabel("字幕")
                            OptionRow("关闭", state.subtitleTracks.none { it.selected }) {
                                onSelectSubtitle(EngineTrack.OFF)
                            }
                            state.subtitleTracks.forEach { track ->
                                OptionRow(track.label, track.selected) { onSelectSubtitle(track.id) }
                            }
                        }
                        if (state.audioTracks.isNotEmpty()) {
                            GroupLabel("音轨")
                            state.audioTracks.forEach { track ->
                                OptionRow(track.label, track.selected) { onSelectAudio(track.id) }
                            }
                        }
                    }

                    Tab.More -> {
                        // 剧集列表与画面比例都在顶栏有常驻圆钮，这里不再重复列一遍；
                        // 只留顶栏没有的入口。
                        GroupLabel("播放")
                        OptionRow("锁定控制", false, onClick = onLock)
                        OptionRow(
                            if (watchConnected) {
                                "一起看 · ${watchRoomCode.orEmpty()}"
                            } else {
                                "一起看"
                            },
                            watchConnected,
                            onClick = onOpenWatchTogether,
                        )

                        // A single file is not a choice, so the group only appears once
                        // the library actually holds more than one copy of this title.
                        if (versions.size > 1) {
                            GroupLabel("版本")
                            versions.forEach { (id, label) ->
                                OptionRow(label, id == selectedVersionId) { onSelectVersion(id) }
                            }
                        }

                        // Only for a series: an opening belongs to the show, and there is
                        // nothing sensible to hang a film's times off. Setting a boundary
                        // from where playback already is beats typing seconds at a
                        // fullscreen landscape keyboard — 我的 has the numeric editor for
                        // when a value needs nudging afterwards.
                        if (skipSeriesName != null) {
                            GroupLabel("片头片尾 · 点按设为当前进度")
                            val here = (state.positionMs / 1000).coerceAtLeast(0L)
                            // 片尾 is kept as a distance from the end, so what gets stored
                            // is how much of the episode is left — the same tap, counted
                            // from the other side. Zero when the duration is not known yet,
                            // which reads as "unset" rather than "starts at the very end".
                            val leftFromHere = ((state.durationMs - state.positionMs) / 1000)
                                .coerceAtLeast(0L)
                            OptionRow(
                                label = skipBoundaryLabel("片头开始", skipIntroStartSeconds),
                                selected = skipIntroStartSeconds > 0L,
                                onClick = {
                                    onSetSkipTimes(
                                        here,
                                        skipIntroEndSeconds,
                                        skipCreditsLeadSeconds,
                                    )
                                },
                                // Setting a boundary is one tap, so clearing one has to be
                                // too: without this the only way back from a mistimed tap
                                // was to wipe all three and re-enter the others.
                                actionLabel = "取消".takeIf { skipIntroStartSeconds > 0L },
                                onAction = {
                                    onSetSkipTimes(0L, skipIntroEndSeconds, skipCreditsLeadSeconds)
                                },
                            )
                            OptionRow(
                                label = skipBoundaryLabel("片头结束", skipIntroEndSeconds),
                                selected = skipIntroEndSeconds > 0L,
                                onClick = {
                                    onSetSkipTimes(
                                        skipIntroStartSeconds,
                                        here,
                                        skipCreditsLeadSeconds,
                                    )
                                },
                                actionLabel = "取消".takeIf { skipIntroEndSeconds > 0L },
                                onAction = {
                                    onSetSkipTimes(
                                        skipIntroStartSeconds,
                                        0L,
                                        skipCreditsLeadSeconds,
                                    )
                                },
                            )
                            OptionRow(
                                label = skipCreditsLabel(skipCreditsLeadSeconds),
                                selected = skipCreditsLeadSeconds > 0L,
                                onClick = {
                                    onSetSkipTimes(
                                        skipIntroStartSeconds,
                                        skipIntroEndSeconds,
                                        leftFromHere,
                                    )
                                },
                                actionLabel = "取消".takeIf { skipCreditsLeadSeconds > 0L },
                                onAction = {
                                    onSetSkipTimes(skipIntroStartSeconds, skipIntroEndSeconds, 0L)
                                },
                            )
                            // Three answers, not a switch: "don't skip automatically" and
                            // "don't offer it at all" are different requests, and the second
                            // used to be reachable only by deleting the times.
                            GroupLabel("到达片头片尾时")
                            SegmentedRow(
                                options = SkipMode.entries.map { it.label },
                                selectedIndex = SkipMode.entries.indexOf(skipMode),
                                onSelect = { onSelectSkipMode(SkipMode.entries[it]) },
                            )
                            // Also offered for a half-entered intro, which is exactly when
                            // starting over is most likely to be what's wanted.
                            val anySkipTimeSet = skipIntroStartSeconds > 0L ||
                                skipIntroEndSeconds > 0L ||
                                skipCreditsLeadSeconds > 0L
                            if (anySkipTimeSet) {
                                OptionRow("清除《$skipSeriesName》的设置", false) {
                                    onSetSkipTimes(0L, 0L, 0L)
                                }
                            }
                        }

                        GroupLabel("播放速度")
                        speeds.forEach { speed ->
                            OptionRow(speedLabel(speed), speed == state.speed) { onSpeed(speed) }
                        }

                        if (engineOptions.isNotEmpty() || transcodeLabel != null) {
                            GroupLabel("播放器内核")
                        }
                        engineOptions.forEachIndexed { index, (label, selected) ->
                            OptionRow(label, selected) { onSelectEngine(index) }
                        }
                        if (transcodeLabel != null) {
                            OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                        }
                    }

                    Tab.Diagnostics -> {
                        val diagnostics = state.diagnostics
                        GroupLabel("实时播放信息")
                        DiagnosticRow("内核", diagnostics.engine.ifBlank { "未知" })
                        DiagnosticRow("解码器", diagnostics.decoder)
                        DiagnosticRow("播放方式", diagnostics.playMethod)
                        DiagnosticRow(
                            "画面",
                            buildString {
                                append(if (state.videoHeight > 0) "${state.videoHeight}P" else "未知分辨率")
                                if (diagnostics.frameRate > 0f) {
                                    append(" · ")
                                    append(diagnostics.frameRate.asFrameRate())
                                }
                            },
                        )
                        DiagnosticRow("视频编码", diagnostics.videoCodec)
                        DiagnosticRow("当前码率", diagnostics.bitrateBitsPerSecond.asBitrate())
                        DiagnosticRow("网络速度", diagnostics.networkBitsPerSecond.asBitrate())
                        DiagnosticRow(
                            "缓冲",
                            "${diagnostics.bufferedDurationMs / 1000.0f}s · ${diagnostics.bufferEvents} 次",
                        )
                        DiagnosticRow("丢帧", "${diagnostics.droppedFrames} 帧")

                        // The room's own state, next to the playback state it is driving.
                        // When 一起看 misbehaves the question is always the same — am I
                        // still connected, who is in charge, how many of us are there —
                        // and until now the only answer was the one-line banner.
                        if (watchConnected || watchRoomCode != null) {
                            GroupLabel("一起看")
                            DiagnosticRow(
                                "状态",
                                when {
                                    watchReconnecting -> "重连中"
                                    watchConnected -> "已连接"
                                    else -> "未连接"
                                },
                            )
                            DiagnosticRow("房间", watchRoomCode ?: "—")
                            DiagnosticRow("身份", if (watchIsHost) "房主" else "参与者")
                            DiagnosticRow("在线", "$watchParticipantCount 人")
                        }
                    }

                    Tab.Cast -> {
                        GroupLabel("局域网投屏设备")
                        if (castDiscovering) {
                            Text(
                                "正在发现 DLNA 设备…",
                                style = mr(11.5f, 500),
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                        castDevices.forEach { (id, name) ->
                            OptionRow(name, id == castingDeviceId) { onCastTo(id) }
                        }
                        if (castingDeviceId != null) {
                            OptionRow("停止投屏", false, onClick = onStopCast)
                        }
                        if (castError != null) {
                            Text(
                                castError,
                                style = mr(10.5f, 500),
                                color = Brand.Danger,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        OptionRow("重新扫描", false, onClick = onDiscoverCast)
                    }
                }
            }
        }
    }
}

/**
 * In-player watch-together control. Since the entry points moved to where people actually
 * decide what to watch — 详情页 for hosting, an invite link for joining — this is now the
 * recovery path: "we're already watching, pull someone in."
 *
 * The relay address is deliberately not asked for here. It's infrastructure with a working
 * default, it belongs in 「我的」's settings, and putting it in front of someone mid-film (as
 * a required field, with both buttons disabled until it validated) was the single biggest
 * obstacle in the old flow.
 */
@Composable
private fun WatchTogetherDialog(
    endpoint: String,
    connecting: Boolean,
    connected: Boolean,
    roomCode: String?,
    isHost: Boolean,
    participantCount: Int,
    error: String?,
    controlRequested: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (String, String) -> Unit,
    onLeave: () -> Unit,
    onRequestControl: () -> Unit,
    onDismiss: () -> Unit,
) {
    var roomDraft by remember { mutableStateOf("") }
    val normalizedRoom = WatchInvite.normalizeCode(roomDraft)
    val palette = LocalPalette.current

    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "一起看",
            subtitle = if (connected) {
                "房主控制播放、暂停与进度，其他成员自动跟随。"
            } else {
                "视频仍由每个人自己的媒体服务器播放，房间服务只同步状态。"
            },
            onClose = onDismiss,
        )
        if (connected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(14.dp), palette.card2, palette.border)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    roomCode.orEmpty(),
                    style = sc(24f, 800),
                    color = Brand.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${if (isHost) "房主" else "成员"} · $participantCount 人在线",
                    style = mr(11f, 500),
                    color = palette.sub2,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
            }
            if (!isHost) {
                // Deliberately still enabled once asked: a host who never answers would
                // otherwise leave this pinned on "waiting" with no way to ask again.
                OverlayButton(
                    label = if (controlRequested) "再次请求控制权" else "请求控制权",
                    onClick = onRequestControl,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tone = OverlayButtonTone.Primary,
                )
                if (controlRequested) {
                    Text(
                        "已发送请求，等待房主响应。",
                        style = sc(10.5f, 500),
                        color = palette.sub2,
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            OverlayButton(
                label = "退出房间",
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                tone = OverlayButtonTone.Destructive,
            )
        } else {
            WatchInput(
                value = normalizedRoom,
                placeholder = "输入 6 位房间码",
                onValueChange = { roomDraft = it },
            )
            error?.let {
                Spacer(Modifier.height(7.dp))
                Text(it, style = sc(10.5f, 500), color = Brand.Danger)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayButton(
                    label = if (connecting) "连接中…" else "创建房间",
                    onClick = { onCreate(endpoint) },
                    modifier = Modifier.weight(1f),
                    tone = OverlayButtonTone.Primary,
                    enabled = !connecting,
                )
                OverlayButton(
                    label = "加入房间",
                    onClick = { onJoin(endpoint, normalizedRoom) },
                    modifier = Modifier.weight(1f),
                    enabled = WatchInvite.isCompleteCode(normalizedRoom) && !connecting,
                )
            }
        }
    }
}

/**
 * Host side of the control handoff: a member has asked to drive the room.
 *
 * Both answers are explicit. Granting moves the timeline to them — this device becomes a
 * follower and its own controls lock — and denying tells the asker so, rather than letting
 * the request expire into silence.
 */
@Composable
private fun ControlRequestDialog(
    requesterName: String,
    onGrant: () -> Unit,
    onDeny: () -> Unit,
) {
    GlassDialog(onDismiss = onDeny) {
        OverlayHeader(
            title = "控制权请求",
            subtitle = "$requesterName 想要控制这个房间。交出后由对方掌握播放、暂停、进度和集数，你会跟随他们。",
            onClose = onDeny,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayButton(
                label = "拒绝",
                onClick = onDeny,
                modifier = Modifier.weight(1f),
            )
            OverlayButton(
                label = "交给对方",
                onClick = onGrant,
                modifier = Modifier.weight(1f),
                tone = OverlayButtonTone.Primary,
            )
        }
    }
}

@Composable
private fun WatchInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(13.dp), palette.card2, palette.border)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isBlank()) {
            Text(
                placeholder,
                style = mr(12f, 500),
                color = palette.hint,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = mr(12f, 500).copy(color = palette.text),
            cursorBrush = SolidColor(Brand.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
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

private fun Long.asBitrate(): String {
    if (this <= 0L) return "等待数据"
    val tenths = this / 100_000L
    return "${tenths / 10}.${tenths % 10} Mbps"
}

private fun Float.asFrameRate(): String {
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
 * Episode drawer — `width:190px`, `rgba(18,22,34,.7)` behind a
 * `rgba(255,255,255,.14)` left hairline, `padding:16px 12px`, `gap:8px`.
 */
@Composable
private fun EpisodeDrawer(
    titles: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).noRippleClickable(onDismiss))
    Column(
        modifier
            .fillMaxHeight()
            .width(190.dp)
            .glass(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                fill = PlayerTokens.drawerFillLandscape,
                border = Color.White.copy(alpha = 0.24f),
            )
            .noRippleClickable { }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("剧集列表", style = sc(12f, 700), color = Color.White)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(11.dp).noRippleClickable(onDismiss),
            )
        }

        titles.forEachIndexed { index, title ->
            val current = index == currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(
                        shape = GlassShapes.thumb,
                        fill = if (current) {
                            PlayerTokens.episodeActiveFill
                        } else {
                            PlayerTokens.episodeIdleFill
                        },
                        border = if (current) {
                            Color.White.copy(alpha = 0.28f)
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        },
                    )
                    .noRippleClickable { onSelect(index) }
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 48×30 thumbnail slot; the queue carries no stills, so it stays a tile.
                Box(
                    Modifier
                        .width(48.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PlayerTokens.drawerFill),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifEmpty { "第 ${index + 1} 集" },
                        style = sc(10.5f, 600),
                        color = if (current) Color.White else Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (current) "正在播放" else "第 ${index + 1} 集",
                        style = mr(9f, 400),
                        color = if (current) {
                            PlayerTokens.episodeActiveSub
                        } else {
                            Color.White.copy(alpha = 0.4f)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Taps on the overlay shouldn't flash a ripple over the picture. */
@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

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

private fun speedLabel(speed: Float): String =
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
