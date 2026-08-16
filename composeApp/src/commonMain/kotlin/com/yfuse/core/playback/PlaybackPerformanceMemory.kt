package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine
import kotlin.math.roundToInt

data class PlaybackPerformanceRecord(
    val signature: String,
    val engine: PlayerEngine,
    val sessions: Int,
    val averageStartupMs: Long,
    val averageRebufferEventsPerMinute: Float,
    val averageDroppedFramesPerMinute: Float,
    val lastObservedEpochMs: Long,
)

/** Bounded, privacy-safe device benchmark memory used to rank otherwise equivalent engines. */
class PlaybackPerformanceMemory(
    private val maxRecords: Int = DEFAULT_MAX_PERFORMANCE_RECORDS,
    private val ttlMs: Long = DEFAULT_PERFORMANCE_TTL_MS,
    private val nowEpochMs: () -> Long = { 0L },
    initialRecords: List<PlaybackPerformanceRecord> = emptyList(),
    private val onChanged: (List<PlaybackPerformanceRecord>) -> Unit = {},
) {
    private val records = linkedMapOf<Pair<String, PlayerEngine>, PlaybackPerformanceRecord>()

    init {
        initialRecords
            .mapNotNull(::normalize)
            .sortedBy(PlaybackPerformanceRecord::lastObservedEpochMs)
            .takeLast(maxRecords.coerceAtLeast(1))
            .forEach { record -> records[record.signature to record.engine] = record }
        pruneExpired(persist = false)
    }

    @Synchronized
    fun record(
        signature: String,
        engine: PlayerEngine,
        assessment: PlaybackHealthAssessment,
    ) {
        if (!assessment.evaluationReady || engine !in PlayerEngine.selectable) return
        val normalizedSignature = signature.normalizedSignature() ?: return
        val startupMs = assessment.startupTimeMs ?: return
        val observedMs = assessment.observedPlaybackMs.coerceAtLeast(1L)
        val rebufferRate = assessment.rebufferEvents * 60_000f / observedMs
        val now = nowEpochMs().coerceAtLeast(1L)
        pruneExpired(persist = false, now = now)
        val key = normalizedSignature to engine
        val previous = records.remove(key)
        val previousWeight = previous?.sessions?.coerceIn(0, MAX_SESSION_WEIGHT) ?: 0
        val record =
            PlaybackPerformanceRecord(
                signature = normalizedSignature,
                engine = engine,
                sessions = ((previous?.sessions ?: 0) + 1).coerceAtMost(MAX_STORED_SESSIONS),
                averageStartupMs = weightedAverage(previous?.averageStartupMs, startupMs, previousWeight),
                averageRebufferEventsPerMinute =
                    weightedAverage(
                        previous?.averageRebufferEventsPerMinute,
                        rebufferRate,
                        previousWeight,
                    ),
                averageDroppedFramesPerMinute =
                    weightedAverage(
                        previous?.averageDroppedFramesPerMinute,
                        assessment.droppedFramesPerMinute,
                        previousWeight,
                    ),
                lastObservedEpochMs = now,
            )
        records[key] = record
        while (records.size > maxRecords.coerceAtLeast(1)) {
            records.remove(records.keys.first())
        }
        persist()
    }

    /** Higher values are worse; fewer than two completed sessions cannot alter routing. */
    @Synchronized
    fun engineCosts(signature: String): Map<PlayerEngine, Int> {
        val normalizedSignature = signature.normalizedSignature() ?: return emptyMap()
        pruneExpired(persist = true)
        return records.values
            .filter { it.signature == normalizedSignature && it.sessions >= MIN_RANKING_SESSIONS }
            .associate { record -> record.engine to record.cost() }
            .filterValues { it > 0 }
    }

    @Synchronized
    fun snapshot(): List<PlaybackPerformanceRecord> {
        pruneExpired(persist = true)
        return records.values.toList()
    }

    /** Clears all benchmarks, or only one credential-free capability signature. */
    @Synchronized
    fun clear(signature: String? = null) {
        if (signature == null) {
            records.clear()
        } else {
            val normalizedSignature = signature.normalizedSignature() ?: return
            records.keys.removeAll { (recordSignature, _) -> recordSignature == normalizedSignature }
        }
        persist()
    }

    @Synchronized
    fun diagnosticLabel(signature: String): String {
        val normalizedSignature = signature.normalizedSignature() ?: return "尚无完整样本"
        pruneExpired(persist = true)
        val matching = records.values.filter { it.signature == normalizedSignature }
        if (matching.isEmpty()) return "尚无完整样本"
        return matching
            .sortedBy(PlaybackPerformanceRecord::engine)
            .joinToString("；") { record ->
                buildString {
                    append(record.engine.label)
                    append(" ")
                    append(record.sessions)
                    append("次 · 首帧")
                    append(record.averageStartupMs)
                    append("ms · 丢帧")
                    append(record.averageDroppedFramesPerMinute.roundToInt())
                    append("/分")
                }
            }
    }

    private fun normalize(record: PlaybackPerformanceRecord): PlaybackPerformanceRecord? {
        val signature = record.signature.normalizedSignature() ?: return null
        if (
            record.engine !in PlayerEngine.selectable ||
            record.sessions <= 0 ||
            record.lastObservedEpochMs <= 0L ||
            !record.averageRebufferEventsPerMinute.isFinite() ||
            !record.averageDroppedFramesPerMinute.isFinite()
        ) {
            return null
        }
        return record.copy(
            signature = signature,
            sessions = record.sessions.coerceAtMost(MAX_STORED_SESSIONS),
            averageStartupMs = record.averageStartupMs.coerceIn(0L, MAX_STARTUP_MS),
            averageRebufferEventsPerMinute =
                record.averageRebufferEventsPerMinute.coerceIn(0f, MAX_RATE_PER_MINUTE),
            averageDroppedFramesPerMinute =
                record.averageDroppedFramesPerMinute.coerceIn(0f, MAX_RATE_PER_MINUTE),
        )
    }

    private fun pruneExpired(
        persist: Boolean,
        now: Long = nowEpochMs().coerceAtLeast(1L),
    ) {
        val changed =
            records.entries.removeAll { (_, record) ->
                now - record.lastObservedEpochMs > ttlMs.coerceAtLeast(1L)
            }
        if (changed && persist) persist()
    }

    private fun persist() = onChanged(records.values.toList())
}

