package com.yfuse.core2.render

import com.yfuse.core2.api.YFrameRateSample

/** Samples an actual output counter on its owner thread; never derives FPS from media timestamps. */
internal class YRenderedFrameRateSampler {
    private var baselineCount = 0L
    private var baselineMs: Long? = null
    private var lastCount = 0L
    private var lastObservedMs = 0L
    private var latest: YFrameRateSample? = null

    fun sample(
        frameCount: Long,
        nowMs: Long,
    ): YFrameRateSample? {
        if (frameCount < 0L || nowMs < 0L) {
            reset()
            return null
        }
        val baseline = baselineMs
        if (baseline == null ||
            frameCount < lastCount ||
            nowMs < lastObservedMs ||
            nowMs - lastObservedMs > MAX_SAMPLE_GAP_MS
        ) {
            reset()
            baselineCount = frameCount
            baselineMs = nowMs
            lastCount = frameCount
            lastObservedMs = nowMs
            return null
        }
        lastCount = frameCount
        lastObservedMs = nowMs
        val elapsedMs = nowMs - baseline
        if (elapsedMs >= SAMPLE_WINDOW_MS) {
            latest =
                YFrameRateSample(
                    framesPerSecond =
                        (frameCount - baselineCount)
                            .toDouble()
                            .times(1_000.0)
                            .div(elapsedMs)
                            .toFloat(),
                    sampledAtElapsedMs = nowMs,
                )
            baselineCount = frameCount
            baselineMs = nowMs
        }
        return latest
    }

    fun reset() {
        baselineCount = 0L
        baselineMs = null
        lastCount = 0L
        lastObservedMs = 0L
        latest = null
    }
}

private const val SAMPLE_WINDOW_MS = 1_000L
private const val MAX_SAMPLE_GAP_MS = 3_000L
