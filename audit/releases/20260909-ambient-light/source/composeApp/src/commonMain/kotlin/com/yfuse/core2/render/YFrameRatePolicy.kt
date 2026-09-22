package com.yfuse.core2.render

enum class YFrameRateSwitchMode {
    Disabled,
    SeamlessOnly,
    Always,
}

/** Platform-neutral frame-rate hint validated before it reaches Surface/display APIs. */
data class YFrameRateHint(
    val framesPerSecond: Float,
    val fixedSource: Boolean = true,
    /** VFR/mixed sources should not request a fixed display cadence. */
    val variableSource: Boolean = false,
) {
    init {
        require(framesPerSecond.isFinite())
        require(framesPerSecond in MIN_VIDEO_FPS..MAX_VIDEO_FPS)
    }
}

data class YDisplayRefreshTarget(
    val sourceFramesPerSecond: Float,
    val refreshRate: Float,
    val cadenceMultiplier: Int,
    val exactCadence: Boolean,
)

/**
 * Selects a supported display rate with integer frame cadence. Fractional authored rates are kept
 * intact: 23.976 may therefore select 23.976, 47.952 or 119.88 instead of being rounded to 24/48/120.
 * A VFR/mixed source deliberately returns null so Android stays in its normal adaptive display mode.
 */
fun selectDisplayRefreshTarget(
    hint: YFrameRateHint?,
    supportedRefreshRates: Iterable<Float>,
    currentRefreshRate: Float? = null,
): YDisplayRefreshTarget? {
    if (hint == null || !hint.fixedSource || hint.variableSource) return null
    val source = hint.framesPerSecond
    val candidates =
        supportedRefreshRates
            .filter { it.isFinite() && it in MIN_VIDEO_FPS..MAX_VIDEO_FPS }
            .mapNotNull { refresh ->
                val rawMultiplier = refresh / source
                val multiplier =
                    kotlin.math
                        .round(rawMultiplier)
                        .toInt()
                        .coerceAtLeast(1)
                val exact = kotlin.math.abs(refresh - source * multiplier) <= cadenceTolerance(source * multiplier)
                if (!exact) null else YDisplayRefreshTarget(source, refresh, multiplier, exactCadence = true)
            }
    if (candidates.isEmpty()) return null
    return candidates.minWithOrNull(
        compareBy<YDisplayRefreshTarget> {
            if (currentRefreshRate != null &&
                kotlin.math.abs(it.refreshRate - currentRefreshRate) <= cadenceTolerance(currentRefreshRate)
            ) {
                0
            } else {
                1
            }
        }.thenByDescending { it.refreshRate }
            .thenByDescending { it.cadenceMultiplier },
    )
}

/**
 * Preserves authored fractional cinema/broadcast rates instead of rounding 23.976/29.97/59.94.
 * Values outside realistic video cadence are treated as unknown and produce no display hint.
 */
fun videoFrameRateHint(frameRate: Float): YFrameRateHint? =
    frameRate
        .takeIf { it.isFinite() && it in MIN_VIDEO_FPS..MAX_VIDEO_FPS }
        ?.let(::YFrameRateHint)

private fun cadenceTolerance(rate: Float): Float = maxOf(0.02f, rate * 0.0008f)

private const val MIN_VIDEO_FPS = 1f
private const val MAX_VIDEO_FPS = 240f
