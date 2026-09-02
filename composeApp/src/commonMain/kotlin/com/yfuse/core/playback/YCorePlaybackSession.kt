package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine

data class YCoreRuntimeObservation(
    val nowEpochMs: Long,
    val positionMs: Long,
    val playbackRequested: Boolean,
    val buffering: Boolean,
    val videoReady: Boolean,
    val videoExpected: Boolean = true,
    val videoOutputVerifiable: Boolean = true,
    val audioReady: Boolean = true,
    val audioExpected: Boolean = false,
    val audioOutputVerifiable: Boolean = true,
    val errorPresent: Boolean,
    val ended: Boolean,
    val bufferEvents: Int,
    val droppedFrames: Int,
    /** True when the active source depends on a remote transport. */
    val sourceRemote: Boolean = false,
    /** Current compressed read-ahead state; used only to distinguish transport starvation. */
    val sourceQueueBytes: Long = 0L,
    val sourceBufferedMs: Long = 0L,
    val sourceStarvationCount: Long = 0L,
    val networkBitsPerSecond: Long = 0L,
    val measuredPowerMilliwatts: Int? = null,
    /** Actual output progression. False for an explicit pause even if a backend kept its intent. */
    val playing: Boolean = playbackRequested && !buffering,
)

data class YCoreRuntimeAssessment(
    val health: PlaybackHealthAssessment,
    val power: PlaybackPowerAssessment,
    val reportHealth: Boolean = false,
    val enginePenaltyRecorded: Boolean = false,
    val engineCapabilityConfirmed: Boolean = false,
    val runtimeFault: PlaybackRuntimeFault? = null,
)

/**
 * Owns the adaptive state for one concrete engine binding.
 *
 * Compose observes the immutable result only; health thresholds, persistent quirks and success
 * confirmation remain isolated from the player UI lifecycle.
 */
class YCorePlaybackSession(
    private val engine: PlayerEngine,
    private val probe: PlaybackMediaProbe,
    plan: PlaybackPlan,
    private val failureMemory: PlaybackFailureMemory,
    private val performanceMemory: PlaybackPerformanceMemory? = null,
    startedAtEpochMs: Long,
    initialPositionMs: Long,
    initialBufferEvents: Int,
    initialDroppedFrames: Int,
) {
    private val healthSession =
        PlaybackHealthSession(
            startedAtEpochMs = startedAtEpochMs,
            initialPositionMs = initialPositionMs,
            initialBufferEvents = initialBufferEvents,
            initialDroppedFrames = initialDroppedFrames,
        )
    private val estimatedPower = playbackPowerAssessment(plan, probe)
    private val runtimeFaultDetector =
        PlaybackRuntimeFaultDetector(
            startedAtEpochMs = startedAtEpochMs,
            initialPositionMs = initialPositionMs,
            startupTimeoutMs = playbackStartupTimeoutMs(probe),
            rebufferTimeoutMs = playbackRebufferTimeoutMs(probe),
        )
    private var reported = false
    private var penaltyRecorded = false
    private var capabilityConfirmed = false

    val initialAssessment: YCoreRuntimeAssessment =
        YCoreRuntimeAssessment(
            health =
                assessPlaybackHealth(
                    PlaybackHealthSample(
                        startupTimeMs = null,
                        observedPlaybackMs = 0L,
                        rebufferEvents = 0,
                        droppedFrames = 0,
                    ),
                ),
            power = estimatedPower,
        )

    fun observe(observation: YCoreRuntimeObservation): YCoreRuntimeAssessment {
        val runtimeFault = runtimeFaultDetector.observe(observation)
        runtimeFault?.let { fault ->
            failureMemory.record(probe.capabilitySignature, engine, fault.kind.failureKind)
        }
        val health =
            healthSession.observe(
                nowEpochMs = observation.nowEpochMs,
                positionMs = observation.positionMs,
                activelyRendering =
                    observation.playing &&
                        !observation.buffering &&
                        !observation.errorPresent &&
                        !observation.ended,
                videoReady = observation.videoReady,
                bufferEvents = observation.bufferEvents,
                droppedFrames = observation.droppedFrames,
            )
        val reportHealth = health.evaluationReady && !reported
        if (reportHealth) {
            reported = true
            performanceMemory?.record(probe.capabilitySignature, engine, health)
        }

        val recordPenalty = health.enginePenaltyRecommended && !penaltyRecorded
        if (recordPenalty) {
            penaltyRecorded = true
            failureMemory.record(
                signature = probe.capabilitySignature,
                engine = engine,
                kind = PlaybackFailureKind.Renderer,
            )
        }

        val confirmCapability =
            !capabilityConfirmed &&
                runtimeFault == null &&
                health.evaluationReady &&
                !health.enginePenaltyRecommended &&
                observation.playing &&
                !observation.buffering &&
                !observation.errorPresent
        if (confirmCapability) {
            capabilityConfirmed = true
            failureMemory.recordSuccess(probe.capabilitySignature, engine)
        }

        return YCoreRuntimeAssessment(
            health = health,
            power = estimatedPower.withMeasuredPower(observation.measuredPowerMilliwatts),
            reportHealth = reportHealth,
            enginePenaltyRecorded = recordPenalty,
            engineCapabilityConfirmed = confirmCapability,
            runtimeFault = runtimeFault,
        )
    }

    fun recordFailure(kind: PlaybackFailureKind) {
        failureMemory.record(probe.capabilitySignature, engine, kind)
    }
}

/** Large or remote sources receive enough probe time while still having a finite escape hatch. */
internal fun playbackStartupTimeoutMs(probe: PlaybackMediaProbe): Long =
    when {
        probe.discSource || probe.discKind != PlaybackDiscKind.None -> 180_000L
        probe.isHugeRemoteMov -> 180_000L
        !probe.localSource && (probe.sourceSizeBytes ?: 0L) >= LARGE_REMOTE_SOURCE_BYTES -> 120_000L
        probe.normalizedContainer == "MOV" ||
            probe.source.videoRequirements.codec == PlaybackVideoCodec.ProRes -> 60_000L
        !probe.localSource -> 60_000L
        else -> 15_000L
    }

/** A settled stream gets a separate, conservative budget before transport recovery is attempted. */
internal fun playbackRebufferTimeoutMs(probe: PlaybackMediaProbe): Long =
    when {
        probe.discSource || probe.discKind != PlaybackDiscKind.None -> 180_000L
        probe.isHugeRemoteMov -> 180_000L
        !probe.localSource && (probe.sourceSizeBytes ?: 0L) >= LARGE_REMOTE_SOURCE_BYTES -> 120_000L
        !probe.localSource -> 60_000L
        else -> 30_000L
    }

private const val LARGE_REMOTE_SOURCE_BYTES = 4L * 1_024L * 1_024L * 1_024L
