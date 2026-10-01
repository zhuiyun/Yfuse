package com.yfuse.feature.player

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformPredictiveBackHandler
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlin.math.roundToInt

/**
 * The picture and everything drawn over it: the engine's surface in its 氛围光, the continuity
 * artwork, the status chip and 弹幕 over the timeline, the controls, the layers over the chrome and
 * both halves of the transition. [controls] is handed the 氛围光 binding this surface owns, which
 * the controls switch and report their visibility to.
 *
 * A composable of its own, in a file of its own, so it compiles to methods of its own. As the inline
 * Box of PlayerRoot's runtime content it was part of a ~3,000-line method that R8 9.1 once
 * mis-optimised into an Android 17 VerifyError before the app could start (see proguard-rules.pro).
 * [creditsTakeover] and [oledPauseProtectionActive] are read here, as they were by that Box, so
 * neither recomposes the runtime content when it changes.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerRootSurface(
    engine: VideoEngine,
    state: PlaybackState,
    livePlayback: State<PlaybackState>,
    currentItem: PlayerMediaItem?,
    startIndex: Int,
    choices: PlayerViewerChoices,
    presentationSubtitleControls: SubtitleControlState,
    playbackPreferences: PlaybackPreferences,
    ambientPowerLimited: Boolean,
    inPictureInPicture: Boolean,
    /** 锁定方向 is on: a tabletop posture then leaves the layout alone. */
    rotationLocked: Boolean,
    transition: PlayerTransitionState?,
    creditsTakeover: State<Boolean>,
    networkRecovery: PlaybackNetworkRecoveryState,
    danmaku: PlayerDanmakuController,
    danmakuPicker: DanmakuPicker,
    chromeExtras: PlayerChromeExtras,
    oledPauseProtectionActive: State<Boolean>,
    onDismissOledPauseProtection: () -> Unit,
    onVideoBounds: (Rect) -> Unit,
    onBack: () -> Unit,
    controls: @Composable (PlayerAmbientBinding) -> Unit,
) {
    val ambient =
        rememberPlayerAmbient(
            playbackPreferences,
            engine,
            currentItem,
            state,
            livePlayback,
            choices.scaleMode,
            inPictureInPicture,
            ambientPowerLimited = ambientPowerLimited,
        )
    // The way out carries the paused frame, and only this composition can read the surface.
    DisposableEffect(transition, ambient.sampler) {
        transition?.snapshotSource = { ambient.sampler.snapshot() }
        onDispose { transition?.snapshotSource = null }
    }
    // Each host places this above its surface and below its subtitle overlays: bars an engine
    // paints inside its own surface are lit, and captions placed in the letterbox stay legible.
    // The picture rectangle is clipped out, so it never draws over the frame.
    val ambientLayer: @Composable () -> Unit = {
        AmbientLightLayer(
            light = ambient.light,
            sampler = ambient.sampler,
            videoSize = ambient.videoSize,
            scaleMode = choices.scaleMode,
            modifier = Modifier.fillMaxSize(),
        )
    }
    // The continuity artwork and the two status strings are built here, outside the timeline
    // scope below, and handed down as values that do not change per tick: the list and the
    // reader lambdas kept being reallocated twice a second, and the strings — 「已缓冲 N 秒」
    // among them — were formatted on every one of those ticks whether or not anything was
    // on screen to read them. The readers take their snapshots where they are drawn instead.
    val continuityArtwork =
        remember(currentItem?.stillUrl, currentItem?.posterUrl) {
            listOf(currentItem?.stillUrl, currentItem?.posterUrl)
        }
    val continuityMessage =
        remember(livePlayback, networkRecovery, startIndex) {
            { playbackContinuityLine(livePlayback.value, networkRecovery.pending, startIndex) }
        }
    val statusChipMessage =
        remember(livePlayback, networkRecovery) {
            { playbackStatusChipLine(livePlayback.value.diagnostics, networkRecovery.pending) }
        }
    // Every layer that only belongs to the full-size window crosses the 画中画 boundary on the
    // same short fade, so the overlays leave together instead of blinking out one by one.
    val pictureInPictureFadeMs = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.QUICK
    // 折叠屏桌面模式: standing half-open, the picture keeps above the hinge and the controls below.
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val tabletopHinge =
        rememberTabletopHinge(
            rotationLocked = rotationLocked,
            inPictureInPicture = inPictureInPicture,
        )
    val tabletop =
        tabletopHinge
            ?.takeUnless { inPictureInPicture }
            ?.let { tabletopSplit(it.first, it.last, containerHeightPx) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                containerHeightPx = coordinates.size.height
                val bounds = coordinates.boundsInWindow()
                onVideoBounds(
                    Rect(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    ),
                )
                ambient.onContainerSize(coordinates.size)
            },
    ) {
        // 片尾接管: whichever engine draws, its surface moves the same way; the controls decide when.
        val pictureModifier =
            Modifier
                .then(
                    if (tabletop == null) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth().height(with(density) { tabletop.pictureBottomPx.toDp() })
                    },
                ).creditsTakeoverPicture(
                    active = creditsTakeover.value && !inPictureInPicture,
                    immediate = inPictureInPicture,
                )
        when (engine) {
            is YPlayerVideoEngineAdapter ->
                Core2Surface(
                    engine = engine,
                    protectedContent =
                        currentItem?.let { item ->
                            item.drmConfiguration != null || item.activeVersion?.drmConfiguration != null
                        } == true,
                    scaleMode = choices.scaleMode,
                    videoWidth =
                        state.diagnostics.videoWidth.takeIf { it > 0 }
                            ?: currentItem?.activeVersion?.sourceWidth
                            ?: 0,
                    videoHeight =
                        state.videoHeight.takeIf { it > 0 }
                            ?: currentItem?.activeVersion?.sourceHeight
                            ?: 0,
                    subtitleOffsetMs = presentationSubtitleControls.offsetMs,
                    subtitleScale = presentationSubtitleControls.scale,
                    secondarySubtitleScale = presentationSubtitleControls.secondaryScale,
                    subtitleBrightness = presentationSubtitleControls.brightness,
                    subtitlePosition = presentationSubtitleControls.position,
                    subtitleAppearance = presentationSubtitleControls.appearance,
                    modifier = pictureModifier,
                    visible = !inPictureInPicture,
                    ambientSampler = ambient.sampler,
                    ambientLayer = ambientLayer,
                )
            is MdkVideoEngine ->
                MdkSurface(
                    engine,
                    pictureModifier,
                    ambientSampler = ambient.sampler,
                    ambientLayer = ambientLayer,
                )
            is MpvVideoEngine ->
                MpvSurface(
                    engine,
                    pictureModifier,
                    ambientSampler = ambient.sampler,
                    ambientLayer = ambientLayer,
                    subtitlesInsidePicture = ambient.enabled,
                    subtitleControls = presentationSubtitleControls,
                )
            is ExoVideoEngine ->
                ExoSurface(
                    engine = engine,
                    scaleMode = choices.scaleMode,
                    subtitleScale = presentationSubtitleControls.scale,
                    secondarySubtitleScale = presentationSubtitleControls.secondaryScale,
                    subtitleBrightness = presentationSubtitleControls.brightness,
                    subtitlePosition = presentationSubtitleControls.position,
                    subtitleAppearance = presentationSubtitleControls.appearance,
                    modifier = pictureModifier,
                    ambientSampler = ambient.sampler,
                    ambientLayer = ambientLayer,
                )
        }

        // Placed outside the timeline scope so the chip's anchor is not rebuilt per tick.
        val statusChipModifier =
            Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 68.dp)
        PlaybackTimelineContent(livePlayback) { state ->
            val pictureReady = state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
            // Sound with no picture to wait for: ready the moment it is heard, for the
            // continuity overlay and the entrance's stand-in alike.
            val audioOnly =
                state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering &&
                    state.videoHeight <= 0 &&
                    currentItem?.activeVersion?.sourceVideoCodec.isNullOrBlank()
            PlaybackContinuityOverlay(
                artworkUrls = continuityArtwork,
                title = currentItem?.title.orEmpty(),
                visible =
                    transition?.coversPicture() != true &&
                        currentItem != null &&
                        state.error == null &&
                        !state.ended &&
                        !audioOnly &&
                        !pictureReady,
                message = continuityMessage,
                modifier = Modifier.fillMaxSize(),
            )
            PlayerTransitionLayer(
                state = transition,
                ready = state.error != null || pictureReady || audioOnly,
                inPictureInPicture = inPictureInPicture,
                aspectRatio = transitionAspectRatio(choices.scaleMode, state),
                layer = PlayerTransitionLayerKind.Entrance,
            )
            PlaybackStatusChip(
                visible = pictureReady && (state.buffering || networkRecovery.pending),
                message = statusChipMessage,
                modifier = statusChipModifier,
            )

            if (danmaku.enabled && danmaku.visibleComments.isNotEmpty()) {
                // Entering 画中画 used to cut the comment layer out between two frames, which
                // reads as the picture glitching rather than as the window changing shape.
                AnimatedVisibility(
                    visible = !inPictureInPicture,
                    enter = fadeIn(Motion.tween(pictureInPictureFadeMs)),
                    exit = fadeOut(Motion.tween(pictureInPictureFadeMs)),
                ) {
                    DanmakuOverlay(
                        comments = danmaku.visibleComments,
                        positionMs = state.positionMs,
                        playing = state.playing && !state.buffering,
                        playbackRate = state.speed,
                        displayArea = danmaku.displayArea,
                        fontSize = danmaku.fontSize,
                        speed = danmaku.speed,
                        opacity = danmaku.opacity,
                        picker = danmakuPicker,
                    )
                }
            }
        }

        // A player that arrived on a transition leaves on it too, whichever way the viewer
        // closes it, and the back gesture drives the first part of the way out as it moves.
        // Registered before the chrome so a drawer or a disc menu composed later still takes
        // the gesture first.
        PlatformPredictiveBackHandler(
            enabled = transition != null && !transition.disabled && !inPictureInPicture,
            onProgress = { transition?.onBackProgress(it) },
            onBack = onBack,
            onCancel = { transition?.onBackCancel() },
        )

        AnimatedVisibility(
            visible = !inPictureInPicture,
            modifier =
                if (tabletop == null) {
                    Modifier
                } else {
                    Modifier.fillMaxSize().padding(top = with(density) { tabletop.controlsTopPx.toDp() })
                },
            enter = fadeIn(Motion.tween(pictureInPictureFadeMs)),
            exit = ExitTransition.None,
        ) {
            controls(ambient)
        }

        // Over the chrome: 点弹幕's menu, and whatever a held 聊天 or 投屏 key has open.
        if (!inPictureInPicture) {
            if (danmaku.enabled) {
                DanmakuPickLayer(
                    picker = danmakuPicker,
                    onBlock = danmaku.onBlock,
                    onUnblock = danmaku.onUnblock,
                )
            }
            PlayerQuickPickLayer(chromeExtras)
        }

        PlayerFrameRateOverlay(
            playback = livePlayback,
            preferences = playbackPreferences,
            visible = !inPictureInPicture && !oledPauseProtectionActive.value,
            modifier =
                Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = 56.dp, end = 12.dp),
        )

        // Folded into the overlay's own visibility rather than an `if`, so leaving the
        // screensaver for 画中画 fades out instead of vanishing between two frames.
        OledPauseProtectionOverlay(
            visible = oledPauseProtectionActive.value && !inPictureInPicture,
            onDismiss = onDismissOledPauseProtection,
            modifier = Modifier.fillMaxSize(),
        )

        // The way out draws last so it covers the chrome and the paused frame; the way in
        // stays under the chrome above so the back button is reachable while the picture is
        // still being prepared.
        PlaybackTimelineContent(livePlayback) { state ->
            PlayerTransitionLayer(
                state = transition,
                ready = true,
                inPictureInPicture = inPictureInPicture,
                aspectRatio = transitionAspectRatio(choices.scaleMode, state),
                layer = PlayerTransitionLayerKind.Exit,
            )
        }
    }
}
