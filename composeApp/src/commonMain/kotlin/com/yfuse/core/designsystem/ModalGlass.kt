package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal val LocalDialogBackdrop = staticCompositionLocalOf<BackdropState?> { null }
internal val LocalMutedGlass = staticCompositionLocalOf { false }

/** Dialogs occupy another window, so only the page (never the dialog itself) is captured. */
@Composable
internal fun DialogBackdropHost(content: @Composable () -> Unit) {
    val backdrop = rememberBackdropState()
    CompositionLocalProvider(LocalDialogBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) { content() }
    }
}

/** Low-reflection grey frost; the blur is behind the fill and never touches foreground text. */
@Composable
fun Modifier.mutedGlassPanel(
    shape: Shape = GlassShapes.sheet,
    samplePage: Boolean = true,
    dark: Boolean = LocalPalette.current.isDark,
): Modifier {
    // In-window player panels must never sample a root layer that contains themselves.
    val backdrop = LocalDialogBackdrop.current.takeIf { samplePage }
    val opaque = LocalAccessibilityOptions.current.reduceTransparency || backdrop?.active != true
    val tint = if (dark) Color(0xFF242831) else Color(0xFF9DA3AD)
    val body = tint.copy(alpha = if (opaque) 1f else if (dark) 0.78f else 0.58f)
    val edge = Color.White.copy(alpha = if (dark) 0.12f else 0.20f)
    return this
        .then(if (!opaque && backdrop != null) Modifier.backdropBlur(backdrop, shape, 28.dp, 1.05f) else Modifier)
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(body, body.copy(alpha = (body.alpha + 0.04f).coerceAtMost(1f))),
            ),
        ).border(0.5.dp, edge, shape)
}

/** Nested controls inherit the modal's quiet material, including callers with white fills. */
@Composable
internal fun Modifier.mutedGlassControl(shape: Shape, fill: Color, border: Color?): Modifier {
    val palette = LocalPalette.current
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    val neutral = if (palette.isDark) Color(0xFF353B45) else Color(0xFFBEC3CB)
    val body =
        if (reduceTransparency) neutral else fill.copy(alpha = fill.alpha.coerceAtMost(0.10f))
    return clip(shape)
        .background(body)
        .then(
            if (border != null) {
                Modifier.border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            } else Modifier,
        )
}
