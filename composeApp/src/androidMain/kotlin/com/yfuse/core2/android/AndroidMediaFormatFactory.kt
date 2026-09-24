package com.yfuse.core2.android

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YCodecConfiguration
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YParameterSets
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.demux.YVideoTrackFormat
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer

/** Converts container-neutral Core2 track metadata into Android MediaCodec configuration. */
internal object AndroidMediaFormatFactory {
    /**
     * [inBandParameterSets] are the first keyframe's, used only when the container's own codec
     * configuration carries no complete set (see [h26xCodecSpecificData]). Callers build the format
     * inside [yVideoFormatStage] so that a malformed record is reported as the Bitstream failure it is.
     */
    fun video(
        track: YVideoTrackFormat,
        inBandParameterSets: YParameterSets? = null,
    ): MediaFormat {
        val mime =
            if (track.dolbyVisionConfig != null) {
                MIME_DOLBY_VISION
            } else {
                track.mimeType
            }
        val format = MediaFormat.createVideoFormat(mime, track.width.coerceAtLeast(0), track.height.coerceAtLeast(0))
        if (track.frameRate > 0f) format.setFloat(MediaFormat.KEY_FRAME_RATE, track.frameRate)
        applyHdr(format, track.hdrType)
        track.hdrStaticMetadata?.let { metadata ->
            format.setByteBuffer(
                MediaFormat.KEY_HDR_STATIC_INFO,
                ByteBuffer.wrap(metadata.toCta8613Bytes()),
            )
        }
        applyVideoCodecPrivate(format, track, inBandParameterSets)
        track.dolbyVisionConfig?.let { config ->
            config.profile.toAndroidDolbyVisionProfile()?.let { profile ->
                format.setInteger(MediaFormat.KEY_PROFILE, profile)
            }
            format.setByteBuffer(CSD_2, ByteBuffer.wrap(config.toConfigurationBytes()))
        }
        return format
    }

