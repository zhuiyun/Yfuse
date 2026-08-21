package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import android.util.Base64
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YRenderPath

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

    fun recordRejected(key: YRuntimeVideoCapabilityKey) = record(key, YRuntimeCapabilityEvidence.Rejected)

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
        val oldest = (nowEpochMs() - EVIDENCE_TTL_MS).coerceAtLeast(0L)
        val stored = preferences.getStringSet(KEY_RECORDS, emptySet()).orEmpty()
        val records =
            stored
                .mapNotNull(::decode)
                .filter { it.updatedAtEpochMs >= oldest }
                .sortedByDescending(YRuntimeCapabilityRecord::updatedAtEpochMs)
                .take(MAX_RECORDS)
        val canonical = records.mapTo(linkedSetOf(), ::encode)
        if (canonical != stored) persist(records)
        return records
    }

    private fun persist(records: List<YRuntimeCapabilityRecord>) {
        preferences.edit().putStringSet(KEY_RECORDS, records.mapTo(linkedSetOf(), ::encode)).apply()
    }

    private fun encode(record: YRuntimeCapabilityRecord): String =
        listOf(
            VERSION,
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

    private fun decode(encoded: String): YRuntimeCapabilityRecord? =
        runCatching {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == FIELD_COUNT && fields[0] == VERSION && fields[1] == systemImage)
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
        hdrType = plan.outputHdrType,
        dolbyVisionProfile = request.video.dolbyVisionProfile,
        tunneled = plan.renderPath == YRenderPath.Tunnel,
    )
}

private fun String.encodeOpaque(): String =
    Base64.encodeToString(
        toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

private fun String.decodeOpaque(): String =
    Base64
        .decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        .toString(Charsets.UTF_8)

private const val PREFERENCES_NAME = "yfuse_ycore2_runtime_capabilities"
private const val KEY_RECORDS = "video_records_v1"
private const val VERSION = "1"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 13
private const val FAILURES_TO_REJECT = 2
private const val MAX_RECORDS = 128
private const val EVIDENCE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
