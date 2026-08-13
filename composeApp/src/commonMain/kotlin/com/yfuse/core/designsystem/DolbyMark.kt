package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The double-D, drawn rather than shipped as an asset.
 *
 * Two D's back to back, straight edges facing each other across a gap, curves outward.
 * A 32×24 viewBox so the glyph is wider than tall the way the real mark is; tint it like
 * any other icon and it sits on artwork or on the page equally.
 */

/** The official double-D silhouette uses counters; two solid half-discs read as brackets. */
private val DolbyDoubleD: ImageVector =
    ImageVector
        .Builder(
            name = "dolby-double-d-correct",
            defaultWidth = 32.dp,
            defaultHeight = 24.dp,
            viewportWidth = 32f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(15f, 3f)
            horizontalLineTo(10f)
            curveTo(4.5f, 3f, 1f, 6.5f, 1f, 12f)
            curveTo(1f, 17.5f, 4.5f, 21f, 10f, 21f)
            horizontalLineTo(15f)
            close()
            moveTo(11.5f, 7f)
            horizontalLineTo(10f)
            curveTo(7f, 7f, 5f, 9f, 5f, 12f)
            curveTo(5f, 15f, 7f, 17f, 10f, 17f)
            horizontalLineTo(11.5f)
            close()

            moveTo(17f, 3f)
            horizontalLineTo(22f)
            curveTo(27.5f, 3f, 31f, 6.5f, 31f, 12f)
            curveTo(31f, 17.5f, 27.5f, 21f, 22f, 21f)
            horizontalLineTo(17f)
            close()
            moveTo(20.5f, 7f)
            horizontalLineTo(22f)
            curveTo(25f, 7f, 27f, 9f, 27f, 12f)
            curveTo(27f, 15f, 25f, 17f, 22f, 17f)
            horizontalLineTo(20.5f)
            close()
        }.build()

/**
 * `◖◗ Dolby / VISION` — the format badge, as Dolby's own guidelines set it: the mark and
 * the wordmark on one line, the technology name spaced out underneath.
 *
 * Shown only when the file actually carries the format. A badge that is always there says
 * nothing; this one is the answer to "is this the good copy", which on a page listing
 * several copies of the same title is the question being asked.
 */
@Composable
fun DolbyBadge(
    caption: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                DolbyDoubleD,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.width(19.dp).height(14.dp),
            )
            Text("Dolby", style = AppTypography.body.strong, color = tint, maxLines = 1)
        }
        Text(
            caption,
            style = AppTypography.caption.strong.copy(letterSpacing = 2.4.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * The same mark at chip scale, for a row that is already dense — a version list, a source
 * card — where the stacked badge would be the tallest thing in it.
 */
@Composable
fun DolbyChip(
    caption: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            DolbyDoubleD,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.width(13.dp).height(10.dp),
        )
        Text(caption, style = AppTypography.caption.strong, color = tint, maxLines = 1)
    }
}
