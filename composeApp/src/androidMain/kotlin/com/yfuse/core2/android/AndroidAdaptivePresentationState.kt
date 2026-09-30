package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.isPrematurePlaybackEnd
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

/**
 * Where the title continues once a child's own [state] ended this target's Period: the Period's end
 * on the whole title. Null while the Period plays, when no later Period is known (the last one, or a
 * title of unknown duration), and when it ended short of its duration: that is a transport failure
 * for the router to recover, not a boundary to cross.
 */
internal fun YAdaptivePlaybackTarget.nextPeriodStartMs(state: YPlayerState): Long? =
    periodEndGlobalMs?.takeIf { endMs ->
        state.phase == YPlaybackPhase.Ended &&
            !isPrematurePlaybackEnd(state.positionMs, state.durationMs) &&
            presentationDurationMs > endMs
    }

/**
 * Carries one output evidence generation through every child a player republishes.
 *
 * outputEvidenceGeneration is monotonic for the playback session, and a newer value is how a
 * consumer recognises output from after a seek or a rebuild. Each child counts from its own start,
 * so republished as-is every rebuild went back to zero, and a child that reset as often as the one
 * before it (a seek back into a Period opens, seeks and resumes exactly as the seek forward did)
 * never showed its picture under a generation newer than the one before the seek. A child's count
 * now continues after everything the player published before that child.
 */
internal class ChildOutputEvidenceSequence {
    private var child: Any? = null
    private var base = 0L

    /** [state] of [child] renumbered to follow [published], the player's state before this one. */
    fun continued(
        child: Any,
        state: YPlayerState,
        published: YPlayerState,
    ): YPlayerState {
        if (this.child !== child) {
            this.child = child
            base = published.diagnostics.outputEvidenceGeneration.let { if (it == Long.MAX_VALUE) it else it + 1L }
        }
        val generation = state.diagnostics.outputEvidenceGeneration.coerceIn(0L, Long.MAX_VALUE - base)
        return state.copy(diagnostics = state.diagnostics.copy(outputEvidenceGeneration = base + generation))
    }
}
