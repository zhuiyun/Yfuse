package com.yfuse.core2.android

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
        val extra = track.codecPrivateData.entries.firstOrNull()?.takeIf(ByteArray::isNotEmpty) ?: return
        when (track.codec) {
            YVideoCodec.H264 -> applyAvcPrivate(format, extra, track.samplePacking)
            YVideoCodec.H265 -> applyHevcPrivate(format, extra, track.samplePacking)
            else -> format.setByteBuffer(CSD_0, ByteBuffer.wrap(extra))
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

/** Normalizes FFmpeg/container NAL packing before an access unit enters MediaCodec. */
internal fun normalizeVideoSampleForMediaCodec(
    data: ByteArray,
    track: YVideoTrackFormat,
): ByteArray {
    val packing = track.samplePacking ?: return data
    return when (track.codec) {
        YVideoCodec.H264 ->
            if (packing == YSamplePacking.AnnexB) data else
                YBitstream.normalize(data, YNalCodec.H264, packing, YSamplePacking.AnnexB)
        YVideoCodec.H265 ->
            if (packing == YSamplePacking.AnnexB) data else
                YBitstream.normalize(data, YNalCodec.H265, packing, YSamplePacking.AnnexB)
        else -> data
    }
}

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

private fun looksLikeConfigurationRecord(data: ByteArray): Boolean =
    data.isNotEmpty() && data[0].toInt() and 0xff == 1

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
private const val CSD_0 = "csd-0"
private const val CSD_1 = "csd-1"
private const val CSD_2 = "csd-2"
