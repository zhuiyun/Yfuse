package com.yfuse.feature.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.yfuse.core.model.DecoderMode

internal fun String.isSoftwareVideoDecoder(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("omx.google.") ||
        normalized.startsWith("c2.android.") ||
        normalized.contains("ffmpeg") ||
        normalized.contains("software")
}

/**
 * Keeps explicit decoder choices deterministic without making a device-specific codec list stale.
 * MediaCodecUtil has already applied Android's secure/tunnel and platform compatibility filters;
 * this final pass only honours the user's requested implementation class.
 */
internal fun <T> preferExoDecoderMode(
    decoders: List<T>,
    decoderMode: DecoderMode,
    isSoftwareOnly: (T) -> Boolean,
): List<T> =
    when (decoderMode) {
        DecoderMode.Software -> decoders.filter(isSoftwareOnly).ifEmpty { decoders }
        DecoderMode.Hardware -> decoders.filterNot(isSoftwareOnly).ifEmpty { decoders }
        DecoderMode.Auto -> decoders
    }

@UnstableApi
internal fun Format.exoSecureDecoderActive(decoderName: String): Boolean {
    val mimeType = sampleMimeType ?: return false
    if (drmInitData == null) return false
    val secureDecoderNames =
        runCatching {
            MediaCodecUtil
                .getDecoderInfos(mimeType, true, false)
                .mapTo(hashSetOf()) { it.name.lowercase() }
        }.getOrDefault(emptySet())
    return decoderName.lowercase() in secureDecoderNames ||
        decoderName.contains("secure", ignoreCase = true)
}
