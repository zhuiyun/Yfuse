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
) {
    init {
        require(framesPerSecond.isFinite())
        require(framesPerSecond in MIN_VIDEO_FPS..MAX_VIDEO_FPS)
    }
}

/**
 * Preserves authored fractional cinema/broadcast rates instead of rounding 23.976/29.97/59.94.
 * Values outside realistic video cadence are treated as unknown and produce no display hint.
 */
fun videoFrameRateHint(frameRate: Float): YFrameRateHint? =
    frameRate
        .takeIf { it.isFinite() && it in MIN_VIDEO_FPS..MAX_VIDEO_FPS }
        ?.let(::YFrameRateHint)

private const val MIN_VIDEO_FPS = 1f
private const val MAX_VIDEO_FPS = 240f
