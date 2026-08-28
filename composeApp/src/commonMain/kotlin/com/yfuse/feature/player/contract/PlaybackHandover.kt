package com.yfuse.feature.player

/** Engine-neutral state that must survive a backend or media-source rebuild. */
internal data class PlaybackHandoverSnapshot(
    val itemIndex: Int,
    val positionMs: Long,
    val playbackRequested: Boolean,
    val speed: Float,
    val audioTrack: TrackRestorePreference? = null,
    val primarySubtitle: TrackRestorePreference? = null,
    val secondarySubtitle: TrackRestorePreference? = null,
    val subtitlesOff: Boolean = false,
    val subtitleDelayMs: Long = 0L,
    val audioDelayMs: Long = 0L,
    val discTitleIndex: Int? = null,
    val discChapterIndex: Int? = null,
)

/** Captures user intent instead of inferring it from buffering/rendering state. */
internal fun playbackHandoverSnapshot(
    state: PlaybackState,
    currentPositionMs: Long,
    playbackRequested: Boolean,
    requestedSpeed: Float,
    secondarySubtitle: TrackRestorePreference? = null,
    subtitleDelayMs: Long = 0L,
    audioDelayMs: Long = 0L,
): PlaybackHandoverSnapshot =
    PlaybackHandoverSnapshot(
        itemIndex = state.currentIndex.coerceAtLeast(0),
        positionMs = currentPositionMs.coerceAtLeast(0L),
        playbackRequested = playbackRequested && !state.ended,
        speed = requestedSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f,
        audioTrack = state.audioTracks.firstOrNull { it.selected }?.toRestorePreference(),
        primarySubtitle = state.subtitleTracks.firstOrNull { it.selected }?.toRestorePreference(),
        secondarySubtitle = secondarySubtitle,
        subtitlesOff = state.subtitleTracks.isNotEmpty() && state.subtitleTracks.none { it.selected },
        subtitleDelayMs = subtitleDelayMs,
        audioDelayMs = audioDelayMs,
        discTitleIndex =
            state.discNavigation.selectedTitleIndex.takeIf {
                state.discNavigation.effectiveTitleCount > 0
            },
        discChapterIndex =
            state.discNavigation.selectedChapterIndex.takeIf {
                state.discNavigation.effectiveChapterCount > 0
            },
    )

internal const val PLAYBACK_HANDOVER_POSITION_TOLERANCE_MS = 250L

/** A replacement snapshot belongs to exactly one engine item and is validated at most once. */
internal fun shouldValidatePlaybackHandoverPosition(
    snapshot: PlaybackHandoverSnapshot,
    currentItemIndex: Int,
    alreadyValidated: Boolean,
): Boolean = !alreadyValidated && currentItemIndex == snapshot.itemIndex

/**
 * The replacement may legitimately advance while it starts playing. Its first position must stay
 * between the captured point and wall-clock advancement, with 250 ms allowed on either edge.
 */
internal fun handoverPositionErrorMs(
    actualPositionMs: Long,
    snapshot: PlaybackHandoverSnapshot,
    elapsedSinceEngineCreationMs: Long,
): Long {
    val lower = (snapshot.positionMs - PLAYBACK_HANDOVER_POSITION_TOLERANCE_MS).coerceAtLeast(0L)
    val legalAdvance =
        if (snapshot.playbackRequested) {
            (elapsedSinceEngineCreationMs.coerceAtLeast(0L) * snapshot.speed).toLong()
        } else {
            0L
        }
    val upper = snapshot.positionMs + legalAdvance + PLAYBACK_HANDOVER_POSITION_TOLERANCE_MS
    return when {
        actualPositionMs < lower -> lower - actualPositionMs
        actualPositionMs > upper -> actualPositionMs - upper
        else -> 0L
    }
}
