package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LiquidBody
import com.yfuse.core.designsystem.LiquidClock
import com.yfuse.core.designsystem.LiquidMotion
import com.yfuse.core.designsystem.LiquidMotion.easeIn
import com.yfuse.core.designsystem.LiquidMotion.inOut
import com.yfuse.core.designsystem.LiquidMotion.lerp
import com.yfuse.core.designsystem.LiquidMotion.smooth
import com.yfuse.core.designsystem.LiquidMotion.spring
import com.yfuse.core.designsystem.LiquidPaths
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.liquidMotionEnabled
import com.yfuse.core.designsystem.liquidOutline
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * 播放器片尾 · 液态分离 — one circle becomes three.
 *
 * When an item ends without rolling on, a white drop appears where the paused key would be. It
 * throws off a drop to each side: the left stays solid and becomes 下一集, the middle and right
 * empty from the centre outwards into the hairline rings of 重播 and 返回, and each glyph comes into
 * focus once its key has formed. With nothing to play next, only 返回 splits off to the right while
 * 重播 stays solid and steps left, so the pair stays centred. 重播 runs it backwards: the rings
 * fill, the side drops are drawn back in, and the last one shrinks away as the item starts again.
 *
 * Geometry is in dp along the row's axis, measured from the row's centre.
 */

/** Where the keys of the ending rest, in dp from the row's centre. */
internal data class PlayerEndMetrics(
    /** Whether 下一集 leads the row. */
    val hasNext: Boolean,
    /** Ring radius — half of [CenterKeySize]. */
    val radius: Float,
    /** From one key's centre to the next: the ring, its touch slack on both sides, and the gap. */
    val step: Float,
) {
    /** The keys' centres, left to right. */
    val centers: List<Float>
        get() = if (hasNext) listOf(-step, 0f, step) else listOf(-step / 2f, step / 2f)

    /** When the last neck breaks, [PlayerEndLiquidFrame] time after the split starts. */
    val pinchMs: Float get() = if (hasNext) PLAYER_END_PINCH_MS else PLAYER_END_PINCH_SINGLE_MS
}

internal enum class PlayerEndMove(
    val durationMs: Int,
) {
    /** The drop appearing, then splitting. */
    Split(PLAYER_END_SPLIT_LEAD_MS + PLAYER_END_SPLIT_MS),

    /** Splitting straight from the paused key's disc, which was already there. */
    SplitFromPausedKey(PLAYER_END_SPLIT_MS),

    /** 重播: filling, flowing back and shrinking away. */
    Merge(PLAYER_END_MERGE_MS),
}

/** One drop of the ending. */
internal class PlayerEndDrop(
    val center: Float,
    val radius: Float,
    /** k with the drops before it. */
    val blend: Float,
    /** How empty it is: 0 solid, 1 only its ring. */
    val hollow: Float,
    /** Its glyph in focus. */
    val glyph: Float,
)

/** One frame of the ending: the drops, left to right as keys, and the layer's opacity. */
internal class PlayerEndLiquidFrame(
    /** The drops in key order: 下一集 / 重播 / 返回, or 重播 / 返回. */
    val drops: List<PlayerEndDrop>,
    /** Which of [drops] the others split from. */
    val parent: Int,
    val alpha: Float,
) {
    /** The parent first, then the drops it throws off, left to right. */
    fun bodies(): List<LiquidBody> {
        val parentDrop = drops[parent]
        return buildList {
            add(LiquidBody.drop(parentDrop.center, parentDrop.radius))
            drops.forEachIndexed { index, drop ->
                if (index != parent) add(LiquidBody.drop(drop.center, drop.radius, drop.blend))
            }
        }
    }
}

internal fun playerEndFrame(
    move: PlayerEndMove,
    ms: Float,
    metrics: PlayerEndMetrics,
): PlayerEndLiquidFrame =
    when (move) {
        PlayerEndMove.Split -> playerEndSplitFrame(ms - PLAYER_END_SPLIT_LEAD_MS, metrics)
        PlayerEndMove.SplitFromPausedKey -> playerEndSplitFrame(ms, metrics)
        PlayerEndMove.Merge -> playerEndMergeFrame(ms, metrics)
    }

/**
 * [ms] after the split starts (the drop itself appeared [PLAYER_END_SPLIT_LEAD_MS] earlier, on the
 * chrome's own fade and scale): each side drop grows from half a key and is pushed out to its key
 * (ζ 0.66, 2.4 Hz), the right one 40 ms after the left. Once the last neck breaks, the middle and
 * right keys empty over 260 ms, the right one 40 ms later, while every glyph comes into focus.
 */
