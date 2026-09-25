package com.yfuse.core2.android

import android.media.MediaFormat
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.capability.YAudioCodec

/**
 * The codec a platform extractor's audio format can be decoded as, or [YAudioCodec.Unknown] when it
 * cannot be named - including an AAC label with nothing behind it ([aacLabelWithoutConfig]).
 */
internal fun MediaFormat.platformAudioCodec(): YAudioCodec =
    if (aacLabelWithoutConfig()) {
        YAudioCodec.Unknown
    } else {
        getString(MediaFormat.KEY_MIME)?.toYAudioCodec() ?: YAudioCodec.Unknown
    }

/**
 * Whether a track the platform extractor labels AAC carries no AudioSpecificConfig, and so is not
 * AAC a platform decoder can play.
 *
 * Android's MP4 extractor names an `mp4a` sample entry AAC unless its object type is one of the few
 * it recognises (MP3, for one). DTS muxed into MP4 by FFmpeg sits in such an entry (object type
 * 0xA9), and other codecs can arrive the same way, as `audio/mp4a-latm` with no AudioSpecificConfig.
 * The AAC decoder, never configured, takes every frame and emits nothing: the picture plays, the
 * sound never starts, and no error says why. A Dolby Vision MP4 with E-AC-3, DTS and AAC tracks did
 * that on a phone without an E-AC-3 decoder - NativeDirect took the DTS track for the first
 * playable AAC, ahead of the real AAC track behind it, and every local restart took it again.
 *
 * Genuine AAC always brings its AudioSpecificConfig as csd-0 (read from the MP4 or Matroska header,
 * or built from the ADTS header by the transport-stream and ADTS extractors), and a raw ADTS stream
 * configures the decoder itself. Only platform extractor formats are judged here: a format built
 * from FFmpeg metadata follows its own rules.
 */
internal fun MediaFormat.aacLabelWithoutConfig(): Boolean =
    platformAacLabelWithoutConfig(
        mime = getString(MediaFormat.KEY_MIME),
        audioSpecificConfigBytes = runCatching { getByteBuffer(AAC_CONFIG_KEY) }.getOrNull()?.remaining(),
        adts = runCatching { getInteger(MediaFormat.KEY_IS_ADTS) }.getOrNull() == 1,
    )

/** [aacLabelWithoutConfig] over the three facts it reads, so it can be checked off-device. */
internal fun platformAacLabelWithoutConfig(
    mime: String?,
    audioSpecificConfigBytes: Int?,
    adts: Boolean,
): Boolean =
    mime?.normalizedAudioMimeType() == PLATFORM_AAC_MIME &&
        !adts &&
        (audioSpecificConfigBytes ?: 0) < MIN_AUDIO_SPECIFIC_CONFIG_BYTES

/**
 * Records a track passed over for [aacLabelWithoutConfig], by its place among [tracks] (the
 * platform audio tracks), beside the codecs the server lists in its own order.
 */
internal fun logAacLabelWithoutConfig(
    route: String,
    tracks: List<YTrack>,
    trackIndex: Int,
    sourceHints: YMediaSourceHints?,
) {
    AppLog.warning(
        category = "player.core2",
        event = "platform_aac_label_rejected",
        message = "Passed over a track labelled AAC that carries no AudioSpecificConfig",
        attributes =
            mapOf(
                "route" to route,
                "ordinal" to tracks.indexOfFirst { it.id == "audio:$trackIndex" }.toString(),
                "serverAudioCodecs" to sourceHints?.audioCodecs.orEmpty().joinToString(","),
            ),
    )
}

private const val PLATFORM_AAC_MIME = "audio/mp4a-latm"
private const val AAC_CONFIG_KEY = "csd-0"

/** Object type, sampling-frequency index and channel configuration take 13 bits. */
private const val MIN_AUDIO_SPECIFIC_CONFIG_BYTES = 2
