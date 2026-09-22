package com.yfuse.feature.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.yfuse.core2.android.AndroidAssSubtitleRenderer
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class SubtitleBitmapNode(
    val payload: YSubtitlePayload.BitmapArgb,
    val image: ImageBitmap,
)

/** Published atomically after conversion; Compose never observes a partly built frame. */
@Composable
internal fun rememberSubtitleBitmapFrames(
    renderer: AndroidAssSubtitleRenderer,
    authored: List<YSubtitlePayload.BitmapArgb>,
    hasAss: Boolean,
): State<List<SubtitleBitmapNode>> {
    val frames = remember(renderer) { mutableStateOf(emptyList<SubtitleBitmapNode>()) }
    LaunchedEffect(renderer, authored, hasAss) {
        suspend fun publish(ass: List<YSubtitlePayload.BitmapArgb>) {
            val previous = frames.value
            frames.value =
                withContext(Dispatchers.Default) {
                    (authored + ass).map { payload ->
                        previous.firstOrNull { it.payload === payload } ?: SubtitleBitmapNode(
                            payload,
                            Bitmap
                                .createBitmap(
                                    payload.pixels,
                                    payload.width,
                                    payload.height,
                                    Bitmap.Config.ARGB_8888,
                                ).asImageBitmap(),
                        )
                    }
                }
        }
        if (hasAss) renderer.bitmaps.collectLatest { publish(it) } else publish(emptyList())
    }
    return frames
}

/** Pixel updates invalidate drawing; only changed display-set bounds invalidate dual-track measurement. */
@Composable
internal fun SubtitleBitmapCanvas(
    frames: State<List<SubtitleBitmapNode>>,
    scale: Float,
    dual: Boolean,
    viewport: DpSize,
    position: Float,
    brightness: Float,
    appearance: SubtitleAppearance,
) {
    val bitmapScale = scale.coerceIn(0.6f, 1.8f)
    val bounds =
        remember(frames, bitmapScale) {
            derivedStateOf { core2SubtitleBitmapBounds(frames.value.map { it.payload }, bitmapScale) }
        }
    Layout(content = {
        Canvas(Modifier.fillMaxSize()) {
            val alpha = brightness.coerceIn(0.35f, 1f)
            val background = Color(appearance.backgroundColorArgb.toULong())
            frames.value.forEach { node ->
                val payload = node.payload
                val destination =
                    subtitleBitmapDestination(
                        payload,
                        bitmapScale,
                        IntSize(viewport.width.roundToPx(), viewport.height.roundToPx()),
                        if (dual) -bounds.value.first else position.coerceIn(0.60f, 0.96f) - DEFAULT_SUBTITLE_POSITION,
                    )
                if (background.alpha > 0f) {
                    drawRect(
                        background.copy(alpha = background.alpha * alpha),
                        Offset(destination.left.toFloat(), destination.top.toFloat()),
                        Size(destination.width.toFloat(), destination.height.toFloat()),
                    )
                }
                drawImage(
                    node.image,
                    dstOffset = IntOffset(destination.left, destination.top),
                    dstSize = IntSize(destination.width, destination.height),
                    alpha = alpha,
                )
            }
        }
    }) { measurables, constraints ->
        val width = constraints.constrainWidth(viewport.width.roundToPx())
        val height =
            if (dual) {
                (viewport.height.toPx() * (bounds.value.second - bounds.value.first)).roundToInt().coerceAtLeast(0)
            } else {
                constraints.maxHeight
            }
        val measuredHeight = constraints.constrainHeight(height)
        val child = measurables.single().measure(Constraints.fixed(width, measuredHeight))
        layout(width, measuredHeight) { child.place(0, 0) }
    }
}

/** Authored coordinates retain their centre while scale and whole-track placement change. */
internal fun subtitleBitmapDestination(
    payload: YSubtitlePayload.BitmapArgb,
    scale: Float,
    viewport: IntSize,
    verticalShift: Float,
): IntRect {
    val boundedScale = scale.coerceIn(0.6f, 1.8f)
    val width = payload.width * boundedScale
    val height = payload.height * boundedScale
    val x = (payload.x - (width - payload.width) / 2f) / payload.canvasWidth
    val y = (payload.y - (height - payload.height) / 2f) / payload.canvasHeight + verticalShift
    val left = (viewport.width * x).roundToInt()
    val top = (viewport.height * y).roundToInt()
    return IntRect(
        left,
        top,
        left + (viewport.width * width / payload.canvasWidth).roundToInt().coerceAtLeast(1),
        top + (viewport.height * height / payload.canvasHeight).roundToInt().coerceAtLeast(1),
    )
}
