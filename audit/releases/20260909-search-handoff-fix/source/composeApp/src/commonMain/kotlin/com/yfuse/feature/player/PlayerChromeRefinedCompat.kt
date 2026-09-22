package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility bridge for the controls refactor.
 *
 * The refined seek bar gained artwork-derived progress colouring after [PlayerControls] had
 * already been split into smaller files. Older call sites therefore do not yet carry an artwork
 * URL. Keep the refactor source-compatible and use the stable playback item index as the accent
 * identity; [RefinedBottomBar] falls back to the player progress accent until an artwork URL is
 * supplied by the caller in a later, dedicated API change.
 */
@Composable
internal fun RefinedBottomBar(
    state: PlaybackState,
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    trickplay: TrickplayStoryboard?,
    progressMarkers: List<PlaybackProgressMarker>,
    hasEpisodes: Boolean,
    onOpenEpisodes: () -> Unit,
    hasMultipleSources: Boolean,
    onOpenSources: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSpeed: () -> Unit,
    skipSettingsAvailable: Boolean,
    onOpenSkipSettings: () -> Unit,
    danmakuEnabled: Boolean,
    onOpenDanmaku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RefinedBottomBar(
        state = state,
        seekLocked = seekLocked,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onSeek = onSeek,
        onScrub = onScrub,
        trickplay = trickplay,
        progressMarkers = progressMarkers,
        hasEpisodes = hasEpisodes,
        onOpenEpisodes = onOpenEpisodes,
        hasMultipleSources = hasMultipleSources,
        onOpenSources = onOpenSources,
        onOpenSubtitles = onOpenSubtitles,
        onOpenAudio = onOpenAudio,
        onOpenSpeed = onOpenSpeed,
        skipSettingsAvailable = skipSettingsAvailable,
        onOpenSkipSettings = onOpenSkipSettings,
        danmakuEnabled = danmakuEnabled,
        onOpenDanmaku = onOpenDanmaku,
        artworkUrl = null,
        artworkIdentity = state.currentIndex,
        modifier = modifier,
    )
}
