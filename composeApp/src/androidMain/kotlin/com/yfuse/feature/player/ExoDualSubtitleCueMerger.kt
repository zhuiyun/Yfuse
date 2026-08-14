package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput

/**
 * Combines the normal ExoPlayer text output with a separately decoded secondary subtitle stream.
 *
 * The primary renderer still owns timing and PlayerView integration. Secondary text cues with no
 * authored position are moved two lines above the normal bottom row; explicitly positioned ASS/SSA
 * cues and bitmap subtitles keep their authored coordinates.
 */
@UnstableApi
internal class ExoDualSubtitleCueMerger {
    private var primary = CueGroup.EMPTY_TIME_ZERO
    private var secondary = CueGroup.EMPTY_TIME_ZERO
    private var downstream: TextOutput? = null

    fun primaryOutput(output: TextOutput): TextOutput {
        synchronized(this) { downstream = output }
        return TextOutput { cues -> updatePrimary(cues) }
    }

    fun secondaryOutput(): TextOutput = TextOutput { cues -> updateSecondary(cues) }

    fun clearSecondary() {
        updateAndDispatch { secondary = CueGroup.EMPTY_TIME_ZERO }
    }

    private fun updatePrimary(cues: CueGroup) {
        updateAndDispatch { primary = cues }
    }

    private fun updateSecondary(cues: CueGroup) {
        updateAndDispatch { secondary = cues }
    }

    private fun updateAndDispatch(update: () -> Unit) {
        val snapshot =
            synchronized(this) {
                update()
                downstream to mergedLocked()
            }
        snapshot.first?.onCues(snapshot.second)
    }

    private fun mergedLocked(): CueGroup {
        if (secondary.cues.isEmpty()) return primary
        val secondaryCues = secondary.cues.map(::secondaryDisplayCue)
        if (primary.cues.isEmpty()) {
            return CueGroup(secondaryCues, secondary.presentationTimeUs)
        }
        return CueGroup(
            primary.cues + secondaryCues,
            maxOf(primary.presentationTimeUs, secondary.presentationTimeUs),
        )
    }
}

@UnstableApi
private fun secondaryDisplayCue(cue: Cue): Cue =
    if (cue.text != null && cue.line == Cue.DIMEN_UNSET) {
        cue.buildUpon().setLine(SECONDARY_SUBTITLE_LINE, Cue.LINE_TYPE_NUMBER).build()
    } else {
        cue
    }

private const val SECONDARY_SUBTITLE_LINE = -3f
