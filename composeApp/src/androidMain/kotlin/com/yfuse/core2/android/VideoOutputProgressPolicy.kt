package com.yfuse.core2.android

/** Audio can remain healthy while a codec accepts video packets without ever producing a frame. */
internal class VideoDecoderNoOutputException :
    IllegalStateException("Video decoder accepted input without producing output")

internal class VideoFirstOutputWatchdog(
    private val timeoutNs: Long = 12_000_000_000L,
) {
    private var inputQueued = false
    private var activeSinceNs: Long? = null

    fun inputQueued() {
        inputQueued = true
    }

    fun reset() {
        inputQueued = false
        activeSinceNs = null
    }

    fun stalled(
        nowNs: Long,
        active: Boolean,
        outputVerified: Boolean,
    ): Boolean {
        if (!active || !inputQueued || outputVerified) {
            activeSinceNs = null
            return false
        }
        val since = activeSinceNs ?: nowNs.also { activeSinceNs = it }
        return nowNs - since >= timeoutNs
    }
}

/**
 * A slow software decoder must keep presenting bounded progress while it catches up. Recent
 * output still permits normal late-frame dropping; seeks reject preroll independently.
 */
internal fun preserveSoftwareVideoProgress(
    decision: YVideoFrameReleaseDecision,
    renderedRealtimeNs: Long,
    nowNs: Long,
): YVideoFrameReleaseDecision =
    if (decision == YVideoFrameReleaseDecision.Drop &&
        (renderedRealtimeNs <= 0L || nowNs - renderedRealtimeNs >= SOFTWARE_VIDEO_PROGRESS_INTERVAL_NS)
    ) {
        YVideoFrameReleaseDecision.Render(nowNs)
    } else {
        decision
    }

internal fun softwareVideoMinimumPresentationUs(
    seekTargetUs: Long,
    masterPositionUs: Long,
    renderedRealtimeNs: Long,
    nowNs: Long,
    preview: Boolean,
): Long =
    if (!preview && renderedRealtimeNs > 0L && nowNs - renderedRealtimeNs < SOFTWARE_VIDEO_PROGRESS_INTERVAL_NS) {
        maxOf(seekTargetUs, masterPositionUs - 100_000L)
    } else {
        seekTargetUs
    }

private const val SOFTWARE_VIDEO_PROGRESS_INTERVAL_NS = 250_000_000L
