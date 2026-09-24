package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YRenderPath
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

internal enum class YRuntimeCapabilityEvidence {
    Configured,
    Rendered,
    Rejected,
}

internal data class YRuntimeVideoCapabilityKey(
    val decoderName: String,
    val codec: YVideoCodec,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val hdrType: YHdrType,
    val dolbyVisionProfile: Int?,
    val tunneled: Boolean,
)

internal data class YRuntimeCapabilityRecord(
    val key: YRuntimeVideoCapabilityKey,
    val evidence: YRuntimeCapabilityEvidence,
    val consecutiveFailures: Int,
    val updatedAtEpochMs: Long,
)

internal fun updateRuntimeCapabilityRecord(
    existing: YRuntimeCapabilityRecord?,
    key: YRuntimeVideoCapabilityKey,
    evidence: YRuntimeCapabilityEvidence,
    nowEpochMs: Long,
): YRuntimeCapabilityRecord =
    when (evidence) {
        YRuntimeCapabilityEvidence.Configured ->
            if (existing?.evidence == YRuntimeCapabilityEvidence.Rendered) {
                existing.copy(updatedAtEpochMs = nowEpochMs)
            } else {
                YRuntimeCapabilityRecord(key, evidence, consecutiveFailures = 0, nowEpochMs)
            }
        YRuntimeCapabilityEvidence.Rendered ->
            YRuntimeCapabilityRecord(key, evidence, consecutiveFailures = 0, nowEpochMs)
        YRuntimeCapabilityEvidence.Rejected ->
            YRuntimeCapabilityRecord(
                key = key,
                evidence = evidence,
                consecutiveFailures =
                    if (existing?.evidence == YRuntimeCapabilityEvidence.Rejected) {
                        (existing.consecutiveFailures + 1).coerceAtMost(Int.MAX_VALUE)
                    } else {
                        1
                    },
                updatedAtEpochMs = nowEpochMs,
            )
    }

/**
 * Whether [failure] proves that the planned decoder refused this video format.
 *
 * Only an exception MediaCodec itself raised while the video node created, configured or started
 * the decoder counts; the node reports exactly those as [YVideoDecoderConfigurationException].
 * 1.0.83 counted every failure before the decoder was configured. In one Dolby Vision Profile 5
 * tap our own hvcC parser threw "HEVC configuration contains no SPS" on the first attempt and on
 * the same-route retry: two strikes rejected c2.dolby.decoder.hevc for every same-shape title for
 * 30 days, the planner removed it and nothing ever re-validated it. Format building, the GPU output
 * and resource contention (a transient or recoverable CodecException) prove nothing about the
 * decoder, and neither does a Dolby Vision lookup that found no candidate to try.
 */
internal fun Throwable.isRuntimeDecoderRejection(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is YVideoDecoderConfigurationException) {
            return current.failures.isNotEmpty() &&
                current.failures.none { it.transient || it.recoverable }
        }
        current = current.cause
    }
    return false
}

/** Hands [key] to [recordRejected] only for a genuine decoder rejection; returns whether it did. */
internal fun recordRuntimeConfigureFailure(
    key: YRuntimeVideoCapabilityKey,
    failure: Throwable,
    recordRejected: (YRuntimeVideoCapabilityKey) -> Unit,
): Boolean {
    if (!failure.isRuntimeDecoderRejection()) return false
    recordRejected(key)
    return true
}

/**
 * How long one kind of evidence stays authoritative.
 *
 * Positive evidence is renewed by every successful playback. A rejection removes the decoder from
 * planning, so nothing renews or disproves it, and a wrong one used to hold for 30 days. It now
 * expires after a few days and the decoder is tried again.
 */
