package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** One pair per panel, reused across frames. Never share mutable paths between dialogs. */
internal class DialogDrawCache(
    glow: Color,
) {
    val aperture = Path()
    val edge = Path()
    val sheen = Brush.horizontalGradient(listOf(Color.Transparent, glow.copy(alpha = 0.4f), Color.Transparent))
}
