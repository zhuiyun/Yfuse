package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Contrast-only overlay for Home and Library hero reels.
 *
 * The artwork itself dissolves with [Modifier.fadeIntoPage], so this brush never paints the
 * page colour over the poster. Keeping the lower half transparent prevents the pale fog band
 * while the status/header area still gets a stable dark readability cap.
 */
fun heroReelScrim(): Brush =
    scrim(
        0f to Color.Transparent,
        0.54f to Color.Transparent,
        0.68f to HeroInk.copy(alpha = 0.10f),
        1f to HeroInk.copy(alpha = 0.42f),
    )