    fun audio(track: YAudioTrackFormat): MediaFormat {
        val format =
            MediaFormat.createAudioFormat(
                track.mimeType,
                track.sampleRate.coerceAtLeast(1),
                track.channelCount.coerceAtLeast(1),
            )
        track.codecPrivateData.entries.forEachIndexed { index, bytes ->
            if (bytes.isNotEmpty()) {
                format.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
        }
        return format
    }

    private fun applyHdr(
        format: MediaFormat,
        hdrType: YHdrType,
    ) {
        when (hdrType) {
            YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.DolbyVision -> {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            }
            YHdrType.Hlg -> {
                format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
            YHdrType.Sdr -> Unit
        }
    }

    private fun applyVideoCodecPrivate(
        format: MediaFormat,
        track: YVideoTrackFormat,
        inBandParameterSets: YParameterSets?,
    ) {
        h26xCodecSpecificData(track, inBandParameterSets)?.let { data ->
            data.csd0?.let { format.setByteBuffer(CSD_0, ByteBuffer.wrap(it)) }
            data.csd1?.let { format.setByteBuffer(CSD_1, ByteBuffer.wrap(it)) }
            return
        }
        val extra =
            track.codecPrivateData.entries
                .firstOrNull()
                ?.takeIf(ByteArray::isNotEmpty) ?: return
        when (track.codec) {
            YVideoCodec.Av1 -> applyAv1Private(format, extra)
            else -> format.setByteBuffer(CSD_0, ByteBuffer.wrap(extra))
        }
    }

    private fun applyAv1Private(
        format: MediaFormat,
        extra: ByteArray,
    ) {
        if (extra.size >= 4 && extra[0].toInt() and 0x80 != 0) {
            val config = YCodecConfiguration.parseAv1C(extra)
            if (config.configObus.isNotEmpty()) {
                format.setByteBuffer(CSD_0, ByteBuffer.wrap(config.configObus))
            }
        } else {
            YBitstream.scanAv1(extra)
            format.setByteBuffer(CSD_0, ByteBuffer.wrap(extra))
        }
    }
}

/** MediaCodec codec-specific data in Annex-B form; a null buffer is left out of the format. */
internal class YVideoCodecSpecificData(
    val csd0: ByteArray? = null,
    val csd1: ByteArray? = null,
    /**
     * True when the container carried no complete parameter set: the decoder depends on the sets
     * the keyframes carry in-band, and the first keyframe's can supply csd.
     */
    val parameterSetsMissing: Boolean = false,
)

/**
 * csd-0 (and csd-1 for AVC) for an H.264/H.265 track, or null for any other codec.
 *
 * A container record with a complete parameter set wins. Some muxers write an `avcC`/`hvcC` with
 * no parameter sets and repeat them in every keyframe instead; that record used to fail playback in
 * our own parser ("HEVC configuration contains no SPS", one Dolby Vision Profile 5 MKV). Its csd now
 * comes from [inBandParameterSets], the first keyframe's, and while those are unknown the decoder
 * starts csd-less and reads the sets in-band. A malformed record still throws; callers report that
 * through [yVideoFormatStage] as a deterministic Bitstream failure.
 */
internal fun h26xCodecSpecificData(
    track: YVideoTrackFormat,
    inBandParameterSets: YParameterSets? = null,
): YVideoCodecSpecificData? {
    val codec =
        when (track.codec) {
            YVideoCodec.H264 -> YNalCodec.H264
            YVideoCodec.H265 -> YNalCodec.H265
            else -> return null
        }
    val extra =
        track.codecPrivateData.entries
            .firstOrNull()
            ?.takeIf(ByteArray::isNotEmpty)
    val record = extra != null && looksLikeConfigurationRecord(extra)
    val containerSets =
        when {
            extra == null -> YParameterSets()
            record && codec == YNalCodec.H264 -> YCodecConfiguration.avcRecordParameterSets(extra)
            record -> YCodecConfiguration.hevcRecordParameterSets(extra)
            track.samplePacking == YSamplePacking.AnnexB || looksLikeAnnexB(extra) ->
                YBitstream.parameterSets(extra, codec, YSamplePacking.AnnexB)
            // Private data in no known layout is handed to the decoder untouched, as before.
            else -> return YVideoCodecSpecificData(csd0 = extra)
        }
    if (containerSets.complete) return containerSets.toCodecSpecificData(codec, parameterSetsMissing = false)
    val completed = inBandParameterSets?.orElse(containerSets)
    if (completed != null && completed.complete) {
        return completed.toCodecSpecificData(codec, parameterSetsMissing = true)
    }
    // Nothing complete yet. A record's partial set stays out of csd; Annex-B private data keeps
    // supplying whatever sets it has, as it always did.
    return if (extra == null || record) {
        YVideoCodecSpecificData(parameterSetsMissing = true)
    } else {
        containerSets.toCodecSpecificData(codec, parameterSetsMissing = true)
    }
}

/** True when [track]'s decoder configuration has to come from its keyframes. Throws like [h26xCodecSpecificData]. */
internal fun videoParameterSetsMissing(track: YVideoTrackFormat): Boolean =
    h26xCodecSpecificData(track)?.parameterSetsMissing == true

/**
 * The parameter sets one access unit carries in-band, or null when it carries none or cannot be
 * scanned. [track] is the demuxed format whose packing describes [data].
 */
internal fun inBandParameterSets(
    data: ByteArray,
    track: YVideoTrackFormat,
): YParameterSets? {
    val codec =
        when (track.codec) {
            YVideoCodec.H264 -> YNalCodec.H264
            YVideoCodec.H265 -> YNalCodec.H265
            else -> return null
        }
    val packing = track.samplePacking ?: return null
    // A sample the scan rejects fails later, in its own queue stage; here it only means "unknown".
    val sets = runCatching { YBitstream.parameterSets(data, codec, packing) }.getOrNull() ?: return null
    return sets.takeIf { it.vps.isNotEmpty() || it.sps.isNotEmpty() || it.pps.isNotEmpty() }
}

/**
 * Runs MediaFormat building from demuxed track metadata as its own failure stage.
 *
 * Everything inside is a pure function of the track, so a rejection (a malformed codec configuration
 * record) repeats on every reopen of the same route: a deterministic Bitstream failure. Built inside
 * the decoder-configure stage, 1.0.83 reported our own hvcC parser's rejection as
 * Decoder/VideoDecoderConfigure and the capability registry blamed the decoder for it.
 */
internal inline fun <T> yVideoFormatStage(
    safeDetail: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: YPlaybackException) {
        throw failure
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        throw YPlaybackException(
            category = YPlaybackFailureCategory.Container,
            stage = YPlaybackFailureStage.Bitstream,
            safeDetail = safeDetail,
            cause = failure,
            deterministic = true,
        )
    }

private fun YParameterSets.toCodecSpecificData(
    codec: YNalCodec,
    parameterSetsMissing: Boolean,
): YVideoCodecSpecificData =
    when (codec) {
        YNalCodec.H264 ->
            YVideoCodecSpecificData(
                csd0 = sps.takeIf(List<ByteArray>::isNotEmpty)?.joinAnnexB(),
                csd1 = pps.takeIf(List<ByteArray>::isNotEmpty)?.joinAnnexB(),
                parameterSetsMissing = parameterSetsMissing,
            )
        // Android HEVC decoders conventionally receive VPS/SPS/PPS together in csd-0.
        YNalCodec.H265 ->
            YVideoCodecSpecificData(
                csd0 = (vps + sps + pps).takeIf(List<ByteArray>::isNotEmpty)?.joinAnnexB(),
                parameterSetsMissing = parameterSetsMissing,
            )
    }

/**
 * Applies a lower bound for `max-input-size` when nothing upstream supplied one.
 *
 * MediaCodec sizes its input buffers from this key. The enhanced-demux route builds its format from
 * scratch and never has it; a MediaExtractor format usually does, but not always. Without it the
 * platform picks a vendor default that a large IDR frame can exceed, and the queue guard then fails
 * the whole session instead of decoding. An existing value always wins: the container knows the real
 * peak better than a formula does.
 */
internal fun MediaFormat.applyVideoMaxInputSizeFloor() {
    if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) return
    val mime = getString(MediaFormat.KEY_MIME) ?: return
    val width = runCatching { getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
    val height = runCatching { getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
    videoMaxInputSizeBytes(mime, width, height)?.let { bytes ->
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bytes)
    }
}

/** Audio counterpart of [applyVideoMaxInputSizeFloor]. */
internal fun MediaFormat.applyAudioMaxInputSizeFloor() {
    if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) return
    val channelCount = runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioMaxInputSizeBytes(channelCount))
}

