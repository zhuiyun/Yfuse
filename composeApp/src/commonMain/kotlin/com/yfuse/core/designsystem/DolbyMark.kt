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
private val DolbyMark: ImageVector = ImageVector.Builder(
    name = "dolby-double-d",
    defaultWidth = 32.dp,
    defaultHeight = 24.dp,
    viewportWidth = 32f,
    viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    // Left D, mirrored: flat edge on the right, bulging left.
    moveTo(15f, 3f)
    horizontalLineTo(10f)
    arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = false, 10f, 21f)
    horizontalLineTo(15f)
    close()
    // Right D: flat edge on the left, bulging right.
    moveTo(17f, 3f)
    horizontalLineTo(22f)
    arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, 22f, 21f)
    horizontalLineTo(17f)
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
                DolbyMark,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.width(19.dp).height(14.dp),
            )
            Text("Dolby", style = sc(13.5f, 700), color = tint, maxLines = 1)
        }
        Text(
            caption,
            style = mr(8f, 600).copy(letterSpacing = 2.4.sp),
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
fun DolbyChip(caption: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            DolbyMark,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.width(13.dp).height(10.dp),
        )
        Text(caption, style = mr(9f, 700), color = tint, maxLines = 1)
    }
}
