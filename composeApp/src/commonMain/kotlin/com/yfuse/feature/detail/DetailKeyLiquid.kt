package com.yfuse.feature.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.LiquidBody
import com.yfuse.core.designsystem.LiquidClock
import com.yfuse.core.designsystem.LiquidMotion
import com.yfuse.core.designsystem.LiquidMotion.inOut
import com.yfuse.core.designsystem.LiquidMotion.lerp
import com.yfuse.core.designsystem.LiquidMotion.smooth
import com.yfuse.core.designsystem.LiquidMotion.spring
import com.yfuse.core.designsystem.LiquidPaths
import com.yfuse.core.designsystem.drawPathShadow

/*
 * 详情页 · 液态分离 — 从头 grows out of the play key.
 *
 * With progress to resume, the play key first arrives whole, as 继续播放; then a drop swells in its
 * right end, draws out a neck and lets go, and once free it squares up into the 从头 key beside it,
 * its label coming into focus last. The split says something: there is progress now, and there
 * are two ways to use it. When the progress goes — marked as watched, or reset — 从头 flows back.
 *
 * Geometry is in dp along the row of keys, from the play key's left edge.
 */

/** The row the two keys share, in dp. */
internal data class DetailKeyMetrics(
    val width: Float,
) {
    /** The play key's right edge when 从头 is a key of its own. */
    val splitEdge: Float get() = width - DETAIL_FROM_START_WIDTH - DETAIL_KEY_GAP

    /** 从头's centre. */
    val fromStartCenter: Float get() = width - DETAIL_FROM_START_WIDTH / 2f
}

internal enum class DetailKeyMove(
    val durationMs: Int,
) {
    /** 从头 buds off the play key and squares up. */
    Split(DETAIL_SPLIT_MS),

    /** 从头 turns back into a drop and is drawn into the play key's end. */
    Merge(DETAIL_MERGE_MS),
}

/** One frame of the key row, in dp. */
internal class DetailKeyFrame(
    /** The play key's right edge. */
    val keyEdge: Float,
    val dropCenter: Float,
    val dropRadius: Float,
    /** 0 a drop, 1 the 从头 key. */
    val morph: Float,
    val blend: Float,
    /** 「↻ 从头」 in focus. */
    val label: Float,
    /** The resume time on the play key; fades as the progress goes. */
    val resume: Float,
    /** Whether there is a drop at all. */
    val child: Boolean,
) {
    fun bodies(): List<LiquidBody> {
        val halfKey = keyEdge / 2f
        val key = LiquidBody.box(halfKey, halfKey, DETAIL_KEY_HALF_HEIGHT, DETAIL_KEY_CORNER)
        if (!child) return listOf(key)
        return listOf(
            key,
            LiquidBody.morph(
                center = dropCenter,
                radius = dropRadius,
                halfWidth = lerp(dropRadius, DETAIL_FROM_START_WIDTH / 2f, morph),
                halfHeight = lerp(dropRadius, DETAIL_KEY_HALF_HEIGHT, morph),
                corner = lerp(dropRadius, DETAIL_KEY_CORNER, morph),
                amount = morph,
                blend = blend,
            ),
        )
    }
}

internal fun detailKeyFrame(
    move: DetailKeyMove,
    ms: Float,
    metrics: DetailKeyMetrics,
): DetailKeyFrame =
    when (move) {
        DetailKeyMove.Split -> detailSplitFrame(ms, metrics)
        DetailKeyMove.Merge -> detailMergeFrame(ms, metrics)
    }

/**
 * [ms] into the split: the play key's right edge retreats by 从头's width and the gap (ζ 0.8,
 * 2.2 Hz) while a drop in its end swells from 0.8 of the key's half-height and is pushed out to
 * 从头's centre (ζ 0.7, 2.4 Hz). The neck breaks at [DETAIL_SPLIT_PINCH_MS]; only then does the drop
 * square up, so the growing key cannot reach back and rejoin.
 */
internal fun detailSplitFrame(
    ms: Float,
    metrics: DetailKeyMetrics,
): DetailKeyFrame {
    val pinch = DETAIL_SPLIT_PINCH_MS.toFloat()
    return DetailKeyFrame(
        keyEdge = lerp(metrics.width, metrics.splitEdge, spring(ms, 0.8f, 2.2f)),
        dropCenter =
            lerp(metrics.width - DETAIL_KEY_HALF_HEIGHT, metrics.fromStartCenter, spring(ms - 20f, 0.7f, 2.4f)),
        dropRadius = DETAIL_KEY_HALF_HEIGHT * lerp(0.8f, 1f, smooth(0f, 140f, ms)),
        morph = smooth(pinch + 10f, pinch + 250f, ms),
        blend = LiquidMotion.VISCOSITY * LiquidMotion.splitViscosity(ms),
        label = smooth(pinch + 110f, pinch + 310f, ms),
        resume = 1f,
        child = true,
    )
}

