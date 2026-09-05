package com.yfuse.core2.android

import android.content.Context
import android.os.Build
import android.util.Base64
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.strategy.YPlaybackRequest

/**
 * The probe result the device has since proven on screen, keyed by media identity.
 *
 * A cold start of a title paid the platform probe, the FFmpeg truth probe and the codec sample
 * probe before the child player opened the source a second time; a replay paid all of it again
 * because the session-scoped probe caches died with the player. Once a route has rendered a
 * verified frame for this exact media, the facts the probes established do not change: the
 * container, the tracks, the Dolby Vision configuration. They are recorded here so the next start
 * of the same media hands the evaluator a finished probe and goes straight to planning.
 *
 * What is stored is a description of the media and of nothing else: no URI, no headers, no
 * server, no title. The key is the credential-free [com.yfuse.core2.network.YCacheIdentity]; media
 * that has no such identity is never remembered. Records are bound to the system image and expire,
 * like the runtime decoder evidence they sit beside, and a failure on the remembered route forgets
 * it so the next start probes again.
 */
internal class AndroidYCoreVerifiedRouteMemory(
    context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val systemImage: String = "${Build.VERSION.SDK_INT}:${Build.FINGERPRINT.hashCode()}",
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun probeFor(item: YMediaItem): YCore2ProbeResult.Success? {
        val identity = item.verifiedRouteIdentity() ?: return null
        return activeRecords().firstOrNull { it.identity == identity }?.probe
    }

    @Synchronized
    fun recordVerified(
        item: YMediaItem,
        probe: YCore2ProbeResult.Success,
    ) {
        val identity = item.verifiedRouteIdentity() ?: return
        if (!probe.isPersistableAsVerifiedRoute()) return
        val record = YVerifiedRouteRecord(identity, probe, nowEpochMs().coerceAtLeast(0L))
        persist(
            activeRecords()
                .filterNot { it.identity == identity }
                .plus(record)
                .sortedByDescending(YVerifiedRouteRecord::verifiedAtEpochMs)
                .take(MAX_RECORDS),
        )
    }

    @Synchronized
    fun forget(item: YMediaItem) {
        val identity = item.verifiedRouteIdentity() ?: return
        val records = activeRecords()
        if (records.none { it.identity == identity }) return
        persist(records.filterNot { it.identity == identity })
    }

    private fun activeRecords(): List<YVerifiedRouteRecord> {
        val oldest = (nowEpochMs() - RECORD_TTL_MS).coerceAtLeast(0L)
        val stored = preferences.getStringSet(KEY_RECORDS, emptySet()).orEmpty()
        val records =
            stored
                .mapNotNull { decodeVerifiedRouteRecord(it, systemImage) }
                .filter { it.verifiedAtEpochMs >= oldest }
                .sortedByDescending(YVerifiedRouteRecord::verifiedAtEpochMs)
                .take(MAX_RECORDS)
        val canonical = records.mapTo(linkedSetOf()) { encodeVerifiedRouteRecord(it, systemImage) }
        if (canonical != stored) persist(records)
        return records
    }

    private fun persist(records: List<YVerifiedRouteRecord>) {
        preferences
            .edit()
            .putStringSet(KEY_RECORDS, records.mapTo(linkedSetOf()) { encodeVerifiedRouteRecord(it, systemImage) })
            .apply()
    }
}

internal data class YVerifiedRouteRecord(
    val identity: String,
    val probe: YCore2ProbeResult.Success,
    val verifiedAtEpochMs: Long,
)

/** The credential-free identity a record is keyed by; media without one is never remembered. */
internal fun YMediaItem.verifiedRouteIdentity(): String? =
    cacheIdentity?.let { "${it.scope}/${it.mediaId}/${it.version}" }

/**
 * Whether a probe describes the media completely enough to stand in for the probes next time.
 *
 * Profile 7 routing depends on enhancement-layer evidence gathered from the bitstream, which is
 * not stored; an unconfigured Dolby signal is a source the probes could not settle at all.
 */
internal fun YCore2ProbeResult.Success.isPersistableAsVerifiedRoute(): Boolean =
    !unconfiguredDolbyVisionSignal && dolbyVisionConfig?.profile != 7

