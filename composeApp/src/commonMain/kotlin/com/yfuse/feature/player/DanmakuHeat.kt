package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment

/**
 * 弹幕热度曲线 — where the matched comments crowd along the file, drawn as a thin line over the
 * progress bar. B 站's 高能进度条 and YouTube's "most replayed" draw the same thing: the moments
 * everyone reacted to, findable before they are reached.
 */
internal const val DANMAKU_HEAT_BUCKETS = 100

/** How many buckets either side the smoothing reaches. */
internal const val DANMAKU_HEAT_SMOOTHING = 2

/**
 * The comments as the curve needs them: each one's time, and how many lines it stands for. Kept
 * apart from the comments so the rail can rebuild its curve without holding the text.
 */
internal class DanmakuHeat(
    val timesMs: LongArray,
    val weights: IntArray,
)

/**
 * [comments] as heat, each weighted by what it stands for — a merged 笑死 ×128 is 128 reactions,
 * not one. Null when there are none, which is also what an unmatched or switched-off 弹幕 has.
 */
internal fun danmakuHeatOf(comments: List<DanmakuComment>): DanmakuHeat? {
    if (comments.isEmpty()) return null
    return DanmakuHeat(
        timesMs = LongArray(comments.size) { comments[it].timeMs },
        weights = IntArray(comments.size) { comments[it].repeats.coerceAtLeast(1) },
    )
}

/**
 * How many comments fall in each of [buckets] equal parts of a [durationMs] file — 1% each by
 * default. Comments outside the file, from a longer cut or a bad timestamp, count nowhere.
 */
internal fun danmakuHeatBuckets(
    heat: DanmakuHeat,
    durationMs: Long,
    buckets: Int = DANMAKU_HEAT_BUCKETS,
): FloatArray {
    val counts = FloatArray(buckets.coerceAtLeast(0))
    if (durationMs <= 0L || counts.isEmpty()) return counts
    for (index in heat.timesMs.indices) {
        val timeMs = heat.timesMs[index]
        if (timeMs < 0L || timeMs >= durationMs) continue
        val bucket = (timeMs.toDouble() / durationMs * counts.size).toInt().coerceIn(0, counts.lastIndex)
        val weight = heat.weights.getOrElse(index) { 1 }
        counts[bucket] += weight.coerceAtLeast(1).toFloat()
    }
    return counts
}

/**
 * [counts] smoothed with a binomial kernel reaching [radius] buckets either side — 1 4 6 4 1 at
 * the default — and scaled so the busiest bucket is 1. At the ends the kernel is cut off and
 * the rest reweighted, so the first and last minutes are not quieter than they were.
 */
internal fun smoothDanmakuHeat(
    counts: FloatArray,
    radius: Int = DANMAKU_HEAT_SMOOTHING,
): FloatArray {
    val reach = radius.coerceAtLeast(0)
    val kernel = binomialKernel(reach)
    val smoothed = FloatArray(counts.size)
    for (index in counts.indices) {
        var sum = 0f
        var weight = 0f
        for (offset in -reach..reach) {
            val neighbour = index + offset
            if (neighbour !in counts.indices) continue
            sum += counts[neighbour] * kernel[offset + reach]
            weight += kernel[offset + reach]
        }
        smoothed[index] = if (weight > 0f) sum / weight else 0f
    }
    val peak = smoothed.maxOrNull() ?: 0f
    if (peak > 0f) {
        for (index in smoothed.indices) smoothed[index] = smoothed[index] / peak
    }
    return smoothed
}

/** The curve for [heat] over a [durationMs] file, one 0..1 value per bucket; null if nothing falls inside it. */
internal fun danmakuHeatCurve(
    heat: DanmakuHeat,
    durationMs: Long,
): FloatArray? {
    val counts = danmakuHeatBuckets(heat, durationMs)
    if (counts.none { it > 0f }) return null
    return smoothDanmakuHeat(counts)
}

private fun binomialKernel(radius: Int): FloatArray {
    val width = 2 * radius
    val kernel = FloatArray(width + 1)
    var coefficient = 1.0
    for (k in 0..width) {
        kernel[k] = coefficient.toFloat()
        coefficient = coefficient * (width - k) / (k + 1)
    }
    return kernel
}
