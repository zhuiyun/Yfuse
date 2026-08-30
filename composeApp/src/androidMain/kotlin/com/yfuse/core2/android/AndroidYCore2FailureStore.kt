package com.yfuse.core2.android

import android.content.Context
import android.util.Base64
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.quirk.YCore2FailureRecord
import com.yfuse.core2.quirk.YCore2FailureStore

/**
 * Device-local persistence for Core2 route quirks.
 *
 * Records contain only capability facts and decoder names. No provider id, title, URI, server,
 * account id or auth material is stored. Malformed/stale records fail closed by being discarded.
 */
internal class AndroidYCore2FailureStore(
    context: Context,
) : YCore2FailureStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): List<YCore2FailureRecord> =
        preferences
            .getStringSet(KEY_RECORDS, emptySet())
            .orEmpty()
            .mapNotNull(::decode)

    override fun replace(records: List<YCore2FailureRecord>) {
        preferences.edit().putStringSet(KEY_RECORDS, records.mapTo(linkedSetOf(), ::encode)).apply()
    }

    private fun encode(record: YCore2FailureRecord): String =
        listOf(
            VERSION,
            record.key.route.name,
            record.key.container.name,
            record.key.videoCodec.name,
            record.key.hdrType.name,
            record.key.dolbyVisionProfile
                ?.toString()
                .orEmpty(),
            record.key.decoderName.encodeOpaque(),
            record.category.name,
            record.firstSeenEpochMs.toString(),
            record.lastSeenEpochMs.toString(),
            record.failureCount.toString(),
            record.blockedUntilEpochMs.toString(),
        ).joinToString(SEPARATOR)

    private fun decode(encoded: String): YCore2FailureRecord? =
        runCatching {
            val fields = encoded.split(SEPARATOR)
            require(fields.size == FIELD_COUNT && fields[0] == VERSION)
            YCore2FailureRecord(
                key =
                    YCore2FailureKey(
                        route = enumValueOf<YPlaybackRoute>(fields[1]),
                        container = enumValueOf<YContainer>(fields[2]),
                        videoCodec = enumValueOf<YVideoCodec>(fields[3]),
                        hdrType = enumValueOf<YHdrType>(fields[4]),
                        dolbyVisionProfile = fields[5].takeIf(String::isNotEmpty)?.toInt(),
                        decoderName = fields[6].decodeOpaque(),
                    ),
                category = enumValueOf<YPlaybackFailureCategory>(fields[7]),
                firstSeenEpochMs = fields[8].toLong(),
                lastSeenEpochMs = fields[9].toLong(),
                failureCount = fields[10].toInt(),
                blockedUntilEpochMs = fields[11].toLong(),
            )
        }.getOrNull()
}

internal fun YCore2RouteDecision.toFailureKey(): YCore2FailureKey =
    YCore2FailureKey(
        route = plan.route,
        container = probe.playbackRequest.container,
        videoCodec = probe.playbackRequest.video.codec,
        hdrType = probe.playbackRequest.video.hdrType,
        dolbyVisionProfile = probe.playbackRequest.video.dolbyVisionProfile,
        decoderName = plan.decoderName,
    )

private fun String?.encodeOpaque(): String =
    this
        ?.toByteArray(Charsets.UTF_8)
        ?.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        .orEmpty()

private fun String.decodeOpaque(): String? {
    if (isEmpty()) return null
    return Base64
        .decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        .toString(Charsets.UTF_8)
        .takeIf(String::isNotEmpty)
}

private const val PREFERENCES_NAME = "yfuse_ycore2_failure_ledger"

// Transport/probe semantics changed in 1.0.13. Old deterministic failures must not suppress the
// corrected route before it gets one real attempt on the device.
private const val KEY_RECORDS = "records_v2"
private const val VERSION = "2"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 12
