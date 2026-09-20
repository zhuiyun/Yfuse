package com.yfuse.feature.player

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.yfuse.core.data.SeriesPlaybackPreference
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter

/**
 * What the continuity artwork says while the picture is being prepared. A reader rather than a
 * string, so it is evaluated where it is drawn and not on every position tick.
 */
@Composable
internal fun rememberContinuityMessage(
    livePlayback: State<PlaybackState>,
    networkRecovery: PlaybackNetworkRecoveryState,
    startIndex: Int,
): () -> String =
    remember(livePlayback, networkRecovery, startIndex) {
        {
            val live = livePlayback.value
            when {
                networkRecovery.pending -> "网络已恢复，正在续播"
                live.currentIndex != startIndex && live.positionMs < 3_000L -> "正在衔接下一集"
                else -> "正在准备画面"
            }
        }
    }

/** The rebuffering chip: recovery, a link slower than the stream, or plain rebuffering. */
@Composable
internal fun rememberStatusChipMessage(
    livePlayback: State<PlaybackState>,
    networkRecovery: PlaybackNetworkRecoveryState,
): () -> String =
    remember(livePlayback, networkRecovery) {
        {
            val diagnostics = livePlayback.value.diagnostics
            val bufferedSeconds =
                maxOf(
                    diagnostics.bufferedDurationMs,
                    diagnostics.sourceBufferedMs,
                ) / 1_000
            when {
                networkRecovery.pending -> "网络已恢复，正在续播"
                diagnostics.networkBitsPerSecond > 0L &&
                    diagnostics.bitrateBitsPerSecond > 0L &&
                    diagnostics.networkBitsPerSecond < diagnostics.bitrateBitsPerSecond ->
                    "网络速度不足 · 已缓冲 $bufferedSeconds 秒"
                else -> "正在重新缓冲 · 已缓冲 $bufferedSeconds 秒"
            }
        }
    }

/**
 * The rendering target of whichever backend is attached. Each host places [ambientLayer] above
 * its surface and below its own captions.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerVideoSurface(
    engine: VideoEngine,
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    scaleMode: VideoScaleMode,
    presentationSubtitleControls: SubtitleControlState,
    inPictureInPicture: Boolean,
    ambient: PlayerAmbientBinding,
    ambientLayer: @Composable () -> Unit,
) {
    when (engine) {
        is YPlayerVideoEngineAdapter ->
            Core2Surface(
                engine = engine,
                protectedContent =
                    currentItem?.let { item ->
                        item.drmConfiguration != null || item.activeVersion?.drmConfiguration != null
                    } == true,
                scaleMode = scaleMode,
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
                modifier = Modifier.fillMaxSize(),
                visible = !inPictureInPicture,
                ambientSampler = ambient.sampler,
                ambientLayer = ambientLayer,
            )
        is MdkVideoEngine ->
            MdkSurface(
                engine,
                Modifier.fillMaxSize(),
                ambientSampler = ambient.sampler,
                ambientLayer = ambientLayer,
            )
        is MpvVideoEngine ->
            MpvSurface(
                engine,
                Modifier.fillMaxSize(),
                ambientSampler = ambient.sampler,
                ambientLayer = ambientLayer,
                subtitlesInsidePicture = ambient.enabled,
                subtitleControls = presentationSubtitleControls,
            )
        is ExoVideoEngine ->
            ExoSurface(
                engine = engine,
                scaleMode = scaleMode,
                subtitleScale = presentationSubtitleControls.scale,
                secondarySubtitleScale = presentationSubtitleControls.secondaryScale,
                subtitleBrightness = presentationSubtitleControls.brightness,
                subtitlePosition = presentationSubtitleControls.position,
                subtitleAppearance = presentationSubtitleControls.appearance,
                modifier = Modifier.fillMaxSize(),
                ambientSampler = ambient.sampler,
                ambientLayer = ambientLayer,
            )
    }
}

/**
 * Everything drawn over the picture on the position tick: the continuity artwork, the arriving
 * poster morph, the rebuffering chip and the comment layer. [statusChipModifier] is built by the
 * caller, inside the Box that anchors it.
 */