internal fun playerEndSplitFrame(
    ms: Float,
    metrics: PlayerEndMetrics,
): PlayerEndLiquidFrame {
    val radius = metrics.radius
    val pinch = metrics.pinchMs

    fun side(
        target: Float,
        delay: Float,
    ) = Pair(
        lerp(SIDE_DROP_START * sign(target), target, spring(ms - delay, 0.66f, 2.4f)),
        radius * lerp(0.5f, 1f, smooth(delay, delay + 200f, ms)),
    )
    val glyph = smooth(pinch + 60f, pinch + 260f, ms)
    val drainMiddle = smooth(pinch + 40f, pinch + 300f, ms)
    val drainRight = smooth(pinch + 80f, pinch + 340f, ms)
    return if (metrics.hasNext) {
        val (leftCenter, leftRadius) = side(-metrics.step, 0f)
        val (rightCenter, rightRadius) = side(metrics.step, 40f)
        PlayerEndLiquidFrame(
            drops =
                listOf(
                    PlayerEndDrop(leftCenter, leftRadius, blend(ms, 0f), hollow = 0f, glyph = glyph),
                    PlayerEndDrop(0f, radius, 0f, hollow = drainMiddle, glyph = glyph),
                    PlayerEndDrop(rightCenter, rightRadius, blend(ms, 40f), hollow = drainRight, glyph = glyph),
                ),
            parent = 1,
            alpha = 1f,
        )
    } else {
        val half = metrics.step / 2f
        val (rightCenter, rightRadius) = side(half, 0f)
        PlayerEndLiquidFrame(
            drops =
                listOf(
                    PlayerEndDrop(lerp(0f, -half, spring(ms, 0.7f, 2.4f)), radius, 0f, hollow = 0f, glyph = glyph),
                    PlayerEndDrop(rightCenter, rightRadius, blend(ms, 0f), hollow = drainRight, glyph = glyph),
                ),
            parent = 0,
            alpha = 1f,
        )
    }
}

/**
 * [ms] after 重播: the rings fill from the outside in over 90 ms as the glyphs go, a bridge forms
 * and the side drops shrink as they are drawn back to the middle, then the last drop shrinks to 0.8
 * and fades while the item starts again.
 */
internal fun playerEndMergeFrame(
    ms: Float,
    metrics: PlayerEndMetrics,
): PlayerEndLiquidFrame {
    val radius = metrics.radius
    val fill = 1f - smooth(0f, 90f, ms)
    val travel = inOut((ms - 90f) / 240f)
    val sideRadius = radius * lerp(1f, 0.5f, easeIn((ms - 150f) / 180f))
    val bridge = LiquidMotion.VISCOSITY * smooth(90f, 150f, ms) * (1f - smooth(330f, 390f, ms))
    val glyph = 1f - smooth(0f, 90f, ms)
    val gone = smooth(350f, 490f, ms)
    val parentRadius = radius * lerp(1f, 0.8f, gone)
    return if (metrics.hasNext) {
        PlayerEndLiquidFrame(
            drops =
                listOf(
                    PlayerEndDrop(lerp(-metrics.step, -SIDE_DROP_START, travel), sideRadius, bridge, 0f, glyph),
                    PlayerEndDrop(0f, parentRadius, 0f, hollow = fill, glyph = glyph),
                    PlayerEndDrop(lerp(metrics.step, SIDE_DROP_START, travel), sideRadius, bridge, fill, glyph),
                ),
            parent = 1,
            alpha = 1f - gone,
        )
    } else {
        val half = metrics.step / 2f
        PlayerEndLiquidFrame(
            drops =
                listOf(
                    PlayerEndDrop(lerp(-half, 0f, travel), parentRadius, 0f, hollow = 0f, glyph = glyph),
                    PlayerEndDrop(lerp(half, SIDE_DROP_START, travel), sideRadius, bridge, fill, glyph),
                ),
            parent = 0,
            alpha = 1f - gone,
        )
    }
}

private fun blend(
    ms: Float,
    delay: Float,
): Float = LiquidMotion.VISCOSITY * LiquidMotion.splitViscosity(ms - delay)

private fun sign(value: Float): Float = if (value < 0f) -1f else 1f

/**
 * Paints the ending along [paths]: each piece white, emptied from its key's centre outwards while
 * it hollows — only once it is a key of its own — with its hairline ring coming in as it empties.
 * [originX] and [scale] map the frame's dp, measured from the row's centre, to pixels.
 */
