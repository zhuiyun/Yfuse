package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import kotlin.math.roundToInt

enum class PlaybackHealthGrade(
    val label: String,
) {
    Starting("采集中"),
    Excellent("优秀"),
    Healthy("稳定"),
    Degraded("一般"),
    Critical("较差"),
}

enum class PlaybackPowerProfile(
    val label: String,
) {
    Efficient("低功耗"),
    Balanced("均衡"),
    Intensive("高负载"),
}

data class PlaybackHealthSample(
    val startupTimeMs: Long?,
    val observedPlaybackMs: Long,
    val rebufferEvents: Int,
    val droppedFrames: Int,
)

data class PlaybackHealthAssessment(
    val grade: PlaybackHealthGrade,
    val startupTimeMs: Long?,
    val observedPlaybackMs: Long,
    val rebufferEvents: Int,
    val droppedFrames: Int,
    val droppedFramesPerMinute: Float,
    val evaluationReady: Boolean,
    /** Buffering is usually a network problem; only sustained frame loss penalizes an engine. */
    val enginePenaltyRecommended: Boolean,
) {
    val diagnosticLabel: String
        get() =
            buildString {
                append(grade.label)
                startupTimeMs?.let { startup -> append(" · 首帧 ${startup.asSecondsLabel()}") }
                if (evaluationReady) {
                    append(" · 缓冲 ")
                    append(rebufferEvents)
                    append(" 次")
                    if (droppedFrames > 0) {
                        append(" · 丢帧 ")
                        append(droppedFrames)
                    }
                }
            }
}

data class PlaybackPowerAssessment(
    val profile: PlaybackPowerProfile,
    val reason: String,
) {
    val diagnosticLabel: String
        get() = "${profile.label} · $reason"
}

fun assessPlaybackHealth(sample: PlaybackHealthSample): PlaybackHealthAssessment {
    val observedMs = sample.observedPlaybackMs.coerceAtLeast(0L)
    val rebufferEvents = sample.rebufferEvents.coerceAtLeast(0)
    val droppedFrames = sample.droppedFrames.coerceAtLeast(0)
    val evaluationReady = sample.startupTimeMs != null && observedMs >= HEALTH_EVALUATION_WINDOW_MS
    val droppedFramesPerMinute =
        if (observedMs <= 0L) {
            0f
        } else {
            droppedFrames * 60_000f / observedMs
        }
    val grade =
        when {
            sample.startupTimeMs == null -> PlaybackHealthGrade.Starting
            !evaluationReady -> PlaybackHealthGrade.Healthy
            sample.startupTimeMs > CRITICAL_STARTUP_MS ||
                rebufferEvents >= CRITICAL_REBUFFER_EVENTS ||
                droppedFramesPerMinute >= CRITICAL_DROPPED_FRAMES_PER_MINUTE ->
                PlaybackHealthGrade.Critical
            sample.startupTimeMs > DEGRADED_STARTUP_MS ||
                rebufferEvents >= DEGRADED_REBUFFER_EVENTS ||
                droppedFramesPerMinute >= DEGRADED_DROPPED_FRAMES_PER_MINUTE ->
                PlaybackHealthGrade.Degraded
            sample.startupTimeMs <= EXCELLENT_STARTUP_MS &&
                rebufferEvents == 0 &&
                droppedFramesPerMinute < EXCELLENT_DROPPED_FRAMES_PER_MINUTE ->
                PlaybackHealthGrade.Excellent
            else -> PlaybackHealthGrade.Healthy
        }
    return PlaybackHealthAssessment(
        grade = grade,
        startupTimeMs = sample.startupTimeMs,
        observedPlaybackMs = observedMs,
        rebufferEvents = rebufferEvents,
        droppedFrames = droppedFrames,
        droppedFramesPerMinute = droppedFramesPerMinute,
        evaluationReady = evaluationReady,
        enginePenaltyRecommended =
            evaluationReady &&
                droppedFramesPerMinute >= CRITICAL_DROPPED_FRAMES_PER_MINUTE,
    )
}