@Composable
internal fun PlayerTimelineOverlays(
    livePlayback: State<PlaybackState>,
    currentItem: PlayerMediaItem?,
    artworkMorph: PlayerArtworkMorphState?,
    inPictureInPicture: Boolean,
    scaleMode: VideoScaleMode,
    continuityArtwork: List<String?>,
    continuityMessage: () -> String,
    statusChipMessage: () -> String,
    statusChipModifier: Modifier,
    networkRecovery: PlaybackNetworkRecoveryState,
    danmaku: PlayerDanmakuController,
    pictureInPictureFadeMs: Int,
) {
    PlaybackTimelineContent(livePlayback) { state ->
        PlaybackContinuityOverlay(
            artworkUrls = continuityArtwork,
            title = currentItem?.title.orEmpty(),
            visible =
                artworkMorph?.visible != true &&
                    currentItem != null &&
                    state.error == null &&
                    !state.ended &&
                    !(
                        state.diagnostics.effectiveAudioReadiness == PlaybackOutputReadiness.Rendering &&
                            state.videoHeight <= 0 &&
                            currentItem.activeVersion?.sourceVideoCodec.isNullOrBlank()
                    ) &&
                    state.diagnostics.effectiveVideoReadiness != PlaybackOutputReadiness.Rendering,
            message = continuityMessage,
            modifier = Modifier.fillMaxSize(),
        )
        PlayerArtworkMorph(
            state = artworkMorph,
            ready =
                state.error != null ||
                    state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering,
            inPictureInPicture = inPictureInPicture,
            aspectRatio = artworkMorphAspectRatio(scaleMode, state),
            layer = PlayerArtworkMorphLayer.Entrance,
        )
        PlaybackStatusChip(
            visible =
                state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering &&
                    (state.buffering || networkRecovery.pending),
            message = statusChipMessage,
            modifier = statusChipModifier,
        )

        if (danmaku.enabled && danmaku.visibleComments.isNotEmpty()) {
            // Entering 画中画 used to cut the comment layer out between two frames, which
            // reads as the picture glitching rather than as the window changing shape.
            AnimatedVisibility(
                visible = !inPictureInPicture,
                enter = fadeIn(tween(pictureInPictureFadeMs)),
                exit = fadeOut(tween(pictureInPictureFadeMs)),
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
                )
            }
        }
    }
}

/** The audio panel as this backend can honour it; Auto may still switch engines to honour the rest. */
internal fun playerAudioControlState(
    audioControls: AudioControlState,
    state: PlaybackState,
    backendExtensions: PlayerBackendExtensions,
    kind: PlayerEngine,
    sessionEngineSelection: PlaybackEngineSelection,
    core2NativeOnlyActive: Boolean,
): AudioControlState =
    audioControls.copy(
        measuredAvOffsetMs = state.diagnostics.avSyncOffsetMs,
        available =
            backendExtensions.supportsAudioDelay ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        enhancementAvailable =
            backendExtensions.supportsAudioEnhancement ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        unavailableReason =
            if (
                kind == PlayerEngine.Mpv ||
                sessionEngineSelection == PlaybackEngineSelection.Auto
            ) {
                null
            } else {
                "当前锁定模式不支持音频延迟，请在高级设置中改回自动选择。"
            },
    )