internal fun runtimeCapabilityEvidenceTtlMs(evidence: YRuntimeCapabilityEvidence): Long =
    when (evidence) {
        YRuntimeCapabilityEvidence.Configured,
        YRuntimeCapabilityEvidence.Rendered,
        -> POSITIVE_EVIDENCE_TTL_MS
        YRuntimeCapabilityEvidence.Rejected -> REJECTED_EVIDENCE_TTL_MS
    }

internal fun runtimeCapabilityRecordLive(
    record: YRuntimeCapabilityRecord,
    nowEpochMs: Long,
): Boolean {
    val oldest = (nowEpochMs - runtimeCapabilityEvidenceTtlMs(record.evidence)).coerceAtLeast(0L)
    return record.updatedAtEpochMs >= oldest
}

/** Decodes the stored set, dropping other system images, older record versions and expired evidence. */
internal fun liveRuntimeCapabilityRecords(
    stored: Set<String>,
    systemImage: String,
    nowEpochMs: Long,
): List<YRuntimeCapabilityRecord> =
    stored
        .mapNotNull { decodeRuntimeCapabilityRecord(it, systemImage) }
        .filter { runtimeCapabilityRecordLive(it, nowEpochMs) }
        .sortedByDescending(YRuntimeCapabilityRecord::updatedAtEpochMs)
        .take(MAX_RECORDS)

internal fun encodeRuntimeCapabilityRecord(
    record: YRuntimeCapabilityRecord,
    systemImage: String,
): String =
    listOf(
        RECORD_VERSION,
        systemImage,
        record.key.decoderName.encodeOpaque(),
        record.key.codec.name,
        record.key.width,
        record.key.height,
        record.key.bitDepth,
        record.key.hdrType.name,
        record.key.dolbyVisionProfile
            ?.toString()
            .orEmpty(),
        record.key.tunneled,
        record.evidence.name,
        record.consecutiveFailures,
        record.updatedAtEpochMs,
    ).joinToString(SEPARATOR)

/**
 * Null for anything this build cannot trust, including every record of an older [RECORD_VERSION].
 *
 * Version 1 records are dropped wholesale: 1.0.83 wrote rejections for failures that were not the
 * decoder's, and a stored record does not say which of its strikes were genuine.
 */
internal fun decodeRuntimeCapabilityRecord(
    encoded: String,
    systemImage: String,
): YRuntimeCapabilityRecord? =
    runCatching {
        val fields = encoded.split(SEPARATOR)
        require(fields.size == FIELD_COUNT && fields[0] == RECORD_VERSION && fields[1] == systemImage)
        YRuntimeCapabilityRecord(
            key =
                YRuntimeVideoCapabilityKey(
                    decoderName = fields[2].decodeOpaque(),
                    codec = enumValueOf<YVideoCodec>(fields[3]),
                    width = fields[4].toInt(),
                    height = fields[5].toInt(),
                    bitDepth = fields[6].toInt(),
                    hdrType = enumValueOf<YHdrType>(fields[7]),
                    dolbyVisionProfile = fields[8].takeIf(String::isNotEmpty)?.toInt(),
                    tunneled = fields[9].toBooleanStrict(),
                ),
            evidence = enumValueOf<YRuntimeCapabilityEvidence>(fields[10]),
            consecutiveFailures = fields[11].toInt(),
            updatedAtEpochMs = fields[12].toLong(),
        )
    }.getOrNull()

