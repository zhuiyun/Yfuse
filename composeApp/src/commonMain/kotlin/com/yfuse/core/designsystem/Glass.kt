package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Corner radii used across the glass UI. */
object GlassShapes {
    val card = RoundedCornerShape(20.dp)
    val panel = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
    val poster = RoundedCornerShape(14.dp)
    val chip = RoundedCornerShape(12.dp)
}

/**
 * Translucent "glass" fill plus a hairline border. Compose has no backdrop
 * blur below API 31, so the effect is built from layered translucency, which
 * reads the same over artwork and gradients.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    strong: Boolean = false,
): Modifier {
    val tokens = LocalGlass.current
    return this
        .clip(shape)
        .background(if (strong) tokens.surfaceStrong else tokens.surface)
        .border(1.dp, if (strong) tokens.borderStrong else tokens.border, shape)
}

/** A glass panel with content. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, strong), content = content)
}

/** Page backdrop: a soft vertical wash behind every screen. */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tokens = LocalGlass.current
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.backdropTop, tokens.backdropBottom))),
        content = content,
    )
}
