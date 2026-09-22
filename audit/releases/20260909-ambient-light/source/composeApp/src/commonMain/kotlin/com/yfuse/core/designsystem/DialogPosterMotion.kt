package com.yfuse.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal class DialogPosterSource(
    val layer: GraphicsLayer,
) {
    var bounds = Rect.Zero
    var active = true
    var recorded = false
}

/** Retain drawing commands only for this selected style; no bitmap readback or image reload. */
@Composable
internal fun Modifier.dialogPosterSource(): Modifier {
    if (LocalDialogAnimation.current != DialogAnimation.PosterMorph ||
        LocalAccessibilityOptions.current.reduceMotion
    ) {
        return this
    }
    val host = LocalDialogMotionHost.current
    val layer = rememberGraphicsLayer()
    val source = remember(layer) { DialogPosterSource(layer) }
    DisposableEffect(source, host) {
        onDispose {
            source.active = false
            if (host.poster === source) host.poster = null
        }
    }
    return onGloballyPositioned {
        val origin = it.positionInWindow()
        source.bounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
    }.pointerInput(host, source) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            host.poster = source
        }
    }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        source.recorded = true
        drawLayer(layer)
    }
}

internal fun posterDialogBounds(
    source: Rect,
    target: Rect,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        source.left + (target.left - source.left) * p,
        source.top + (target.top - source.top) * p,
        source.right + (target.right - source.right) * p,
        source.bottom + (target.bottom - source.bottom) * p,
    )
}

internal fun ContentDrawScope.drawPosterDialog(
    source: DialogPosterSource?,
    overlay: GraphicsLayer,
    panelOrigin: Offset,
    anchor: Offset?,
    progress: Float,
    cache: DialogDrawCache,
): Boolean {
    val valid = source?.takeIf { it.active && it.recorded && it.bounds.width > 0f && it.bounds.height > 0f }
    val origin = anchor?.minus(panelOrigin) ?: center
    val start = valid?.bounds?.translate(-panelOrigin) ?: Rect(origin, origin)
    val bounds = posterDialogBounds(start, Rect(Offset.Zero, size), progress)
    val roundness = 16.dp.toPx() * (1f - progress)
    val mask =
        cache.aperture.apply {
            rewind()
            addRoundRect(RoundRect(bounds, CornerRadius(roundness, roundness)))
        }
    clipPath(mask) {
        this@drawPosterDialog.drawContent()
        if (valid != null && progress < 0.65f) {
            // A separate layer owns opacity: changing the original layer would also fade
            // the poster behind the dialog. The source remains live and keeps its aspect.
            if (cache.posterSourceLayer !== valid.layer ||
                cache.posterOverlayLayer !== overlay ||
                cache.posterSourceSize != valid.layer.size
            ) {
                // The display list retains the live child layer. Opacity/transform updates do
                // not need to record the same drawLayer command again on every animation frame.
                overlay.record(size = valid.layer.size) { drawLayer(valid.layer) }
                cache.posterSourceLayer = valid.layer
                cache.posterOverlayLayer = overlay
                cache.posterSourceSize = valid.layer.size
            }
            overlay.alpha = 1f - (progress / 0.65f).coerceIn(0f, 1f)
            val scale = max(bounds.width / valid.bounds.width, bounds.height / valid.bounds.height)
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                withTransform({
                    translate(
                        bounds.center.x - valid.bounds.width * scale / 2f,
                        bounds.center.y - valid.bounds.height * scale / 2f,
                    )
                    scale(scale, scale, Offset.Zero)
                }) { drawLayer(overlay) }
            }
        }
    }
    return true
}
