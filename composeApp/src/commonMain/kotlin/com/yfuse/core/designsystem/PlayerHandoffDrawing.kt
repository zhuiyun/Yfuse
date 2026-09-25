package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The artwork a transition carries, as a [Painter] it can draw anywhere on its own clock.
 *
 * Tries the same candidates, in the same order, as the page's own `FallbackImage`, so the picture
 * that travels is the one that was on screen. [small] asks for a thumbnail a few dozen pixels wide:
 * drawn across the screen it is already a soft field of the picture's colours, with no blur pass.
 */
@Composable
internal fun rememberArtworkPainter(
    urls: List<String>,
    small: Boolean = false,
): Painter? {
    val candidates = remember(urls) { urls.filter(String::isNotBlank).distinct() }
    var index by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(index) ?: return null
    val context = LocalPlatformContext.current
    val request =
        remember(context, url, small) {
            ImageRequest
                .Builder(context)
                .data(url)
                // The page decoded this picture moments ago: it is drawn from the memory cache
                // while the larger copy is still on its way, rather than the stand-in starting blank.
                .placeholderMemoryCacheKey(MemoryCache.Key(url))
                .size(if (small) FIELD_WIDTH else ARTWORK_WIDTH, if (small) FIELD_HEIGHT else ARTWORK_HEIGHT)
                .build()
        }
    return rememberAsyncImagePainter(
        model = request,
        onError = { if (index < candidates.lastIndex) index++ },
    )
}

private const val ARTWORK_WIDTH = 1920
private const val ARTWORK_HEIGHT = 1080
private const val FIELD_WIDTH = 48
private const val FIELD_HEIGHT = 27

/**
 * One transition's blurs, one per half pixel of radius (the step [ArtworkBlurCache] uses).
 *
 * The sets set a layer's blur on every frame they move; built fresh each time, a radius easing
 * through a few dozen distinct values became a new effect per frame, and a constant one — the
 * 虚焦 field's — one per frame for the length of the transition.
 */
internal class HandoffBlurs(
    private val edges: TileMode,
) {
    private val effects = HashMap<Int, RenderEffect>()

    fun of(radiusPx: Float): RenderEffect? {
        val step = artworkBlurStep(radiusPx)
        if (step == 0) return null
        return effects.getOrPut(step) {
            BlurEffect(step * ARTWORK_BLUR_STEP_PX, step * ARTWORK_BLUR_STEP_PX, edges)
        }
    }
}

/** Saturation filters, one per hundredth of saturation; see [HandoffBlurs]. */
internal class HandoffSaturations {
    private val filters = HashMap<Int, ColorFilter>()

    fun of(amount: Float): ColorFilter {
        val step = (amount * SATURATION_STEPS).roundToInt()
        return filters.getOrPut(step) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(step / SATURATION_STEPS) })
        }
    }
}

private const val SATURATION_STEPS = 100f

/** A box's own frame: origin at its centre, turned with it. */
internal inline fun DrawScope.inBox(
    box: HandoffBox,
    block: DrawScope.() -> Unit,
) {
    withTransform({
        translate(box.center.x, box.center.y)
        rotate(box.rotation, pivot = Offset.Zero)
    }) { block() }
}

internal fun HandoffBox.localRoundRect(): RoundRect =
    RoundRect(-width / 2f, -height / 2f, width / 2f, height / 2f, CornerRadius(corner.coerceAtLeast(0f)))

internal fun HandoffBox.localPath(): Path = Path().apply { addRoundRect(localRoundRect()) }

/**
 * Draws [painter] cropped to fill [box] the way `ContentScale.Crop` would, [zoom] times closer,
 * clipped to the box's rounded outline. An image not yet decoded is assumed to be 16:9.
 */
internal fun DrawScope.drawArtwork(
    painter: Painter?,
    box: HandoffBox,
    alpha: Float = 1f,
    zoom: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    if (painter == null || alpha <= 0f || box.width <= 0f || box.height <= 0f) return
    val intrinsic =
        painter.intrinsicSize.takeIf { it.isSpecified && it.width > 0f && it.height > 0f } ?: Size(16f, 9f)
    val scale = max(box.width / intrinsic.width, box.height / intrinsic.height) * zoom
    val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
    inBox(box) {
        clipPath(box.localPath()) {
            translate(-drawn.width / 2f, -drawn.height / 2f) {
                with(painter) { draw(drawn, alpha.coerceIn(0f, 1f), colorFilter) }
            }
        }
    }
}

/** Draws a picture that stays still in the window while the [lens] that shows it moves and turns. */
internal fun DrawScope.drawThroughLens(
    painter: Painter?,
    picture: HandoffBox,
    lens: HandoffBox,
    zoom: Float,
    alpha: Float = 1f,
) {
    if (painter == null || alpha <= 0f) return
    inBox(lens) {
        clipPath(lens.localPath()) {
            // Undo the lens's own turn and offset, so the picture keeps the window's frame.
            withTransform({
                rotate(-lens.rotation, pivot = Offset.Zero)
                translate(-lens.center.x, -lens.center.y)
            }) {
                drawArtwork(painter, picture.scaled(zoom), alpha)
            }
        }
    }
}

/** The glass rim the 玻璃舱 set shares with the app's liquid glass: a white hairline, blue above, amber below. */
internal fun DrawScope.drawGlassRim(
    box: HandoffBox,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val a = alpha.coerceIn(0f, 1f)
    val hairline = 1.5f * density
    inBox(box) {
        val outline = box.localRoundRect()
        clipPath(box.localPath()) {
            val band = (14f * density).coerceAtMost(box.height / 2f)
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0xFF78B0FF).copy(alpha = 0.62f * a),
                    1f to Color.Transparent,
                    startY = outline.top,
                    endY = outline.top + band,
                ),
                topLeft = Offset(outline.left, outline.top),
                size = Size(outline.width, band),
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xFFFFA054).copy(alpha = 0.58f * a),
                    startY = outline.bottom - band,
                    endY = outline.bottom,
                ),
                topLeft = Offset(outline.left, outline.bottom - band),
                size = Size(outline.width, band),
            )
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.26f * a),
                    0.24f to Color.Transparent,
                    start = Offset(outline.left, outline.top),
                    end = Offset(outline.left + outline.width * 0.5f, outline.bottom),
                ),
                topLeft = Offset(outline.left, outline.top),
                size = Size(outline.width, outline.height),
            )
        }
        drawPath(
            box.localPath(),
            color = Color.White.copy(alpha = 0.78f * a),
            style = Stroke(width = hairline),
        )
    }
}

/** A lamp: a white core in a warm halo, the point of light the 开幕 set shrinks everything into. */
internal fun DrawScope.drawLamp(
    center: Offset,
    alpha: Float,
    scale: Float = 1f,
) {
    if (alpha <= 0f) return
    val radius = 24f * density * scale
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = alpha),
            0.16f to Color(0xFFFFF6E4).copy(alpha = alpha),
            0.36f to Color(0xFFFFCE8C).copy(alpha = 0.62f * alpha),
            0.7f to Color(0xFFFFAA64).copy(alpha = 0.16f * alpha),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
