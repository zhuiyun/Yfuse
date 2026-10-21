package com.yfuse.feature.player

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/** Explicit WebVTT/ASS positioning and vertical writing stay with Media3's authored renderer. */
@UnstableApi
internal fun exoCanPlaceInBlackBar(cue: Cue): Boolean =
    cue.bitmap == null &&
        (cue.line == Cue.DIMEN_UNSET || (cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line < 0f)) &&
        (cue.position == Cue.DIMEN_UNSET || (cue.position == 0.5f && cue.positionAnchor == Cue.ANCHOR_TYPE_MIDDLE)) &&
        (cue.size == Cue.DIMEN_UNSET || cue.size == 1f) &&
        cue.verticalType == Cue.TYPE_UNSET

/** WebVTT assigns -1 to the lowest automatic line, -2 above it, etc. Keep that reading order. */
@UnstableApi
internal fun exoBottomCueOrder(cues: List<Cue>): List<Cue> =
    if (cues.all { it.lineType == Cue.LINE_TYPE_NUMBER && it.line < 0f && it.line != Cue.DIMEN_UNSET }) {
        cues.sortedBy { it.line }
    } else {
        cues
    }
