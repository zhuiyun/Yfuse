package com.yfuse.feature.detail

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.LocalAccentColors

/** A solid edge stays visible even when a glass surface deliberately remains translucent. */
internal val DetailSelectionBorderWidth = 2.5.dp

@Composable
internal fun detailSelectionHighlight(accent: Color = LocalAccentColors.current.accent): Color {
    // A near-white ice-blue highlight keeps its luminosity on both light and dark glass.
    // Add only a trace of the artwork accent so a dark sampled color cannot dim the edge.
    return lerp(Color(0xFFE2F1FF), accent.copy(alpha = 1f), 0.06f)
}

@Composable
internal fun Modifier.detailSelectionOutline(
    selected: Boolean,
    accent: Color = LocalAccentColors.current.accent,
    shape: Shape = AppShapes.card,
): Modifier = if (selected) border(DetailSelectionBorderWidth, detailSelectionHighlight(accent), shape) else this
