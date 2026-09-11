package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleTimeBase

/** A child demuxer sees one Period; the public player and recovery commands see the whole title. */
internal fun mapAdaptivePresentationState(
    state: YPlayerState,
    target: YAdaptivePlaybackTarget?,
): YPlayerState {
    if (target == null) return state
    val duration =
        target.presentationDurationMs.takeIf { it > 0L }
            ?: state.durationMs + target.presentationOffsetMs

    fun global(local: Long): Long =
        (local + target.presentationOffsetMs).let {
            if (duration > 0L) it.coerceAtMost(duration) else it
        }
    val offsetUs = target.presentationOffsetMs.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L

    fun translated(value: Long): Long = value.coerceAtMost(Long.MAX_VALUE - offsetUs) + offsetUs

    fun subtitles(cues: List<YSubtitleCue>): List<YSubtitleCue> =
        cues.map { cue ->
            if (cue.timeBase == YSubtitleTimeBase.Presentation) {
                cue
            } else {
                val startUs = translated(cue.startUs).coerceAtMost(Long.MAX_VALUE - 1L)
                cue.copy(
                    startUs = startUs,
                    endUs = translated(cue.endUs).coerceAtLeast(startUs + 1L),
                    sourceTimeOffsetUs = translated(cue.sourceTimeOffsetUs),
                    timeBase = YSubtitleTimeBase.Presentation,
                )
            }
        }
    return state.copy(
        positionMs = global(state.positionMs),
        bufferedPositionMs = global(state.bufferedPositionMs),
        durationMs = duration,
        subtitleCues = subtitles(state.subtitleCues),
        secondarySubtitleCues = subtitles(state.secondarySubtitleCues),
    )
}