/** Tracks one concrete engine binding without retaining media ids, URLs or account data. */
class PlaybackHealthSession(
    private val startedAtEpochMs: Long,
    private val initialPositionMs: Long,
    initialBufferEvents: Int,
    initialDroppedFrames: Int,
) {
    private var firstStableFrameAtEpochMs: Long? = null
    private var bufferEventsAtFirstFrame = initialBufferEvents.coerceAtLeast(0)
    private var droppedFramesAtFirstFrame = initialDroppedFrames.coerceAtLeast(0)
    private var lastObservationEpochMs = startedAtEpochMs
    private var previouslyRendering = false
    private var observedPlaybackMs = 0L

    fun observe(
        nowEpochMs: Long,
        positionMs: Long,
        activelyRendering: Boolean,
        videoReady: Boolean,
        bufferEvents: Int,
        droppedFrames: Int,
    ): PlaybackHealthAssessment {
        val now = nowEpochMs.coerceAtLeast(startedAtEpochMs)
        val elapsed = (now - lastObservationEpochMs).coerceIn(0L, MAX_OBSERVATION_STEP_MS)
        if (previouslyRendering) observedPlaybackMs += elapsed
        lastObservationEpochMs = now
        previouslyRendering = activelyRendering

        if (
            firstStableFrameAtEpochMs == null &&
            activelyRendering &&
            (videoReady || positionMs > initialPositionMs)
        ) {
            firstStableFrameAtEpochMs = now
            bufferEventsAtFirstFrame = bufferEvents.coerceAtLeast(0)
            droppedFramesAtFirstFrame = droppedFrames.coerceAtLeast(0)
        }

        return assessPlaybackHealth(
            PlaybackHealthSample(
                startupTimeMs = firstStableFrameAtEpochMs?.let { it - startedAtEpochMs },
                observedPlaybackMs = observedPlaybackMs,
                rebufferEvents = (bufferEvents - bufferEventsAtFirstFrame).coerceAtLeast(0),
                droppedFrames = (droppedFrames - droppedFramesAtFirstFrame).coerceAtLeast(0),
            ),
        )
    }
}

fun playbackPowerAssessment(
    plan: PlaybackPlan,
    probe: PlaybackMediaProbe,
): PlaybackPowerAssessment {
    val isUltraHd = (probe.source.width ?: 0) > 2_560 || (probe.source.height ?: 0) > 1_440
    return when {
        plan.renderPath == PlaybackRenderPath.PlatformDirect && plan.decoderMode != DecoderMode.Software ->
            PlaybackPowerAssessment(PlaybackPowerProfile.Efficient, "平台硬解直出")
        plan.renderPath == PlaybackRenderPath.ServerTranscode ->
            PlaybackPowerAssessment(PlaybackPowerProfile.Efficient, "服务器转换后硬解")
        plan.renderPath == PlaybackRenderPath.GpuToneMapped ->
            PlaybackPowerAssessment(PlaybackPowerProfile.Intensive, "GPU HDR 色调映射")
        plan.decoderMode == DecoderMode.Software && isUltraHd ->
            PlaybackPowerAssessment(PlaybackPowerProfile.Intensive, "高分辨率软件解码")
        plan.decoderMode == DecoderMode.Software ->
            PlaybackPowerAssessment(PlaybackPowerProfile.Intensive, "FFmpeg 软件解码")
        else -> PlaybackPowerAssessment(PlaybackPowerProfile.Balanced, "原生解封装与硬解")
    }
}

private fun Long.asSecondsLabel(): String = "${(this / 100f).roundToInt() / 10f}s"

private const val HEALTH_EVALUATION_WINDOW_MS = 30_000L
private const val MAX_OBSERVATION_STEP_MS = 5_000L
private const val EXCELLENT_STARTUP_MS = 1_500L
private const val DEGRADED_STARTUP_MS = 5_000L
private const val CRITICAL_STARTUP_MS = 12_000L
private const val DEGRADED_REBUFFER_EVENTS = 2
private const val CRITICAL_REBUFFER_EVENTS = 4
private const val EXCELLENT_DROPPED_FRAMES_PER_MINUTE = 1f
private const val DEGRADED_DROPPED_FRAMES_PER_MINUTE = 10f
private const val CRITICAL_DROPPED_FRAMES_PER_MINUTE = 30f
