package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import android.util.Base64
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.learning.YPlaybackLearningRecord
import com.yfuse.core2.learning.YPlaybackLearningStore

/** SharedPreferences persistence scoped to the exact Android system image and privacy-safe facts. */
internal class AndroidYPlaybackLearningStore(
    context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : YPlaybackLearningStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val systemImage = "${Build.VERSION.SDK_INT}:${Build.FINGERPRINT.hashCode()}"

    @Synchronized
    override fun load(): List<YPlaybackLearningRecord> {
        val oldest = (nowEpochMs() - RECORD_TTL_MS).coerceAtLeast(0L)
        val stored = preferences.getStringSet(KEY_RECORDS, emptySet()).orEmpty()
        val records =
            stored
                .mapNotNull(::decode)
                .filter { it.updatedAtEpochMs >= oldest }
                .sortedByDescending(YPlaybackLearningRecord::updatedAtEpochMs)
                .take(MAX_RECORDS)
        val canonical = records.mapTo(linkedSetOf(), ::encode)
        if (canonical != stored) persist(records)
        return records
    }

    @Synchronized
    override fun replace(records: List<YPlaybackLearningRecord>) {
        persist(records.sortedByDescending(YPlaybackLearningRecord::updatedAtEpochMs).take(MAX_RECORDS))
    }

    private fun persist(records: List<YPlaybackLearningRecord>) {
        preferences.edit().putStringSet(KEY_RECORDS, records.mapTo(linkedSetOf(), ::encode)).commit()
    }

    private fun encode(record: YPlaybackLearningRecord): String =
        listOf(
            VERSION,
            systemImage,
            record.key.route.name,
            record.key.container.name,
            record.key.videoCodec.name,
            record.key.hdrType.name,
            record.key.decoderName
                .orEmpty()
                .encodeOpaqueForLearning(),
            record.attempts,
            record.successfulAttempts,
            record.consecutiveFailures,
            record.playedDurationMs,
            record.droppedFrames,
            record.codecResets,
            record.audioUnderruns,
            record.maximumAbsoluteAvDriftMs,
            record.maximumThermalStatus,
            record.batteryDeltaPermille,
            record.updatedAtEpochMs,
        ).joinToString(SEPARATOR)

    private fun decode(encoded: String): YPlaybackLearningRecord? =
        runCatching {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == FIELD_COUNT && fields[0] == VERSION && fields[1] == systemImage)
            YPlaybackLearningRecord(
                key =
                    YPlaybackLearningKey(
                        route = enumValueOf<YPlaybackRoute>(fields[2]),
                        container = enumValueOf<YContainer>(fields[3]),
                        videoCodec = enumValueOf<YVideoCodec>(fields[4]),
                        hdrType = enumValueOf<YHdrType>(fields[5]),
                        decoderName = fields[6].decodeOpaqueForLearning().takeIf(String::isNotEmpty),
                    ),
                attempts = fields[7].toInt(),
                successfulAttempts = fields[8].toInt(),
                consecutiveFailures = fields[9].toInt(),
                playedDurationMs = fields[10].toLong(),
                droppedFrames = fields[11].toLong(),
                codecResets = fields[12].toLong(),
                audioUnderruns = fields[13].toLong(),
                maximumAbsoluteAvDriftMs = fields[14].toLong(),
                maximumThermalStatus = fields[15].toInt(),
                batteryDeltaPermille = fields[16].toLong(),
                updatedAtEpochMs = fields[17].toLong(),
            )
        }.getOrNull()
}

private fun String.encodeOpaqueForLearning(): String =
    Base64.encodeToString(
        toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

private fun String.decodeOpaqueForLearning(): String =
    Base64
        .decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        .toString(Charsets.UTF_8)

private const val PREFERENCES_NAME = "yfuse_ycore2_playback_learning"
private const val KEY_RECORDS = "route_metrics_v1"
private const val VERSION = "1"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 18
private const val MAX_RECORDS = 128
private const val RECORD_TTL_MS = 90L * 24L * 60L * 60L * 1_000L
