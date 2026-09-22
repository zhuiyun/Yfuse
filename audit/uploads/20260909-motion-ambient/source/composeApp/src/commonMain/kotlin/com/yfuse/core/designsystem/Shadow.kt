package com.yfuse.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A CSS `box-shadow`, drawn behind the content.
 *
 * `Modifier.shadow` only exposes a single elevation and cannot express the
 * offset / blur / spread triples the spec annotates, so the drop shadow is
 * rasterised directly.
 */
expect fun Modifier.cssShadow(
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
    blur: Dp = 0.dp,
    spread: Dp = 0.dp,
    color: Color,
    shape: Shape,
): Modifier