/** Delay is stored per output route as well as per series; enhancement per series only. */
internal fun playerAudioControlActions(
    trackSession: PlayerTrackSession,
    livePlayback: State<PlaybackState>,
    audioOutputDelayPreferences: AudioOutputDelayPreferences,
    context: Context,
    rememberSeriesPlayback: ((SeriesPlaybackPreference) -> SeriesPlaybackPreference) -> Unit,
): AudioControlActions {
    var audioControls by trackSession.audioControls
    val lastVerifiedAudioRoute by trackSession.lastVerifiedAudioRoute
    return AudioControlActions(
        onDelay = {
            audioControls = audioControls.copy(delayMs = it)
            audioOutputDelayPreferences.write(lastVerifiedAudioRoute, it)
            rememberSeriesPlayback { remembered -> remembered.copy(audioDelayMs = it) }
        },
        onAutoSync = {
            livePlayback.value.diagnostics.avSyncOffsetMs?.let { measured ->
                val corrected =
                    calibratedAudioDelayMs(audioControls.delayMs, measured)
                audioControls = audioControls.copy(delayMs = corrected)
                audioOutputDelayPreferences.write(lastVerifiedAudioRoute, corrected)
                rememberSeriesPlayback { remembered ->
                    remembered.copy(audioDelayMs = corrected)
                }
                Toast
                    .makeText(context, "已校准音画同步：$corrected ms", Toast.LENGTH_SHORT)
                    .show()
            }
        },
        onEnhancement = {
            audioControls = audioControls.copy(enhancement = it)
            rememberSeriesPlayback { remembered ->
                remembered.copy(audioEnhancement = it.name)
            }
        },
    )
}

/** The subtitle panel as this backend can honour it, with the reason when it cannot. */
@OptIn(UnstableApi::class)
internal fun playerSubtitleControlState(
    subtitleControls: SubtitleControlState,
    secondarySubtitleTrackId: String?,
    engine: VideoEngine,
    state: PlaybackState,
    backendExtensions: PlayerBackendExtensions,
    sessionEngineSelection: PlaybackEngineSelection,
    core2NativeOnlyActive: Boolean,
): SubtitleControlState =
    subtitleControls.copy(
        secondaryTrackId = secondarySubtitleTrackId,
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
                            secondarySubtitleTrackId,
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
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        scaleAvailable =
            backendExtensions.supportsSubtitleScale ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        brightnessAvailable =
            backendExtensions.supportsSubtitleBrightness ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        positionAvailable =
            backendExtensions.supportsSubtitlePosition ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        appearanceAvailable =
            backendExtensions.supportsSubtitleAppearance ||
                (
                    sessionEngineSelection == PlaybackEngineSelection.Auto &&
                        !core2NativeOnlyActive
                ),
        unavailableReason =
            if (
                sessionEngineSelection == PlaybackEngineSelection.Auto &&
                !core2NativeOnlyActive
            ) {
                "调整后将自动切换到支持该功能的播放内核。"
            } else if (core2NativeOnlyActive) {
                "YCore Native 纯内核模式不允许兼容内核接管此项调节。"
            } else {
                "当前锁定内核不支持此项调节，请在播放内核中选择自动或 MPV。"
            },
    )

/**
 * Every subtitle adjustment writes the session state the engines restore from and the series
 * memory the next episode starts from, in that order.
 */