private fun PlaybackPerformanceRecord.cost(): Int =
    when {
        averageDroppedFramesPerMinute >= 30f -> 10
        averageDroppedFramesPerMinute >= 10f -> 6
        averageRebufferEventsPerMinute >= 4f -> 5
        averageStartupMs >= 12_000L -> 4
        averageRebufferEventsPerMinute >= 2f || averageStartupMs >= 5_000L -> 2
        else -> 0
    }

private fun weightedAverage(
    previous: Long?,
    sample: Long,
    previousWeight: Int,
): Long =
    if (previous == null || previousWeight <= 0) {
        sample
    } else {
        (previous * previousWeight + sample) / (previousWeight + 1)
    }

private fun weightedAverage(
    previous: Float?,
    sample: Float,
    previousWeight: Int,
): Float =
    if (previous == null || previousWeight <= 0) {
        sample
    } else {
        (previous * previousWeight + sample) / (previousWeight + 1)
    }

private fun String.normalizedSignature(): String? =
    trim().take(MAX_PERFORMANCE_SIGNATURE_CHARS).takeIf(String::isNotEmpty)

private const val DEFAULT_MAX_PERFORMANCE_RECORDS = 96
private const val DEFAULT_PERFORMANCE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
private const val MAX_PERFORMANCE_SIGNATURE_CHARS = 320
private const val MAX_SESSION_WEIGHT = 20
private const val MAX_STORED_SESSIONS = 1_000
private const val MIN_RANKING_SESSIONS = 2
private const val MAX_STARTUP_MS = 120_000L
private const val MAX_RATE_PER_MINUTE = 10_000f
