package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.model.PlaybackChapter

/**
 * 全程缩略图 — the trickplay card over the picture while it is scrubbed away from the bar: the
 * sideways swipe across the picture, and the side thirds' held scan. Both used to be text
 * alone ("+1:30 · 12:34 / 45:00"), which says where the scrub has got to but not what is there.
 *
 * Sits just above the gesture HUD, the readout it illustrates. [positionMs] is a reader, null
 * while nothing is being scrubbed, so a scrub's samples recompose this card and nothing around
 * it. Without a storyboard there is nothing to show and the HUD carries on alone.
 */
@Composable
internal fun PictureScrubPreview(
    storyboard: TrickplayStoryboard?,
    positionMs: () -> Long?,
    chapters: List<PlaybackChapter>,
    modifier: Modifier = Modifier,
) {
    if (storyboard == null) return
    val position = positionMs()
    // The card leaves showing the last frame it had, not an empty one. A plain holder, not
    // state: it only remembers, and never needs to recompose anything.
    val last = remember { LongArray(1) }
    if (position != null) last[0] = position
    val shown = position ?: last[0]
    val chapterLine = chapters.isNotEmpty()
    val cardHeight = trickplayPreviewHeight(storyboard, chapterLine)
    AnimatedVisibility(
        visible = position != null,
        enter = fadeIn(Motion.tween(Motion.QUICK)),
        exit = fadeOut(Motion.tween(Motion.QUICK)),
        // Centred above the HUD, whose own centre is the frame's, clear of the paused key too.
        modifier = modifier.offset(y = -(cardHeight / 2 + PictureScrubPreviewClearance)),
    ) {
        TrickplayPreview(
            storyboard = storyboard,
            positionMs = shown,
            chapter = fileChapterNameAt(chapters, shown),
            chapterLine = chapterLine,
        )
    }
}

/** From the centre of the frame to the card's bottom edge: half the HUD and a little air. */
private val PictureScrubPreviewClearance: Dp = 32.dp
