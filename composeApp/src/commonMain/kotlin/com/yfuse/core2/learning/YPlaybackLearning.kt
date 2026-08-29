package com.yfuse.core2.learning

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import kotlin.math.abs

/** Privacy-safe route identity. It deliberately cannot contain media, provider, URI or auth ids. */
data class YPlaybackLearningKey(
    val route: YPlaybackRoute,
    val container: YContainer,
    val videoCodec: YVideoCodec,
    val hdrType: YHdrType,
    val decoderName: String? = null,
)

data class YPlaybackObservation(
    val rendered: Boolean,
    val playedDurationMs: Long,
    val droppedFrames: Int = 0,
    val codecResets: Int = 0,
    val audioUnderruns: Int = 0,
    val maximumAbsoluteAvDriftMs: Long = 0L,
    val maximumThermalStatus: Int = 0,
    val batteryDeltaPermille: Int = 0,
) {
    init {
        require(playedDurationMs >= 0L)
        require(droppedFrames >= 0)
        require(codecResets >= 0)
        require(audioUnderruns >= 0)
        require(maximumAbsoluteAvDriftMs >= 0L)
        require(maximumThermalStatus >= 0)
    }
}

data class YPlaybackLearningRecord(
    val key: YPlaybackLearningKey,
    val attempts: Int,
    val successfulAttempts: Int,
    val consecutiveFailures: Int,
    val playedDurationMs: Long,
    val droppedFrames: Long,
    val codecResets: Long,
    val audioUnderruns: Long,
    val maximumAbsoluteAvDriftMs: Long,
    val maximumThermalStatus: Int,
    val batteryDeltaPermille: Long,
    val updatedAtEpochMs: Long,
)

interface YPlaybackLearningStore {
    fun load(): List<YPlaybackLearningRecord>

    fun replace(records: List<YPlaybackLearningRecord>)
}

class InMemoryYPlaybackLearningStore : YPlaybackLearningStore {
    private var records: List<YPlaybackLearningRecord> = emptyList()

    override fun load(): List<YPlaybackLearningRecord> = records

    override fun replace(records: List<YPlaybackLearningRecord>) {
        this.records = records.toList()
    }
}

enum class YLearnedRouteAdvice {
    Allow,
    Penalize,
    Avoid,
}

class YPlaybackLearningEngine(
    private val store: YPlaybackLearningStore,
    private val nowEpochMs: () -> Long,
    private val maxRecords: Int = 128,
) {
    init {
        require(maxRecords > 0)
    }

    @Synchronized
    fun record(
        key: YPlaybackLearningKey,
        observation: YPlaybackObservation,
    ): YPlaybackLearningRecord {
        val records = store.load()
        val old = records.firstOrNull { it.key == key }
        val successful = observation.rendered && observation.codecResets == 0
        val updated =
            YPlaybackLearningRecord(
                key = key,
                attempts = (old?.attempts ?: 0).saturatedIncrement(),
                successfulAttempts =
                    if (successful) {
                        (old?.successfulAttempts ?: 0).saturatedIncrement()
                    } else {
                        old?.successfulAttempts ?: 0
                    },
                consecutiveFailures = if (successful) 0 else (old?.consecutiveFailures ?: 0).saturatedIncrement(),
                playedDurationMs = (old?.playedDurationMs ?: 0L).saturatedAdd(observation.playedDurationMs),
                droppedFrames = (old?.droppedFrames ?: 0L).saturatedAdd(observation.droppedFrames.toLong()),
                codecResets = (old?.codecResets ?: 0L).saturatedAdd(observation.codecResets.toLong()),
                audioUnderruns = (old?.audioUnderruns ?: 0L).saturatedAdd(observation.audioUnderruns.toLong()),
                maximumAbsoluteAvDriftMs =
                    maxOf(old?.maximumAbsoluteAvDriftMs ?: 0L, observation.maximumAbsoluteAvDriftMs),
                maximumThermalStatus = maxOf(old?.maximumThermalStatus ?: 0, observation.maximumThermalStatus),
                batteryDeltaPermille =
                    (old?.batteryDeltaPermille ?: 0L).saturatedAdd(abs(observation.batteryDeltaPermille.toLong())),
                updatedAtEpochMs = nowEpochMs().coerceAtLeast(0L),
            )
        store.replace(
            records
                .filterNot { it.key == key }
                .plus(updated)
                .sortedByDescending(YPlaybackLearningRecord::updatedAtEpochMs)
                .take(maxRecords),
        )
        return updated
    }

    @Synchronized
    fun advice(key: YPlaybackLearningKey): YLearnedRouteAdvice {
        val record = store.load().firstOrNull { it.key == key } ?: return YLearnedRouteAdvice.Allow
        if (record.consecutiveFailures >= FAILURES_TO_AVOID) return YLearnedRouteAdvice.Avoid
        val dropRate =
            if (record.playedDurationMs > 0L) {
                record.droppedFrames.toDouble() / (record.playedDurationMs.toDouble() / 1_000.0)
            } else {
                0.0
            }
        if (
            record.attempts >= QUALITY_ATTEMPTS_TO_AVOID &&
            (
                record.codecResets >= CODEC_RESETS_TO_AVOID ||
                    record.audioUnderruns >= UNDERRUNS_TO_AVOID ||
                    record.maximumAbsoluteAvDriftMs >= AV_DRIFT_TO_AVOID_MS ||
                    record.maximumThermalStatus >= THERMAL_STATUS_SEVERE ||
                    (
                        record.playedDurationMs >= QUALITY_DURATION_TO_AVOID_MS &&
                            dropRate >= DROPPED_FRAMES_PER_SECOND_TO_AVOID
                    )
            )
        ) {
            return YLearnedRouteAdvice.Avoid
        }
        return if (
            record.codecResets > 0L ||
            record.audioUnderruns >= UNDERRUNS_TO_PENALIZE ||
            record.maximumAbsoluteAvDriftMs >= AV_DRIFT_TO_PENALIZE_MS ||
            record.maximumThermalStatus >= THERMAL_STATUS_SEVERE ||
            dropRate >= DROPPED_FRAMES_PER_SECOND_TO_PENALIZE
        ) {
            YLearnedRouteAdvice.Penalize
        } else {
            YLearnedRouteAdvice.Allow
        }
    }
}

private fun Int.saturatedIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1

private fun Long.saturatedAdd(value: Long): Long =
    if (value > 0L && this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

private const val FAILURES_TO_AVOID = 3
private const val QUALITY_ATTEMPTS_TO_AVOID = 3
private const val UNDERRUNS_TO_PENALIZE = 3L
private const val UNDERRUNS_TO_AVOID = 12L
private const val CODEC_RESETS_TO_AVOID = 3L
private const val AV_DRIFT_TO_PENALIZE_MS = 250L
private const val AV_DRIFT_TO_AVOID_MS = 1_000L
private const val DROPPED_FRAMES_PER_SECOND_TO_PENALIZE = 1.0
private const val DROPPED_FRAMES_PER_SECOND_TO_AVOID = 3.0
private const val QUALITY_DURATION_TO_AVOID_MS = 180_000L
// Mirrors Android's stable PowerManager.THERMAL_STATUS_SEVERE integer without making common code
// depend on the Android SDK. A severe route is penalized immediately and avoided after the same
// three-observation confidence gate used by the other quality signals.
private const val THERMAL_STATUS_SEVERE = 3