internal fun DrawScope.drawPlayerEnd(
    frame: PlayerEndLiquidFrame,
    paths: LiquidPaths,
    originX: Float,
    scale: Float,
) {
    val pieces = paths.pieces
    val separate = pieces.size == frame.drops.size
    val axisY = size.height / 2f
    val fill = PlayerTokens.playFill.copy(alpha = PlayerTokens.playFill.alpha * frame.alpha)
    pieces.forEach { piece ->
        val drop = frame.drops.firstOrNull { it.center in piece.segment }
        val hollow = if (separate) drop?.hollow ?: 0f else 0f
        val center = Offset(originX + (drop?.center ?: piece.segment.center) * scale, axisY)
        val brush =
            if (hollow <= 0.001f) {
                null
            } else {
                // A hole opening from the centre, its edge softened over a fifth of the radius.
                val inner = hollow * 0.95f
                val outer = minOf(1f, inner + 0.18f)
                val solid = if (hollow >= 0.999f) Color.Transparent else fill
                Brush.radialGradient(
                    0f to Color.Transparent,
                    inner to Color.Transparent,
                    outer to solid,
                    1f to solid,
                    // The piece is drawn from its own corner.
                    center = center - piece.bounds.topLeft,
                    radius = ((drop?.radius ?: 0f) + DRAIN_REACH) * abs(scale),
                )
            }
        translate(piece.bounds.left, piece.bounds.top) {
            if (brush == null) drawPath(piece.path, fill) else drawPath(piece.path, brush)
        }
        if (hollow > 0f && drop != null) {
            drawCircle(
                color = Color.White.copy(alpha = RING_ALPHA * minOf(1f, hollow * 1.6f) * frame.alpha),
                radius = (drop.radius - RING_WIDTH / 2f) * abs(scale),
                center = center,
                style = Stroke(RING_WIDTH * abs(scale)),
            )
        }
    }
}

/** The ending's liquid: which move is playing, over keys laid out as [metrics] says. */
@Stable
internal class PlayerEndLiquid(
    val metrics: PlayerEndMetrics,
    armed: PlayerEndMove?,
) {
    val clock = LiquidClock(armed)

    val paths = LiquidPaths()

    /** Whether the liquid is drawing the keys' discs and rings, in which case they draw only their glyphs. */
    val drawing: Boolean get() = clock.move != null

    /** Whether 重播 is flowing the keys back together — nothing more should be pressed. */
    val merging: Boolean get() = clock.move == PlayerEndMove.Merge

    fun frame(): PlayerEndLiquidFrame? {
        val move = clock.move ?: return null
        return playerEndFrame(move, clock.elapsed, metrics)
    }

    /** The drop that becomes key [index], this frame. */
    fun drop(index: Int): PlayerEndDrop? = frame()?.drops?.getOrNull(index)
}

/**
 * The keys at the end of an item that did not roll on — 下一集 when there is one, 重播 and 返回 —
 * and the liquid they form out of.
 *
 * Every key is at its own place from the start and can be pressed at once; the liquid is drawn
 * behind them and takes no touches. A guest in a room its host drives sees the keys dimmed and still.
 */