internal fun encodeVerifiedRouteRecord(
    record: YVerifiedRouteRecord,
    systemImage: String,
): String {
    val probe = record.probe
    val request = probe.playbackRequest
    val video = request.video
    val audio = request.audio
    val dolby = probe.dolbyVisionConfig
    return listOf(
        VERSION,
        systemImage,
        record.identity.encodeOpaque(),
        record.verifiedAtEpochMs,
        request.container.name,
        video.codec.name,
        video.width,
        video.height,
        video.frameRate,
        video.bitDepth,
        video.hdrType.name,
        video.dolbyVisionProfile?.toString().orEmpty(),
        video.secureDecodeRequired,
        video.surfaceOutputRequired,
        audio?.codec?.name.orEmpty(),
        audio?.channelCount ?: 0,
        audio?.sampleRate ?: 0,
        request.audioOnly,
        request.platformDemuxSupported,
        request.enhancedDemuxSupported,
        request.fallbackHdrType?.name.orEmpty(),
        request.platformAudioDemuxSupported,
        request.sourceDeclaresAudio,
        probe.videoMime.encodeOpaque(),
        probe.audioMime?.encodeOpaque().orEmpty(),
        probe.durationMs,
        dolby?.versionMajor ?: -1,
        dolby?.versionMinor ?: -1,
        dolby?.profile ?: -1,
        dolby?.level ?: -1,
        dolby?.rpuPresent ?: false,
        dolby?.enhancementLayerPresent ?: false,
        dolby?.baseLayerPresent ?: false,
        dolby?.baseLayerCompatibilityId ?: -1,
        dolby?.metadataCompression ?: -1,
    ).joinToString(SEPARATOR)
}

internal fun decodeVerifiedRouteRecord(
    encoded: String,
    systemImage: String,
): YVerifiedRouteRecord? =
    runCatching {
        val fields = encoded.split(SEPARATOR)
        require(fields.size == FIELD_COUNT && fields[0] == VERSION && fields[1] == systemImage)
        val audioCodec = fields[14].takeIf(String::isNotEmpty)?.let { enumValueOf<YAudioCodec>(it) }
        val dolbyProfile = fields[28].toInt()
        YVerifiedRouteRecord(
            identity = fields[2].decodeOpaque(),
            verifiedAtEpochMs = fields[3].toLong(),
            probe =
                YCore2ProbeResult.Success(
                    playbackRequest =
                        YPlaybackRequest(
                            container = enumValueOf<YContainer>(fields[4]),
                            video =
                                YVideoRequirement(
                                    codec = enumValueOf<YVideoCodec>(fields[5]),
                                    width = fields[6].toInt(),
                                    height = fields[7].toInt(),
                                    frameRate = fields[8].toFloat(),
                                    bitDepth = fields[9].toInt(),
                                    hdrType = enumValueOf<YHdrType>(fields[10]),
                                    dolbyVisionProfile = fields[11].takeIf(String::isNotEmpty)?.toInt(),
                                    secureDecodeRequired = fields[12].toBooleanStrict(),
                                    surfaceOutputRequired = fields[13].toBooleanStrict(),
                                ),
                            audio =
                                audioCodec?.let {
                                    YAudioRequirement(
                                        codec = it,
                                        channelCount = fields[15].toInt(),
                                        sampleRate = fields[16].toInt(),
                                    )
                                },
                            audioOnly = fields[17].toBooleanStrict(),
                            platformDemuxSupported = fields[18].toBooleanStrict(),
                            enhancedDemuxSupported = fields[19].toBooleanStrict(),
                            fallbackHdrType = fields[20].takeIf(String::isNotEmpty)?.let { enumValueOf<YHdrType>(it) },
                            platformAudioDemuxSupported = fields[21].toBooleanStrict(),
                            sourceDeclaresAudio = fields[22].toBooleanStrict(),
                        ),
                    videoMime = fields[23].decodeOpaque(),
                    audioMime = fields[24].takeIf(String::isNotEmpty)?.decodeOpaque(),
                    durationMs = fields[25].toLong(),
                    dolbyVisionConfig =
                        if (dolbyProfile < 0) {
                            null
                        } else {
                            YDolbyVisionConfig(
                                versionMajor = fields[26].toInt(),
                                versionMinor = fields[27].toInt(),
                                profile = dolbyProfile,
                                level = fields[29].toInt(),
                                rpuPresent = fields[30].toBooleanStrict(),
                                enhancementLayerPresent = fields[31].toBooleanStrict(),
                                baseLayerPresent = fields[32].toBooleanStrict(),
                                baseLayerCompatibilityId = fields[33].toInt(),
                                metadataCompression = fields[34].toInt(),
                            )
                        },
                ),
        )
    }.getOrNull()

private fun String.encodeOpaque(): String =
    Base64.encodeToString(
        toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

private fun String.decodeOpaque(): String =
    Base64
        .decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        .toString(Charsets.UTF_8)

private const val PREFERENCES_NAME = "yfuse_ycore2_verified_routes"
private const val KEY_RECORDS = "records_v1"
private const val VERSION = "1"
private const val SEPARATOR = "\t"
private const val FIELD_COUNT = 35
private const val MAX_RECORDS = 64
private const val RECORD_TTL_MS = 30L * 24L * 60L * 60L * 1000L
