package com.yfuse.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yfuse.tv.player.TvPlayerChromeState

/*
 * The two bars of PlayerControls' chrome, up and down with the rest of it. Each counts itself in
 * and out of what 氛围光 treats as chrome ([onAmbientPresence]); the rest is theirs.
 *
 * [controlState] is PlayerControls' snapshot of the playback, read here through the same delegate
 * and in the same places the controls read it, so a bar recomposes on what it did before and a tap
 * still sees the state of the moment it lands.
 */

/** The title bar. Top-level actions (投屏/更多) live with the title; media navigation stays below. */
@Composable
internal fun BoxScope.PlayerTopChrome(
    chrome: PlayerChromeState,
    playback: State<PlaybackState>,
    controlState: State<PlaybackState>,
    transport: PlayerTransportState,
    picture: PlayerPictureState,
    pictureActions: PlayerPictureActions,
    source: PlayerSourceState,
    cast: PlayerCastState,
    watch: WatchRoomState,
    host: PlayerChromeHost,
    onAmbientPresence: (Boolean) -> Unit,
) {
    val state by controlState
    ChromeVisibility(
        visible = chrome.visible,
        edge = ChromeEdge.Top,
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        DisposableEffect(Unit) {
            onAmbientPresence(true)
            onDispose { onAmbientPresence(false) }
        }
        val readout by remember(playback, source.sourceLabel, source.containerLabel) {
            derivedStateOf { playback.value.readoutLine(source.sourceLabel, source.containerLabel) }
        }
        RefinedTopBar(
            title =
                transport.episodes
                    .getOrNull(state.currentIndex)
                    ?.title
                    .orEmpty(),
            subtitle = readout,
            scaleMode = picture.scaleMode,
            dolbyVision = source.dolbyVision,
            dolbyAtmos = source.dolbyAtmos,
            onBack = host.onBack,
            onEnterPictureInPicture = host.onEnterPictureInPicture,
            onToggleFill = { stretch ->
                chrome.poke()
                pictureActions.onToggleFill(stretch)
            },
            onOpenCast = { chrome.openSettingsPanel(SettingsPanelKind.Cast) },
            onOpenMore = { chrome.openSettingsPanel(SettingsPanelKind.More) },
            ambientLight = picture.ambientLight,
            castActive = cast.active,
            watchConnected = watch.connected,
            unreadChat =
                watch.chatMessages.lastOrNull()?.id?.let { latest ->
                    chrome.lastReadChatId?.let { latest > it } ?: true
                } ?: false,
            onOpenChat = { chrome.openWatchChat(watch.chatMessages) },
            extras = host.extras,
            onKeyActivity = chrome::poke,
        )
    }
}

/** The transport bar: the rail, the transport keys and the chip row under them. */
@Composable
internal fun BoxScope.PlayerBottomChrome(
    chrome: PlayerChromeState,
    gestureState: PlayerGestureState,
    playback: State<PlaybackState>,
    controlState: State<PlaybackState>,
    /** A remote's seek in progress, which the rail shows ahead of the engine. */
    remoteChromeState: TvPlayerChromeState?,
    watchLocked: Boolean,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    picture: PlayerPictureState,
    source: PlayerSourceState,
    danmaku: PlayerDanmakuState,
    danmakuActions: DanmakuPanelActions,
    /** 没听清 behind a held ⟲10. */
    onMissedLine: () -> Unit,
    /** Where a remote's focus lands when the controls come up; see [RefinedBottomBar]. */
    playKeyModifier: Modifier,
    onAmbientPresence: (Boolean) -> Unit,
) {
    val state by controlState
    val skip = transport.skip
    ChromeVisibility(
        visible = chrome.visible,
        edge = ChromeEdge.Bottom,
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        DisposableEffect(Unit) {
            onAmbientPresence(true)
            onDispose {
                onAmbientPresence(false)
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
                    chrome.poke()
                    transportActions.onPlayPause()
                },
                onPrevious = {
                    chrome.poke()
                    transportActions.onPreviousItem()
                },
                onNext = {
                    chrome.poke()
                    transportActions.onNextItem()
                },
                onSeek = {
                    chrome.poke()
                    transportActions.onSeek(it)
                },
                onScrub = {
                    // Every touch sample lands here. `interactions` is read by this
                    // whole control tree and keys two effects, so bumping it per sample
                    // rebuilt ~2,300 lines of chrome each frame of a drag. The hide timer
                    // already waits on `scrubbing`, and the release pokes it afresh.
                    if (gestureState.scrub()) chrome.interactions++
                },
                onScrubEnd = {
                    gestureState.endScrub()
                    chrome.poke()
                },
                trickplay = transport.trickplay,
                progressMarkers =
                    remember(
                        skip.introStartSeconds,
                        skip.introEndSeconds,
                        skip.creditsLeadSeconds,
                        state.durationMs,
                        transport.chapters,
                    ) {
                        playbackProgressMarkers(
                            skip,
                            state.durationMs,
                            transport.chapters.asProgressChapters(),
                        )
                    },
                hasEpisodes = state.itemCount > 1,
                onOpenEpisodes = {
                    transportActions.onRefreshEpisodes()
                    chrome.openEpisodeDrawer()
                },
                hasMultipleSources = source.sourceOptions.size > 1,
                onOpenSources = { chrome.openQuickPopup(QuickPopup.Source) },
                onOpenSubtitles = {
                    chrome.openSettingsPanel(SettingsPanelKind.Tracks, TrackPanelMode.Subtitle)
                },
                onOpenAudio = {
                    chrome.openSettingsPanel(SettingsPanelKind.Tracks, TrackPanelMode.Audio)
                },
                onOpenSpeed = { chrome.openQuickPopup(QuickPopup.Speed) },
                skipSettingsAvailable = skip.seriesName != null,
                onOpenSkipSettings = { chrome.openSettingsPanel(SettingsPanelKind.Skip) },
                danmakuEnabled = danmaku.panel.enabled,
                onToggleDanmaku = danmakuActions.onToggle,
                onOpenDanmaku = { chrome.openSettingsPanel(SettingsPanelKind.Danmaku) },
                // 进度条跟随作品取色: the series poster, or the episode still without one.
                artworkUrl =
                    transport.episodes.getOrNull(state.currentIndex)?.let { it.posterUrl ?: it.stillUrl },
                artworkIdentity = state.currentIndex,
                ambientLight = picture.ambientLight,
                danmakuHeat = danmaku.heat,
                onSeekBackwardLongPress = onMissedLine,
                playKeyModifier = playKeyModifier,
            )
        }
    }
}
