package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

internal val LocalDialogBackdrop = staticCompositionLocalOf<BackdropState?> { null }
internal val LocalMutedGlass = staticCompositionLocalOf { false }

/**
 * Dialogs occupy another window, so only the page (never the dialog itself) is captured.
 *
 * The capture is the whole page, every frame — so it only runs while a dialog is actually
 * up. [OverlayVisibility] is the theme's; with nothing open the page draws straight to the
 * window and the layer keeps whatever it last held, which the next dialog overwrites on its
 * first frame.
 */
@Composable
internal fun DialogBackdropHost(content: @Composable () -> Unit) {
    val backdrop = rememberBackdropState()
    val motionHost = remember { DialogMotionHost() }
    val overlays = LocalOverlayVisibility.current
    CompositionLocalProvider(LocalDialogBackdrop provides backdrop, LocalDialogMotionHost provides motionHost) {
        Box(
            Modifier
                .fillMaxSize()
                .trackDialogOrigin(motionHost)
                .backdropSource(backdrop, record = { overlays == null || overlays.any }),
        ) { content() }
    }
}

/** Low-reflection liquid glass; refraction and diffusion affect the page, never the text. */
@Composable
fun Modifier.mutedGlassPanel(
    shape: Shape = AppShapes.sheet,
    samplePage: Boolean = true,
    dark: Boolean = LocalPalette.current.isDark,
): Modifier {
    // In-window player panels must never sample a root layer that contains themselves.
    val backdrop = LocalDialogBackdrop.current.takeIf { samplePage }
    val opaque = LocalAccessibilityOptions.current.reduceTransparency || backdrop?.active != true
    val material = LocalGlassMaterials.current.forTheme(dark).normalized(dark)
    // Without a backdrop (including reduced transparency), light text tokens need their
    // light surface. Making the translucent grey tint opaque leaves captions hard to read.
    val tint =
        when {
            dark -> material.tint(true)
            opaque && material.ink != GlassInk.Light && material.tintRgb == null -> LightPalette.background
            else -> material.tint(false)
        }
    val body = if (opaque) tint.copy(alpha = 1f) else tint
    val rimStart = material.rim * if (dark) 0.73f else 1f
    val rimSide = material.rim * (0.13f + material.prism * 0.87f)
    val rimEnd = material.rim * (0.53f + material.prism * 0.47f)
    val rim =
        Brush.linearGradient(
            0f to lerp(Color.White, Color(0xFFBCF9EB), material.prism).copy(alpha = rimStart),
            0.38f to Color.White.copy(alpha = material.rim * 0.20f),
            0.70f to lerp(Color.White, Color(0xFFDBA9FF), material.prism).copy(alpha = rimSide),
            1f to lerp(Color.White, Color(0xFFFFD9BC), material.prism).copy(alpha = rimEnd),
        )
    return this
        .then(
            if (!opaque && backdrop != null) {
                Modifier.backdropBlur(
                    backdrop,
                    shape,
                    radius = material.blur.dp,
                    saturation = material.saturation,
                    refraction =
                        if (material.refraction > 0f || material.fluted > 0f) {
                            BackdropRefraction(
                                edgeX = 0.08f,
                                edgeY = 0.10f,
                                strength = material.refraction.dp,
                                fluted = material.fluted,
                                fluteWidth = material.fluteWidth.dp,
                            )
                        } else {
                            null
                        },
                )
            } else {
                Modifier
            },
        ).clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(body, body.copy(alpha = (body.alpha + 0.04f).coerceAtMost(1f))),
            ),
        ).background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (opaque) 0f else 0.035f),
                0.30f to Color.Transparent,
                1f to Color.Black.copy(alpha = if (opaque) 0f else 0.025f),
            ),
        ).glassMaterialFinish(material, dark, opaque)
        .border(material.rimWidth.dp, rim, shape)
}

/** Nested controls inherit the modal's quiet material, including callers with white fills. */
@Composable
internal fun Modifier.mutedGlassControl(
    shape: Shape,
    fill: Color,
    border: Color?,
): Modifier {
    val palette = LocalPalette.current
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    val body =
        if (reduceTransparency) palette.mutedControl else fill.copy(alpha = fill.alpha.coerceAtMost(0.10f))
    return clip(shape)
        .background(body)
        .then(
            if (border != null) {
                Modifier.border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            } else {
                Modifier
            },
        )
}
