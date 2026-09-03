package com.yfuse.core2.android

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YCodecConfiguration
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.demux.YVideoTrackFormat
import java.nio.ByteBuffer

/** Converts container-neutral Core2 track metadata into Android MediaCodec configuration. */
internal object AndroidMediaFormatFactory {
    fun video(track: YVideoTrackFormat): MediaFormat {
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
        applyVideoCodecPrivate(format, track)
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
    ) {
        val extra =
            track.codecPrivateData.entries
                .firstOrNull()
                ?.takeIf(ByteArray::isNotEmpty) ?: return
        when (track.codec) {
            YVideoCodec.H264 -> applyAvcPrivate(format, extra, track.samplePacking)
            YVideoCodec.H265 -> applyHevcPrivate(format, extra, track.samplePacking)
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

    private fun applyAvcPrivate(
        format: MediaFormat,
        extra: ByteArray,
        samplePacking: YSamplePacking?,
    ) {
        if (looksLikeConfigurationRecord(extra)) {
            val config = YCodecConfiguration.parseAvcC(extra)
            format.setByteBuffer(CSD_0, ByteBuffer.wrap(config.csd0AnnexB()))
            format.setByteBuffer(CSD_1, ByteBuffer.wrap(config.csd1AnnexB()))
            return
        }
        if (samplePacking == YSamplePacking.AnnexB || looksLikeAnnexB(extra)) {
            val sets = YBitstream.parameterSets(extra, YNalCodec.H264, YSamplePacking.AnnexB)
            sets.sps.takeIf(List<ByteArray>::isNotEmpty)?.joinAnnexB()?.let {
                format.setByteBuffer(CSD_0, ByteBuffer.wrap(it))
            }
            sets.pps.takeIf(List<ByteArray>::isNotEmpty)?.joinAnnexB()?.let {
                format.setByteBuffer(CSD_1, ByteBuffer.wrap(it))
            }
            return
        }
        format.setByteBuffer(CSD_0, ByteBuffer.wrap(extra))
    }

    private fun applyHevcPrivate(
        format: MediaFormat,
        extra: ByteArray,
        samplePacking: YSamplePacking?,
    ) {
        if (looksLikeConfigurationRecord(extra)) {
            val config = YCodecConfiguration.parseHvcC(extra)
            format.setByteBuffer(CSD_0, ByteBuffer.wrap(config.csd0AnnexB()))
            return
        }
        if (samplePacking == YSamplePacking.AnnexB || looksLikeAnnexB(extra)) {
            val sets = YBitstream.parameterSets(extra, YNalCodec.H265, YSamplePacking.AnnexB)
            (sets.vps + sets.sps + sets.pps)
                .takeIf(List<ByteArray>::isNotEmpty)
                ?.joinAnnexB()
                ?.let { format.setByteBuffer(CSD_0, ByteBuffer.wrap(it)) }
            return
        }
        format.setByteBuffer(CSD_0, ByteBuffer.wrap(extra))
    }
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
