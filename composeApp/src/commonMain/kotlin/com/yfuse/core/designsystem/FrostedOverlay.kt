package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The thick frosted panel: `blur(46px) saturate(1.6)` of the page beneath, under a fill that
 * is mostly light, inside a bright rim.
 *
 * It is deliberately denser and softer than the navigation bar's material. The bar is a thin
 * sliver over content that keeps moving, so it stays clear enough to read through; a panel
 * of actions has to be read *on*, so what shows through is only the colour and light of the
 * page, never its edges.
 */
private val FrostedPanelBlurRadius = 46.dp
private const val FROSTED_PANEL_SATURATION = 1.6f

/** `0 24px 60px rgba(10,14,22,.28)` — the drop a floating slab of glass casts on the page. */
private val FrostedPanelShadow = CssShadow(0.dp, 24.dp, 60.dp, 0.dp, Color(0xFF0A0E16).copy(alpha = 0.28f))

private val FrostedRimWidth = 1.5.dp
private val FrostedInnerGlowWidth = 14.dp

/** The panel arrives from slightly smaller and a little lower than where it settles. */
private const val FROSTED_PANEL_SCALE_FROM = 0.94f
private val FrostedPanelMotionOffset = 16.dp

/**
 * How dark the page goes behind the panel. Light enough that the page still reads as the
 * page — the blur is what separates the two layers, not the scrim.
 */
private const val FROSTED_SCRIM_LIGHT = 0.22f
private const val FROSTED_SCRIM_DARK = 0.34f

/**
 * A floating frosted-glass panel drawn inside the page.
 *
 * [GlassDialog] is a separate window, and a window cannot see the pixels it floats over, so
 * everything that wants the page to blur underneath it — the season list, this — has to be a
 * sibling drawn after the page content that [backdrop] captures. Place it last in the same
 * `Box` as the content marked with [backdropSource].
 *
 * It stays composed until the exit animation finishes, so callers keep supplying [content]
 * for the last shown item while [open] is false. A tap on the scrim or the back key calls
 * [onDismiss]; the panel consumes its own taps.
 */
@Composable
fun FrostedOverlay(
    open: Boolean,
    backdrop: BackdropState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter,
    shape: Shape = GlassShapes.sheet,
    windowPadding: PaddingValues = PaddingValues(start = 22.dp, top = 52.dp, end = 22.dp, bottom = 24.dp),
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, top = 18.dp, end = 8.dp, bottom = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open, reduceMotion) {
        if (open) {
            progress.animateTo(1f, Motion.settle(reduceMotion))
        } else {
            progress.animateTo(
                0f,
                if (reduceMotion) snap() else tween(Motion.QUICK, easing = Motion.Curve),
            )
        }
    }
    PlatformBackHandler(enabled = open, onBack = onDismiss)
    ReportOverlayVisible(enabled = open)
    // Composed while opening, open, or still animating shut.
    if (!open && progress.value <= 0f) return

    val motionOffset = with(LocalDensity.current) { FrostedPanelMotionOffset.toPx() }
    val scrimAlpha = if (palette.isDark) FROSTED_SCRIM_DARK else FROSTED_SCRIM_LIGHT
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress.value }
            .then(
                if (open) {
                    // Everything outside the panel closes it; the panel consumes its own taps.
                    Modifier.pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                } else {
                    Modifier
                },
            ),
        contentAlignment = alignment,
    ) {
        Box(Modifier.fillMaxSize().background(ScrimColor.copy(alpha = scrimAlpha)))
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(windowPadding)
                .widthIn(max = OverlayMaxWidth)
                .fillMaxWidth()
                .graphicsLayer {
                    val entered = progress.value
                    val scale = FROSTED_PANEL_SCALE_FROM + (1f - FROSTED_PANEL_SCALE_FROM) * entered
                    scaleX = scale
                    scaleY = scale
                    translationY = motionOffset * (1f - entered)
                }.shadow(FrostedPanelShadow, shape)
                .backdropBlur(
                    backdrop,
                    shape,
                    radius = FrostedPanelBlurRadius,
                    saturation = FROSTED_PANEL_SATURATION,
                ).frostedPane(shape, blurred = backdrop.active)
                .pointerInput(Unit) { detectTapGestures { } }
                .then(modifier)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * The fill, highlight and rim of a frosted panel, drawn over whatever [backdropBlur] put
 * beneath it.
 *
 * With the page blurred behind it the fill can be a fill — half white, so the page's colour
 * comes through as a wash. Where nothing is blurred (older platforms, 降低透明度) the same
 * body goes near-opaque, because rows of text read straight through a half-white plate.
 */
@Composable
fun Modifier.frostedPane(
    shape: Shape,
    blurred: Boolean,
): Modifier {
    val palette = LocalPalette.current
    val dark = palette.isDark
    val body =
        when {
            dark && blurred ->
                Brush.verticalGradient(
                    listOf(Color(0xFF1C2434).copy(alpha = 0.44f), Color(0xFF141B29).copy(alpha = 0.36f)),
                )
            dark ->
                Brush.verticalGradient(
                    listOf(Color(0xFF1C2434).copy(alpha = 0.96f), Color(0xFF141B29).copy(alpha = 0.96f)),
                )
            blurred ->
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.52f), Color.White.copy(alpha = 0.38f)),
                )
            else ->
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.94f), Color.White.copy(alpha = 0.90f)),
                )
        }
    // `radial-gradient(120% 60% at 50% -10%, …)` — the light catches the top of the slab.
    val highlight =
        cssRadialGradient(
            centerX = 0.5f,
            centerY = -0.1f,
            endStop = 0.6f,
            inner = Color.White.copy(alpha = if (dark) 0.10f else 0.35f),
        )
    val rim = Color.White.copy(alpha = if (dark) 0.22f else 0.85f)
    val innerGlow = Color.White.copy(alpha = if (dark) 0.06f else 0.22f)
    return this
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            // Half of each stroke falls outside the clip, so the visible width is half the
            // stroke's — the rim reads at its nominal width and the glow at half of its.
            val rimStroke = Stroke(FrostedRimWidth.toPx() * 2f)
            val glowStroke = Stroke(FrostedInnerGlowWidth.toPx() * 2f)
            onDrawBehind {
                drawOutline(outline, brush = body)
                drawOutline(outline, brush = highlight)
                drawOutline(outline, color = innerGlow, style = glowStroke)
                drawOutline(outline, color = rim, style = rimStroke)
            }
        }
}
