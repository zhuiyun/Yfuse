package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine

data class YCoreRuntimeObservation(
    val nowEpochMs: Long,
    val positionMs: Long,
    val playbackRequested: Boolean,
    val buffering: Boolean,
    val videoReady: Boolean,
    val videoExpected: Boolean = true,
    val audioReady: Boolean = true,
    val audioExpected: Boolean = false,
    val errorPresent: Boolean,
    val ended: Boolean,
    val bufferEvents: Int,
    val droppedFrames: Int,
    val measuredPowerMilliwatts: Int? = null,
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
                    observation.playbackRequested &&
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
