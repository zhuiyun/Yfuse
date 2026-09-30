package com.yfuse.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.tv.player.TvPlayerChromeBridge
import com.yfuse.tv.player.TvPlayerChromeState

private const val MAX_ERROR_ALTERNATIVES = 3

/*
 * What PlayerControls shows over the picture besides its bars and panels. [controlState] is the
 * controls' snapshot of the playback, read through the same delegate and in the same places the
 * controls read it, so these recompose on what they did before and a tap still sees the moment it
 * lands in.
 */

/**
 * Standing, like the paused key: the picture is on another screen whether or not the
 * controls are up. It rides below the title bar while that is shown.
 */
@Composable
internal fun BoxScope.PlayerCastPill(
    chrome: PlayerChromeState,
    cast: PlayerCastState,
    castActions: PlayerCastActions,
    hintProgress: State<Float>,
) {
    val lastCastStatus = remember { arrayOf("") }
    cast.status?.let { lastCastStatus[0] = it }
    ChromeVisibility(
        visible = cast.active && cast.status != null,
        edge = ChromeEdge.Top,
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .playerHintOffset(hintProgress, 56.dp)
                .padding(start = 22.dp, top = 18.dp),
    ) {
        CastSessionPill(
            status = lastCastStatus[0],
            onOpen = { chrome.openSettingsPanel(SettingsPanelKind.Cast) },
            onDisconnect = {
                chrome.poke()
                castActions.onStop()
            },
            announce = cast.error != null,
        )
    }
}

/**
 * What stands on the picture itself: 暂停信息层, 继续播放 and the ending's keys, the gesture tips,
 * 长按中间's pill, the scrub preview, the gesture HUD, 双击's pulse and the volume slider.
 */
@Composable
internal fun BoxScope.PlayerPictureStatus(
    chrome: PlayerChromeState,
    gestureState: PlayerGestureState,
    playback: State<PlaybackState>,
    controlState: State<PlaybackState>,
    /** Where 双击's pulse count stood when this item started; see PlayerControls. */
    pulseItemStart: Int,
    pauseInfoShown: Boolean,
    watchLocked: Boolean,
    /** Neither a room nor a cast owns the rate, so 长按中间 may hold a speed. */
    speedBoostable: Boolean,
    remoteChrome: TvPlayerChromeBridge?,
    /** A remote's held fast-forward or rewind, whose frame the scrub preview shows. */
    remoteChromeState: TvPlayerChromeState?,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    /** The caller's seek as it is when 重播 is tapped. */
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    picture: PlayerPictureState,
    pictureActions: PlayerPictureActions,
    danmaku: PlayerDanmakuState,
) {
    val state by controlState
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
        chapters = transport.chapters,
        onDismiss = chrome::poke,
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
            transportActions.onPlayPause()
            chrome.poke()
        },
        onNext = {
            chrome.poke()
            transportActions.onNextItem()
        },
        onReplay = {
            if (playback.value.ended) {
                // Back to the first frame, and playing again: the engine reports
                // the ended item as paused, so the seek alone would leave it
                // standing on frame one.
                onSeek(0L)
                if (!playback.value.playing) transportActions.onPlayPause()
            } else {
                // Parked on the last frame instead of ended: resuming would run
                // into the next item before the seek landed, so the item is
                // started again from the top.
                transportActions.onSelectItem(state.currentIndex)
            }
            chrome.poke()
        },
        onBack = onBack,
        onHold = { endingFlowsBack = it },
    )

    // Taught once each, while their gesture is in reach; see [PlayerGestureTips].
    PlayerGestureTips(
        chromeUp = chrome.visible && remoteChrome == null && !chrome.locked,
        seekable = state.durationMs > 0L && !watchLocked,
        speedBoostable = speedBoostable,
        subtitlesAvailable = state.subtitleTracks.isNotEmpty(),
        danmakuShowing = danmaku.panel.enabled && danmaku.panel.count > 0,
        fineScrubArmed = gestureState.fineScrubArmed,
        gestures = picture.gestures,
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
        storyboard = transport.trickplay,
        positionMs = { remoteChromeState?.holdPreviewMs ?: gestureState.previewMs },
        chapters = transport.chapters,
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
        visible = chrome.volumeSliderVisible,
        edge = ChromeEdge.End,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp),
    ) {
        VolumeSlider(
            volume = picture.volume,
            onVolume = { target ->
                chrome.volumeSliderTouches++
                pictureActions.onVolume(target)
            },
            modifier = Modifier,
        )
    }
}

/**
 * The failure surface: the message, 重试 and the ways around it — another version, another engine,
 * an external player — and 查看原因与导出日志 behind it.
 */
@Composable
internal fun PlayerErrorLayer(
    errorMessage: String?,
    playback: State<PlaybackState>,
    source: PlayerSourceState,
    sourceActions: PlayerSourceActions,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var showProblem by remember { mutableStateOf(false) }
    if (showProblem) PlaybackProblemDialog(playback = playback, onDismiss = { showProblem = false })
    ChromeContent(errorMessage, modifier = Modifier.fillMaxSize(), coversScreen = true) { message ->
        val otherVersions =
            source.versions
                .filter { (id, _) -> id != source.selectedVersionId }
                .take(MAX_ERROR_ALTERNATIVES)
                .map { (id, label) -> "版本 · $label" to { sourceActions.onSelectVersion(id) } }
        // One strategy on offer (a native-only package) is no alternative to itself, whichever
        // row happens to be marked: reloading it replays the same path into the same failure.
        val otherEngines =
            source.engineOptions
                .takeIf { it.size > 1 }
                .orEmpty()
                .mapIndexedNotNull { index, (label, selected) ->
                    if (selected) null else label to { sourceActions.onSelectEngine(index) }
                }.take(MAX_ERROR_ALTERNATIVES)
        PlaybackErrorOverlay(
            message = message,
            onRetry = onRetry,
            onExternalPlayer = sourceActions.onExternalPlayer,
            onBack = onBack,
            alternatives = otherVersions + otherEngines,
            onExplain = { showProblem = true },
        )
    }
}
