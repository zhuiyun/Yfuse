package com.yfuse.feature.player

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.model.PlaybackChapter

/**
 * 全程缩略图 — the trickplay card over the picture while it is scrubbed away from the bar: the
 * sideways swipe across the picture, and on a television a held fast-forward or rewind on the
 * remote. The swipe used to be text alone ("+1:30 · 12:34 / 45:00"), which says where the scrub
 * has got to but not what is there.
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
    val position = positionMs() ?: return
    TrickplayPreview(
        storyboard, position,
        modifier = modifier.offset(y = -(trickplayPreviewHeight(storyboard, chapters.isNotEmpty()) / 2 + PictureScrubPreviewClearance)),
        chapter = fileChapterNameAt(chapters, position),
        chapterLine = chapters.isNotEmpty(),
    )
}

private val PictureScrubPreviewClearance = 32.dp