@Composable
internal fun EndedKeys(
    hasNext: Boolean,
    watchLocked: Boolean,
    /** The paused key's disc was already where the ending forms: split from it without appearing again. */
    fromPausedKey: Boolean,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    /** True while 重播 flows the keys back together, which the keys have to stay up for. */
    onHold: (Boolean) -> Unit,
) {
    val liquidMotion = liquidMotionEnabled() && !watchLocked
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val liquid =
        remember(hasNext) {
            PlayerEndLiquid(
                metrics =
                    PlayerEndMetrics(
                        hasNext = hasNext,
                        radius = CenterKeySize.value / 2f,
                        step = (CenterKeySize + ControlTouchPadding * 2 + EndedKeySpacing).value,
                    ),
                armed =
                    when {
                        !liquidMotion -> null
                        fromPausedKey -> PlayerEndMove.SplitFromPausedKey
                        else -> PlayerEndMove.Split
                    },
            )
        }
    LaunchedEffect(liquid) {
        val move = liquid.clock.move ?: return@LaunchedEffect
        liquid.clock.play(move, move.durationMs)
    }
    val scope = rememberCoroutineScope()
    val latestOnHold by rememberUpdatedState(onHold)
    val drawing = liquid.drawing
    val merging = liquid.merging

    fun glyph(
        index: Int,
        hollowing: Boolean,
    ): Modifier =
        if (!drawing) {
            Modifier
        } else {
            Modifier
                .graphicsLayer {
                    val drop = liquid.drop(index) ?: return@graphicsLayer
                    val shift = (drop.center - liquid.metrics.centers[index]) * density
                    translationX = if (rtl) -shift else shift
                    alpha = drop.glyph
                    scaleX = GLYPH_FOCUS_FROM + (1f - GLYPH_FOCUS_FROM) * drop.glyph
                    scaleY = scaleX
                    val blur = GLYPH_FOCUS_BLUR.toPx() * (1f - drop.glyph)
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                }.then(
                    if (hollowing) {
                        Modifier.hollowingGlyphTint { liquid.drop(index)?.hollow }
                    } else {
                        Modifier
                    },
                )
        }
    Row(
        Modifier.drawBehind {
            val frame = liquid.frame() ?: return@drawBehind
            val metrics = liquid.metrics
            val reach = metrics.step + metrics.radius * 2f
            val segments = liquidOutline(frame.bodies(), -reach, reach, metrics.radius * 1.5f)
            val scale = if (rtl) -density else density
            liquid.paths.update(segments, scale = scale, axisY = size.height / 2f, originX = size.width / 2f)
            drawPlayerEnd(frame, liquid.paths, originX = size.width / 2f, scale = scale)
        },
        horizontalArrangement = Arrangement.spacedBy(EndedKeySpacing),
    ) {
        if (hasNext) {
            CircleControl(
                icon = AppIcons.Next,
                description = "下一集",
                size = CenterKeySize,
                iconSize = CenterKeyIconSize,
                enabled = !watchLocked && !merging,
                filled = true,
                onClick = onNext,
                bodyVisible = !drawing,
                glyphModifier = glyph(0, hollowing = false),
            )
        }
        CircleControl(
            icon = AppIcons.Refresh,
            description = "重播",
            size = CenterKeySize,
            iconSize = CenterKeyIconSize,
            enabled = !watchLocked && !merging,
            filled = !hasNext,
            onClick = {
                if (liquidMotion) {
                    latestOnHold(true)
                    scope.launch {
                        try {
                            // It ends with nothing left to show: the keys must not take their discs back.
                            liquid.clock.play(
                                PlayerEndMove.Merge,
                                PlayerEndMove.Merge.durationMs,
                                holdLastFrame = true,
                            )
                        } finally {
                            latestOnHold(false)
                        }
                    }
                }
                onReplay()
            },
            bodyVisible = !drawing,
            glyphModifier = glyph(if (hasNext) 1 else 0, hollowing = hasNext),
        )
        CircleControl(
            icon = AppIcons.Close,
            description = "返回",
            size = CenterKeySize,
            iconSize = CenterKeyIconSize,
            onClick = onBack,
            bodyVisible = !drawing,
            glyphModifier = glyph(if (hasNext) 2 else 1, hollowing = true),
        )
    }
}

/**
 * A white glyph that is still sitting on white: dark while its drop is solid, white once it has
 * emptied into a ring. [hollow] is read while drawing.
 */
private fun Modifier.hollowingGlyphTint(hollow: () -> Float?): Modifier =
    drawWithContent {
        val emptied = hollow() ?: return@drawWithContent drawContent()
        val tint = lerp(PlayerTokens.onPlay, Color.White, smooth(0.15f, 0.4f, emptied))
        val paint = Paint().apply { colorFilter = ColorFilter.tint(tint) }
        drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
        drawContent()
        drawIntoCanvas { it.restore() }
    }

/** Between the ending's keys' touch targets, as the row has always spaced them. */
internal val EndedKeySpacing = 18.dp

/** A glyph comes into focus from this scale and this blur once its key has formed. */
private const val GLYPH_FOCUS_FROM = 0.8f
private val GLYPH_FOCUS_BLUR = 3.dp

/** The split follows the drop's own 180 ms arrival by 120 ms. */
internal const val PLAYER_END_SPLIT_LEAD_MS = 120

/** Drained by the last pinch plus 340 ms, and the late right-hand spring within a sixth of a dp of rest. */
internal const val PLAYER_END_SPLIT_MS = 700
internal const val PLAYER_END_MERGE_MS = 490

/** When the last neck breaks, for 52 dp keys 84 dp apart — found with the engine; see the tests. */
internal const val PLAYER_END_PINCH_MS = 150f
internal const val PLAYER_END_PINCH_SINGLE_MS = 112f

/** Where a side drop starts, just off the parent's centre, and where it is drawn back to. */
private const val SIDE_DROP_START = 4f

/** The drained gradient reaches a dp past the rim, so the ring never shows a sliver of white outside it. */
private const val DRAIN_REACH = 1f
private const val RING_ALPHA = 0.62f
private const val RING_WIDTH = 1f
