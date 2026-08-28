package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import android.util.Base64
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.render.NATIVE_GPU_API_VERSION
import com.yfuse.core2.render.YNativeGpuRuntimeProbe
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest

internal data class YCoreGpuEvidenceKey(
    val decoderName: String,
    val resolutionClass: String,
    val bitDepth: Int,
    val inputHdrType: YHdrType,
    val outputHdrType: YHdrType,
    val dolbyVisionProfile: Int?,
)

/** Persists only exact driver/decoder/format Vulkan measurements; no media identity enters it. */
internal class AndroidYCoreGpuEvidenceStore(
    context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val driverImage =
        listOf(
            Build.VERSION.SDK_INT,
            Build.FINGERPRINT.hashCode(),
            Build.HARDWARE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "legacy-soc",
        ).joinToString(":")

    fun verifiedFeatureMask(key: YCoreGpuEvidenceKey?): Long {
        if (key == null) return 0L
        return records().firstOrNull { it.key == key }?.featureMask ?: 0L
    }

    fun recordVerified(
        key: YCoreGpuEvidenceKey?,
        featureMask: Long,
    ) {
        if (key == null) return
        val probe =
            YNativeGpuRuntimeProbe(
                platformApiLevel = Build.VERSION.SDK_INT,
                nativeApiVersion = NATIVE_GPU_API_VERSION,
                featureMask = featureMask,
            )
        if (!probe.canClaimNativeVulkan) return
        val updated =
            records()
                .filterNot { it.key == key }
                .plus(Record(key, featureMask, nowEpochMs().coerceAtLeast(0L)))
                .sortedByDescending(Record::updatedAtEpochMs)
                .take(MAX_RECORDS)
        preferences.edit().putStringSet(KEY_RECORDS, updated.mapTo(linkedSetOf(), ::encode)).apply()
    }

    private fun records(): List<Record> {
        val oldest = (nowEpochMs() - EVIDENCE_TTL_MS).coerceAtLeast(0L)
        return preferences.getStringSet(KEY_RECORDS, emptySet()).orEmpty()
            .mapNotNull(::decode)
            .filter { it.updatedAtEpochMs >= oldest }
            .sortedByDescending(Record::updatedAtEpochMs)
            .take(MAX_RECORDS)
    }

    private fun encode(record: Record): String =
        listOf(
            VERSION,
            driverImage.encodeOpaque(),
            record.key.decoderName.encodeOpaque(),
            record.key.resolutionClass,
            record.key.bitDepth,
            record.key.inputHdrType.name,
            record.key.outputHdrType.name,
            record.key.dolbyVisionProfile?.toString().orEmpty(),
            record.featureMask,
            record.updatedAtEpochMs,
        ).joinToString(SEPARATOR)

    private fun decode(value: String): Record? =
        runCatching {
            val fields = value.split(SEPARATOR)
            require(fields.size == FIELD_COUNT && fields[0] == VERSION)
            require(fields[1].decodeOpaque() == driverImage)
            Record(
                key =
                    YCoreGpuEvidenceKey(
                        decoderName = fields[2].decodeOpaque(),
                        resolutionClass = fields[3],
                        bitDepth = fields[4].toInt(),
                        inputHdrType = enumValueOf<YHdrType>(fields[5]),
                        outputHdrType = enumValueOf<YHdrType>(fields[6]),
                        dolbyVisionProfile = fields[7].takeIf(String::isNotEmpty)?.toInt(),
                    ),
                featureMask = fields[8].toLong(),
                updatedAtEpochMs = fields[9].toLong(),
            )
        }.getOrNull()

    private data class Record(
        val key: YCoreGpuEvidenceKey,
        val featureMask: Long,
        val updatedAtEpochMs: Long,
    )
}

internal fun yCoreGpuEvidenceKey(
    request: YPlaybackRequest,
    plan: YPlaybackPlan,
): YCoreGpuEvidenceKey? =
    plan.decoderName?.takeIf(String::isNotBlank)?.let { decoder ->
        YCoreGpuEvidenceKey(
            decoderName = decoder,
            resolutionClass =
                when {
                    request.video.width > 3_840 || request.video.height > 2_160 -> "above-4k"
                    request.video.width > 2_560 || request.video.height > 1_440 -> "4k"
                    request.video.width > 1_920 || request.video.height > 1_080 -> "1440p"
                    else -> "1080p-or-below"
                },
            bitDepth = request.video.bitDepth,
            inputHdrType = plan.inputHdrType,
            outputHdrType = plan.outputHdrType,
            dolbyVisionProfile = request.video.dolbyVisionProfile.takeUnless { plan.usesHdrFallback },
        )
    }

internal fun YRuntimeVideoCapabilityKey.toGpuEvidenceKey(plan: YPlaybackPlan): YCoreGpuEvidenceKey =
    YCoreGpuEvidenceKey(
        decoderName = decoderName,
        resolutionClass =
            when {
                width > 3_840 || height > 2_160 -> "above-4k"
                width > 2_560 || height > 1_440 -> "4k"
                width > 1_920 || height > 1_080 -> "1440p"
                else -> "1080p-or-below"
            },
        bitDepth = bitDepth,
        inputHdrType = plan.inputHdrType,
        outputHdrType = plan.outputHdrType,
        dolbyVisionProfile = dolbyVisionProfile,
    )

private fun String.encodeOpaque(): String =
    Base64.encodeToString(toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun String.decodeOpaque(): String =
    Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).decodeToString()

private const val PREFERENCES_NAME = "ycore_gpu_measurement_v3"
private const val KEY_RECORDS = "records"
private const val VERSION = "3"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 10
private const val MAX_RECORDS = 128
private const val EVIDENCE_TTL_MS = 30L * 24L * 60L * 60L * 1_000L