internal class AndroidRuntimeCapabilityRegistry(
    context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val systemImage = "${Build.VERSION.SDK_INT}:${Build.FINGERPRINT.hashCode()}"

    @Synchronized
    fun isRejected(key: YRuntimeVideoCapabilityKey): Boolean {
        val record = activeRecords().firstOrNull { it.key == key } ?: return false
        return record.evidence == YRuntimeCapabilityEvidence.Rejected &&
            record.consecutiveFailures >= FAILURES_TO_REJECT
    }

    @Synchronized
    fun evidence(key: YRuntimeVideoCapabilityKey): YRuntimeCapabilityEvidence? =
        activeRecords().firstOrNull { it.key == key }?.evidence

    fun recordConfigured(key: YRuntimeVideoCapabilityKey) = record(key, YRuntimeCapabilityEvidence.Configured)

    fun recordRendered(key: YRuntimeVideoCapabilityKey) = record(key, YRuntimeCapabilityEvidence.Rendered)

    /** For a verdict already known to be the decoder's own, such as an active codec probe's. */
    fun recordRejected(key: YRuntimeVideoCapabilityKey) = record(key, YRuntimeCapabilityEvidence.Rejected)

    /**
     * Counts a playback-time video configure failure as a strike only when MediaCodec raised it
     * (see [isRuntimeDecoderRejection]). Returns whether a strike was recorded.
     */
    fun recordConfigureFailure(
        key: YRuntimeVideoCapabilityKey,
        failure: Throwable,
    ): Boolean = recordRuntimeConfigureFailure(key, failure, ::recordRejected)

    @Synchronized
    private fun record(
        key: YRuntimeVideoCapabilityKey,
        evidence: YRuntimeCapabilityEvidence,
    ) {
        val records = activeRecords()
        val updated =
            updateRuntimeCapabilityRecord(
                existing = records.firstOrNull { it.key == key },
                key = key,
                evidence = evidence,
                nowEpochMs = nowEpochMs().coerceAtLeast(0L),
            )
        persist(
            records
                .filterNot { it.key == key }
                .plus(updated)
                .sortedByDescending(YRuntimeCapabilityRecord::updatedAtEpochMs)
                .take(MAX_RECORDS),
        )
    }

    private fun activeRecords(): List<YRuntimeCapabilityRecord> {
        val stored = preferences.getStringSet(KEY_RECORDS, emptySet()).orEmpty()
        val records = liveRuntimeCapabilityRecords(stored, systemImage, nowEpochMs())
        // Rewriting also removes expired, foreign-image and older-version records from storage.
        val canonical = records.mapTo(linkedSetOf()) { encodeRuntimeCapabilityRecord(it, systemImage) }
        if (canonical != stored) persist(records)
        return records
    }

    private fun persist(records: List<YRuntimeCapabilityRecord>) {
        val encoded = records.mapTo(linkedSetOf()) { encodeRuntimeCapabilityRecord(it, systemImage) }
        preferences.edit().putStringSet(KEY_RECORDS, encoded).apply()
    }
}

internal fun runtimeVideoCapabilityKey(
    request: YPlaybackRequest,
    plan: YPlaybackPlan,
): YRuntimeVideoCapabilityKey? {
    val decoderName = plan.decoderName?.takeIf(String::isNotBlank) ?: return null
    return YRuntimeVideoCapabilityKey(
        decoderName = decoderName,
        codec = request.video.codec,
        width = request.video.width,
        height = request.video.height,
        bitDepth = request.video.bitDepth,
        hdrType = plan.inputHdrType,
        dolbyVisionProfile = request.video.dolbyVisionProfile.takeUnless { plan.usesHdrFallback },
        tunneled = plan.renderPath == YRenderPath.Tunnel,
    )
}

// Same output as android.util.Base64 URL_SAFE | NO_WRAP | NO_PADDING, and runnable in JVM tests.
private fun String.encodeOpaque(): String =
    Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray(Charsets.UTF_8))

private fun String.decodeOpaque(): String = Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)

private const val PREFERENCES_NAME = "yfuse_ycore2_runtime_capabilities"
private const val KEY_RECORDS = "video_records_v1"

/** "1" until parser and GPU-output failures stopped counting as decoder rejections. */
private const val RECORD_VERSION = "2"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 13
private const val FAILURES_TO_REJECT = 2
private const val MAX_RECORDS = 128
private const val POSITIVE_EVIDENCE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
private const val REJECTED_EVIDENCE_TTL_MS = 3L * 24L * 60L * 60L * 1_000L