/**
 * Lower bound for a compressed video access unit, mirroring the ratio the platform decoders are
 * built around: three bytes per two luma samples divided by a conservative per-codec compression
 * ratio. It is a floor for buffer allocation, not a claim about the real peak frame size, so an
 * unknown resolution deliberately returns null and leaves the platform default in place.
 */
internal fun videoMaxInputSizeBytes(
    mime: String,
    width: Int,
    height: Int,
): Int? {
    if (width <= 0 || height <= 0) return null
    val pixels = width.toLong() * height.toLong()
    val minimumCompressionRatio =
        when (mime.lowercase()) {
            MIME_H265, MIME_VP9 -> 4L
            // Dolby Vision sits on an HEVC, AVC or AV1 base depending on profile, so it takes the
            // generous ratio rather than assuming the HEVC one.
            MIME_H264, MIME_AV1, MIME_VP8, MIME_MPEG4, MIME_DOLBY_VISION -> 2L
            // An unrecognised codec gets the most generous ratio rather than a guess that is too
            // small; over-allocating a few MiB is cheaper than failing playback.
            else -> 2L
        }
    return (pixels * 3L / (2L * minimumCompressionRatio))
        .coerceIn(MIN_VIDEO_MAX_INPUT_BYTES, MAX_VIDEO_MAX_INPUT_BYTES)
        .toInt()
}

/** Compressed audio frames are small, but lossless multichannel carriers are not. */
internal fun audioMaxInputSizeBytes(channelCount: Int): Int =
    (channelCount.coerceAtLeast(1).toLong() * PER_CHANNEL_AUDIO_MAX_INPUT_BYTES)
        .coerceIn(MIN_AUDIO_MAX_INPUT_BYTES, MAX_AUDIO_MAX_INPUT_BYTES)
        .toInt()

/** Normalizes FFmpeg/container NAL packing before an access unit enters MediaCodec. */
internal fun normalizeVideoSampleForMediaCodec(
    data: ByteArray,
    track: YVideoTrackFormat,
): ByteArray {
    if (track.codec == YVideoCodec.Av1) return YBitstream.normalizeAv1LowOverhead(data)
    val packing = track.samplePacking ?: return data
    return when (track.codec) {
        YVideoCodec.H264 ->
            if (packing == YSamplePacking.AnnexB) {
                data
            } else {
                YBitstream.normalize(data, YNalCodec.H264, packing, YSamplePacking.AnnexB)
            }
        YVideoCodec.H265 ->
            if (packing == YSamplePacking.AnnexB) {
                data
            } else {
                YBitstream.normalize(data, YNalCodec.H265, packing, YSamplePacking.AnnexB)
            }
        YVideoCodec.Av1 -> error("AV1 normalization is handled before NAL packing")
        else -> data
    }
}

@SuppressLint("InlinedApi")
internal fun Int.toAndroidDolbyVisionProfile(): Int? =
    when (this) {
        4 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr
        5 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn
        7 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb
        8 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt
        9 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe
        10 -> MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110
        else -> null
    }

private fun looksLikeConfigurationRecord(data: ByteArray): Boolean = data.isNotEmpty() && data[0].toInt() and 0xff == 1

private fun looksLikeAnnexB(data: ByteArray): Boolean =
    data.size >= 3 &&
        data[0] == 0.toByte() &&
        data[1] == 0.toByte() &&
        (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))

private fun List<ByteArray>.joinAnnexB(): ByteArray =
    fold(ByteArray(0)) { output, nal ->
        output + byteArrayOf(0, 0, 0, 1) + nal
    }

private const val MIME_DOLBY_VISION = "video/dolby-vision"
private const val MIME_H264 = "video/avc"
private const val MIME_H265 = "video/hevc"
private const val MIME_VP8 = "video/x-vnd.on2.vp8"
private const val MIME_VP9 = "video/x-vnd.on2.vp9"
private const val MIME_AV1 = "video/av01"
private const val MIME_MPEG4 = "video/mp4v-es"
private const val MIN_VIDEO_MAX_INPUT_BYTES = 256L * 1024L
private const val MAX_VIDEO_MAX_INPUT_BYTES = 32L * 1024L * 1024L
private const val PER_CHANNEL_AUDIO_MAX_INPUT_BYTES = 32L * 1024L
private const val MIN_AUDIO_MAX_INPUT_BYTES = 64L * 1024L
private const val MAX_AUDIO_MAX_INPUT_BYTES = 1L * 1024L * 1024L
private const val CSD_0 = "csd-0"
private const val CSD_1 = "csd-1"
private const val CSD_2 = "csd-2"