internal fun playerSubtitleControlActions(
    trackSession: PlayerTrackSession,
    state: PlaybackState,
    currentItem: PlayerMediaItem?,
    player: YPlayer,
    backendExtensions: PlayerBackendExtensions,
    context: Context,
    rememberSeriesPlayback: ((SeriesPlaybackPreference) -> SeriesPlaybackPreference) -> Unit,
): SubtitleControlActions {
    var handoverItemId by trackSession.handoverItemId
    var subtitleRestore by trackSession.subtitleRestore
    var secondarySubtitleRestore by trackSession.secondarySubtitleRestore
    var secondarySubtitleTrackId by trackSession.secondarySubtitleTrackId
    var restoreSubtitlesOff by trackSession.restoreSubtitlesOff
    var subtitleControls by trackSession.subtitleControls

    fun applySubtitlePair(
        primary: EngineTrack,
        secondary: EngineTrack,
    ) {
        if (!backendExtensions.supportsSecondarySubtitleTrack) return
        val oldPrimary = state.subtitleTracks.firstOrNull { it.selected }
        val oldSecondary = secondarySubtitleTrackId
        backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
        player.selectTrack(YTrackType.Subtitle, primary.id)
        if (!backendExtensions.selectSecondarySubtitleTrack(secondary.id)) {
            player.selectTrack(YTrackType.Subtitle, oldPrimary?.id ?: EngineTrack.OFF)
            oldSecondary?.let(backendExtensions::selectSecondarySubtitleTrack)
            Toast.makeText(context, "当前内核无法应用此双字幕方案", Toast.LENGTH_SHORT).show()
            return
        }
        handoverItemId = currentItem?.id
        subtitleRestore = state.subtitleTracks.restorePreferenceFor(primary)
        secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(secondary)
        secondarySubtitleTrackId = secondary.id
        restoreSubtitlesOff = false
        rememberSeriesPlayback {
            it.copy(
                primarySubtitlesOff = false,
                primarySubtitle = primary.toRememberedPlaybackTrack(),
                secondarySubtitle = secondary.toRememberedPlaybackTrack(),
            )
        }
    }

    return SubtitleControlActions(
        onSecondaryScale = { value ->
            subtitleControls = subtitleControls.copy(secondaryScale = value.coerceIn(0.6f, 1.8f))
            rememberSeriesPlayback {
                it.copy(
                    secondarySubtitleScale = subtitleControls.secondaryScale,
                )
            }
        },
        onSwap = {
            val primary = state.subtitleTracks.firstOrNull { it.selected }
            val secondary = state.subtitleTracks.firstOrNull { it.id == secondarySubtitleTrackId }
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
            subtitleControls = subtitleControls.copy(offsetMs = it)
            rememberSeriesPlayback { remembered ->
                remembered.copy(subtitleOffsetMs = it)
            }
        },
        onScale = {
            subtitleControls =
                subtitleControls.copy(
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
            subtitleControls =
                subtitleControls.copy(
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
            subtitleControls =
                subtitleControls.copy(
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
            subtitleControls =
                subtitleControls.copy(
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
            val appearance = subtitleControls.appearance.copy(textColorArgb = color)
            subtitleControls =
                subtitleControls.copy(
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
            val appearance = subtitleControls.appearance.copy(backgroundColorArgb = color)
            subtitleControls =
                subtitleControls.copy(
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
            val appearance = subtitleControls.appearance.copy(outlineColorArgb = color)
            subtitleControls =
                subtitleControls.copy(
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
            val appearance = subtitleControls.appearance.copy(outlineWidth = width)
            subtitleControls =
                subtitleControls.copy(
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
                subtitleControls = subtitleControls.copy(secondaryOffsetMs = offset)
                rememberSeriesPlayback { it.copy(secondarySubtitleOffsetMs = offset) }
            }
        },
        onSecondaryTrack = secondary@{ id ->
            if (id == EngineTrack.OFF) {
                backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
                secondarySubtitleTrackId = null
                secondarySubtitleRestore = null
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
            handoverItemId = currentItem?.id
            secondarySubtitleTrackId = id
            secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(track)
            rememberSeriesPlayback { remembered ->
                remembered.copy(secondarySubtitle = track.toRememberedPlaybackTrack())
            }
        },
    )
}

/** The fitted video rectangle the poster morphs to; the whole surface when the picture fills it. */
internal fun artworkMorphAspectRatio(
    scaleMode: VideoScaleMode,
    state: PlaybackState,
): Float? =
    if (scaleMode == VideoScaleMode.Fit && state.videoHeight > 0) {
        state.diagnostics.videoWidth.toFloat() / state.videoHeight
    } else {
        null
    }

/** 「标题 N」, or the disc's own edition/playlist name where it authored one. */
internal fun discTitleToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String = navigation.titleOptions.getOrNull(index)?.label ?: "标题 ${index + 1}"

/**
 * 「第 N 章 · 章节名」.
 *
 * The authored name is appended only when the disc carries one: the chapter's own label falls
 * back to 「章节 N」, which next to the number would just say the same thing twice.
 */
internal fun discChapterToast(
    navigation: PlaybackDiscNavigationState,
    index: Int,
): String {
    val authored =
        navigation.chapterOptions
            .getOrNull(index)
            ?.title
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    return "第 ${index + 1} 章" + authored?.let { " · $it" }.orEmpty()
}
