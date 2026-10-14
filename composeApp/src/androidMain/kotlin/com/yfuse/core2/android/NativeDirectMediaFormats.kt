package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.render.videoFrameRateHint
import com.yfuse.core2.subtitle.YSubtitleFormat
import java.nio.ByteBuffer

internal fun YMediaItem.toAndroidSource(): YAndroidMediaSource =
    YAndroidMediaSource(
        uri = uri,
        headers = headers,
        credentials = transportCredentials,
        bitrateBitsPerSecond = sourceHints?.bitrateBitsPerSecond ?: 0L,
        cacheIdentity = cacheIdentity,
        cacheMaximumBytes = cacheMaximumBytes,
    )

/**
 * Describes an audio track without throwing.
 *
 * Every failure here used to be swallowed by the caller's `runCatching { … }.getOrNull()`, which
 * dropped the track and left the session reporting that the media simply had no audio. An
 * unrecognised MIME therefore has to reach the capability layer as [YAudioCodec.Unknown] — the
 * convention `AndroidCore2MediaProbe` already follows — so the device probe decides there is no
 * output path and the caller's fail-closed guard reports a real reason. `toYAudioCodec` itself
 * keeps returning null for unmapped types because [AndroidYCapabilityProvider] uses it to build the
 * decoder capability set, where an Unknown entry would claim support for everything. An AAC label
 * with no AudioSpecificConfig is Unknown too: it is DTS or another codec in an `mp4a` entry, and the
 * AAC decoder would play it as silence ([aacLabelWithoutConfig]).
 *
 * Channel count and sample rate are read defensively for the same reason: `MediaFormat.getInteger`
 * throws when a container omits the key. Server probe metadata fills those gaps; a codec-aware,
 * valid AudioTrack geometry is the final fallback instead of the old 1 Hz/mono placeholder.
 */
internal fun MediaFormat.toCore2AudioTrackFormat(sourceHints: YMediaSourceHints? = null): YAudioTrackFormat {
    val mime = requireNotNull(getString(MediaFormat.KEY_MIME)).normalizedAudioMimeType()
    val profile = intOrZero(MediaFormat.KEY_PROFILE)
    val baseCodec = platformAudioCodec()
    val codec =
        when {
            baseCodec == YAudioCodec.Eac3 && profile == ATMOS_PROFILE -> YAudioCodec.Eac3Joc
            baseCodec == YAudioCodec.TrueHd && profile == ATMOS_PROFILE -> YAudioCodec.TrueHdAtmos
            else -> baseCodec
        }
    return YAudioTrackFormat(
        codec = codec,
        mimeType = mime,
        channelCount =
            resolveNativeDirectAudioChannelCount(
                codec = codec,
                extractedChannelCount = intOrZero(MediaFormat.KEY_CHANNEL_COUNT),
                sourceHintChannelCount = sourceHints?.audioChannelCount ?: 0,
                sourceAudioTrackCount = sourceHints?.audioTrackCount ?: 0,
            ),
        sampleRate =
            resolveNativeDirectAudioSampleRate(
                extractedSampleRateHz = intOrZero(MediaFormat.KEY_SAMPLE_RATE),
                sourceHintSampleRateHz = sourceHints?.audioSampleRateHz ?: 0,
                sourceAudioTrackCount = sourceHints?.audioTrackCount ?: 0,
            ),
    )
}

internal fun resolveNativeDirectAudioChannelCount(
    codec: YAudioCodec,
    extractedChannelCount: Int,
    sourceHintChannelCount: Int,
    sourceAudioTrackCount: Int = 1,
): Int =
    extractedChannelCount.takeIf { it > 0 }
        ?: sourceHintChannelCount.takeIf { it > 0 && sourceAudioTrackCount == 1 }
        ?: when (codec) {
            YAudioCodec.Ac3, YAudioCodec.Eac3, YAudioCodec.Eac3Joc, YAudioCodec.Dts -> 6
            YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos, YAudioCodec.DtsHd, YAudioCodec.DtsX -> 8
            else -> 2
        }

internal fun resolveNativeDirectAudioSampleRate(
    extractedSampleRateHz: Int,
    sourceHintSampleRateHz: Int,
    sourceAudioTrackCount: Int = 1,
): Int =
    extractedSampleRateHz.takeIf { it > 0 }
        ?: sourceHintSampleRateHz.takeIf { it > 0 && sourceAudioTrackCount == 1 }
        ?: DEFAULT_AUDIO_SAMPLE_RATE_HZ

internal fun MediaFormat.durationUsOrNull(): Long? =
    if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else null

private fun MediaFormat.intOrZero(key: String): Int =
    if (containsKey(key)) {
        runCatching {
            getInteger(key)
        }.getOrDefault(0)
    } else {
        0
    }

internal fun MediaFormat.longOrZero(key: String): Long =
    if (containsKey(key)) {
        runCatching { getLong(key) }
            .recoverCatching { getInteger(key).toLong() }
            .getOrDefault(0L)
    } else {
        0L
    }

internal fun MediaFormat.floatOrZero(key: String): Float =
    if (containsKey(key)) {
        runCatching { getFloat(key) }
            .recoverCatching { getInteger(key).toFloat() }
            .getOrDefault(0f)
    } else {
        0f
    }

internal fun MediaFormat.directFrameRateHint() =
    if (containsKey(MediaFormat.KEY_FRAME_RATE)) {
        val frameRate =
            runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
        frameRate?.let(::videoFrameRateHint)
    } else {
        null
    }

internal fun MediaFormat.subtitleFormatOrNull(): YSubtitleFormat? = mediaSubtitleFormat(getString(MediaFormat.KEY_MIME))

internal fun mediaSubtitleFormat(mimeType: String?): YSubtitleFormat? =
    when (mimeType?.lowercase()) {
        "application/x-subrip" -> YSubtitleFormat.Srt
        "text/vtt" -> YSubtitleFormat.WebVtt
        "text/x-ssa", "text/x-ass" -> YSubtitleFormat.Ass
        "application/pgs" -> YSubtitleFormat.Pgs
        "application/vobsub" -> YSubtitleFormat.VobSub
        "application/x-quicktime-tx3g" -> YSubtitleFormat.Tx3g
        else -> null
    }

internal fun MediaFormat?.dynamicRangeLabel(): String {
    val format = this ?: return "Unknown"
    val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase()
    if (mime == DOLBY_VISION_MIME) return "Dolby Vision"
    val transfer =
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
            null
        }
    return when (transfer) {
        COLOR_TRANSFER_ST2084 -> "HDR10/PQ"
        COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR/Unknown"
    }
}

internal fun extractNativeDirectHdr10PlusPayload(data: ByteBuffer): ByteArray? {
    if (!data.hasRemaining()) return null
    val bytes = ByteArray(data.remaining())
    data.duplicate().get(bytes)
    return HDR10_PLUS_SAMPLE_PACKINGS
        .asSequence()
        .mapNotNull { packing ->
            runCatching { YBitstream.hdr10PlusItuT35Payload(bytes, packing) }.getOrNull()
        }.firstOrNull()
}

internal const val DOLBY_VISION_MIME = "video/dolby-vision"

private const val DEFAULT_AUDIO_SAMPLE_RATE_HZ = 48_000

private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
private const val ATMOS_PROFILE = 30

private val HDR10_PLUS_SAMPLE_PACKINGS =
    listOf(
        YSamplePacking.AnnexB,
        YSamplePacking.LengthPrefixed(4),
        YSamplePacking.LengthPrefixed(2),
        YSamplePacking.LengthPrefixed(1),
    )