/**
 * [ms] into the merge: 「↻ 从头」 fades and the key rounds back into a drop, a bridge forms, and
 * the play key's edge reaches back out while the drop shrinks into its end. The resume time fades
 * as the play key's label changes back to 播放.
 */
internal fun detailMergeFrame(
    ms: Float,
    metrics: DetailKeyMetrics,
): DetailKeyFrame {
    val travel = inOut((ms - 60f) / 300f)
    return DetailKeyFrame(
        keyEdge = lerp(metrics.splitEdge, metrics.width, travel),
        dropCenter = lerp(metrics.fromStartCenter, metrics.width - MERGE_DROP_INSET, travel),
        dropRadius = DETAIL_KEY_HALF_HEIGHT * lerp(1f, 0.6f, smooth(140f, 300f, ms)),
        morph = 1f - smooth(0f, 160f, ms),
        blend = LiquidMotion.VISCOSITY * smooth(40f, 100f, ms) * (1f - smooth(180f, 280f, ms)),
        label = 1f - smooth(0f, 90f, ms),
        resume = 1f - smooth(MERGE_LABEL_DELAY_MS.toFloat(), 360f, ms),
        child = ms < DETAIL_MERGE_MS,
    )
}

/** The two keys' liquid: which move is playing, and the row it is laid along. */
@Stable
internal class DetailKeyLiquid(
    armed: DetailKeyMove?,
) {
    val clock = LiquidClock(armed)

    var metrics: DetailKeyMetrics? by mutableStateOf(null)

    val paths = LiquidPaths()

    /** Whether the liquid is drawing the keys' bodies, in which case the keys draw only their content. */
    val drawing: Boolean get() = clock.move != null

    fun frame(): DetailKeyFrame? {
        val move = clock.move ?: return null
        val metrics = metrics ?: return null
        return detailKeyFrame(move, clock.elapsed, metrics)
    }

    /** The resume time's opacity now; [progress] is whether there is still a position to resume. */
    fun resumeAlpha(progress: Boolean): Float {
        val move = clock.move ?: return 1f
        val metrics = metrics ?: return 1f
        return detailResumeAlpha(move, detailKeyFrame(move, clock.elapsed, metrics), metrics, progress)
    }
}

/**
 * Whether 「↻ 从头」 is a key of its own in [frame]: once its label is half in focus. Before that the
 * row shows one key, and its end plays rather than starting over. No frame is the keys at rest.
 */
internal fun fromStartIsKey(frame: DetailKeyFrame?): Boolean = (frame?.label ?: 1f) >= 0.5f

/**
 * The resume time's opacity in [frame]. A split running back because the progress went takes the
 * time away as the play key's end comes back out over it; it used to stay to the end and vanish.
 */
internal fun detailResumeAlpha(
    move: DetailKeyMove,
    frame: DetailKeyFrame,
    metrics: DetailKeyMetrics,
    progress: Boolean,
): Float =
    if (move == DetailKeyMove.Split && !progress) {
        ((metrics.width - frame.keyEdge) / (metrics.width - metrics.splitEdge)).coerceIn(0f, 1f)
    } else {
        frame.resume
    }

/**
 * The two keys' bodies along [paths]: the key's lift under the whole outline, then each piece in the
 * key's gradient sized to that piece — so each lands looking exactly like the key it becomes.
 */
internal fun DrawScope.drawDetailKeys(
    paths: LiquidPaths,
    brush: Brush,
) {
    if (paths.pieces.isEmpty()) return
    drawPathShadow(paths.outline, GlassLift.key)
    paths.pieces.forEach { piece ->
        val box = piece.bounds
        inset(box.left, box.top, size.width - box.right, size.height - box.bottom) {
            drawPath(piece.path, brush)
        }
    }
}

internal const val DETAIL_FROM_START_WIDTH = 74f
internal const val DETAIL_KEY_GAP = 10f
internal const val DETAIL_KEY_HALF_HEIGHT = 26f
internal const val DETAIL_KEY_CORNER = 16f

/** When the neck breaks, for the key heights above — found with the engine; see the tests. */
internal const val DETAIL_SPLIT_PINCH_MS = 280

/** 从头's label is in focus by the pinch plus 310 ms, and both springs are within a tenth of a dp. */
internal const val DETAIL_SPLIT_MS = 600
internal const val DETAIL_MERGE_MS = 420

/** The play key's label changes back to 播放 this far into the merge, once the drop is on its way in. */
internal const val MERGE_LABEL_DELAY_MS = 200

/** A page arrives in 320 ms; the split waits for it and a beat more. */
internal const val DETAIL_SPLIT_ARRIVAL_MS = 420L

/** Where the drop ends up inside the play key's end as it is drawn in. */
private const val MERGE_DROP_INSET = 28f
