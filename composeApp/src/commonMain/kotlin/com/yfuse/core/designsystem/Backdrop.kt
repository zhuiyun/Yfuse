package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether this platform can blur what is behind a surface.
 *
 * On Android the blur is `RenderEffect`, which arrived in API 31. Below that the capture
 * would cost a full-screen layer every frame and produce nothing, so the whole mechanism
 * turns itself off and the glass surfaces keep the raised-alpha treatment they were
 * already using.
 */
expect val supportsBackdropBlur: Boolean

/** 设计说明文档 §8.1 — `blur(20-22px)`. */
val BackdropBlurRadius: Dp = 20.dp

/**
 * 设计说明文档 §8.1 — the `saturate(180%)` that goes with the blur, and the half that was
 * missing.
 *
 * Blur alone averages a picture towards its mean, and the mean of almost any frame is grey.
 * That is why an unsaturated blur under a translucent fill reads as dirty glass rather than
 * as the colour of what is behind it. Pushing saturation back up is what makes the material
 * pick up a poster's colour, and it is the whole reason Apple's materials feel like they are
 * *made of* the content underneath instead of merely covering it.
 */
private const val BACKDROP_SATURATION = 1.8f

/**
 * The page content, captured so the floating chrome above it can blur what it covers.
 *
 * §8.1 specifies the tab bar and mini player as `blur(20-22px) saturate(180%)` over a
 * 0.74–0.82 fill, and [Palette.glassStrong] exists only because that blur was missing:
 * Compose Multiplatform has no backdrop filter, so the alpha was raised until posters
 * stopped reading through the bar. This is the blur, so the fill can go back to being a
 * fill.
 *
 * One [BackdropState] serves one source and any number of surfaces above it. Surfaces
 * sample by position, so they may be anywhere in the tree as long as they are drawn after
 * the source — which siblings later in a `Box` are.
 */
@Stable
class BackdropState internal constructor(
    internal val layer: GraphicsLayer,
    internal val enabled: Boolean,
) {
    /** Where the captured content sits in root coordinates. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** False until the source has drawn once; there is nothing to sample before that. */
    internal var hasContent = false

    /**
     * Bumped every time the source re-records.
     *
     * Surfaces read it while drawing, which is the whole point: a surface does not depend
     * on the scroll position of the content underneath it, so without something to observe
     * its blurred copy would be captured once and then sit frozen while the page scrolled
     * beneath it.
     */
    private var revision by mutableIntStateOf(0)

    /**
     * Counted outside the snapshot so [recorded] can raise [revision] without reading it.
     *
     * `revision++` would read the state it writes, inside the source's own draw — which
     * subscribes the source to a value the source itself changes, and that is a redraw
     * loop that never settles.
     */
    private var records = 0

    internal fun recorded() {
        hasContent = true
        records++
        revision = records
    }

    /**
     * The captured content, and the subscription that keeps it live.
     *
     * Reading [revision] here is not incidental — it is how the calling draw scope comes to
     * depend on the source, so returning the layer without it would give a surface one
     * frozen frame and nothing after.
     */
    internal fun sample(): GraphicsLayer {
        @Suppress("UNUSED_VARIABLE")
        val subscription = revision
        return layer
    }
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    val reduceTransparency = LocalAccessibilityOptions.current.reduceTransparency
    // 降低透明度 asks for opaque surfaces; blurring what cannot be seen through is work
    // with nothing to show for it.
    val enabled = supportsBackdropBlur && !reduceTransparency
    return remember(layer, enabled) { BackdropState(layer, enabled) }
}

/**
 * Marks the content whose pixels the surfaces above it sample.
 *
 * Apply to the page content only. Anything inside this is part of the backdrop, so the
 * floating chrome must be a sibling drawn after it, not a child — otherwise the bar would
 * be blurring a picture of itself.
 */
fun Modifier.backdropSource(state: BackdropState): Modifier {
    if (!state.enabled) return this
    return this
        .onGloballyPositioned { state.origin = it.positionInRoot() }
        .drawWithContent {
            state.layer.record { this@drawWithContent.drawContent() }
            state.recorded()
            drawLayer(state.layer)
        }
}

/**
 * Blurs whatever [state] captured behind this surface, clipped to [shape].
 *
 * Chain it *before* the fill — `shadow(…).backdropBlur(…).overlayGlass(…)` — so the
 * translucent fill sits on top of the blur rather than under it. The blur goes into a
 * layer of its own rather than onto this node, because a `renderEffect` here would take
 * the surface's own label and icons with it.
 */
@Composable
fun Modifier.backdropBlur(
    state: BackdropState,
    shape: Shape,
    radius: Dp = BackdropBlurRadius,
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    // The saturation is a property of the material, not of this surface, so it is built once
    // rather than per frame.
    val vibrancy = remember {
        Paint().apply {
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(BACKDROP_SATURATION) },
            )
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (!state.enabled) return this
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (!state.hasContent) return@drawBehind
            val source = state.sample()
            blurLayer.renderEffect = BlurEffect(radiusPx, radiusPx)
            blurLayer.record {
                translate(
                    left = state.origin.x - origin.x,
                    top = state.origin.y - origin.y,
                ) {
                    drawLayer(source)
                }
            }
            // Saturation is applied as the blurred copy is composited down, so it lifts the
            // backdrop and leaves the fill, hairline and content above it alone.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), vibrancy)
                drawLayer(blurLayer)
                canvas.restore()
            }
        }
}
