package com.yfuse.core2.android

import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.demux.YCodecPrivateData
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSampleFlag
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import java.nio.ByteBuffer

/**
 * Enhanced-demux implementation backed by the pinned FFmpeg 8.1 libraries in the custom native AAR.
 *
 * FFmpeg stops at AVPacket: no video/audio decode API is exposed by this class. Encoded samples are
 * copied once from a reusable native DirectByteBuffer into the immutable common demux contract,
 * then Core2 Bitstream/MediaCodec own the rest of the playback path.
 */
internal class AndroidFfmpegDemuxer : YDemuxer {
    override val name: String = "FFmpeg 8.1 / libavformat"

    private var handle = 0L
    private var openResult: YDemuxOpenResult? = null
    private var packetBuffer = ByteBuffer.allocateDirect(INITIAL_PACKET_BUFFER_BYTES)

    val available: Boolean get() = FfmpegNativeBridge.available

    override fun open(source: YDemuxSource): YDemuxOpenResult {
        close()
        check(available) { "YCore FFmpeg enhanced demux is unavailable" }
        var openedHandle = 0L
        return try {
            openedHandle = FfmpegNativeBridge.open(source.uri, source.headers)
            handle = openedHandle
            val tracks =
                (0 until FfmpegNativeBridge.trackCount(handle))
                    .mapNotNull(::readTrack)
            YDemuxOpenResult(
                container = ffmpegContainer(FfmpegNativeBridge.containerName(handle)),
                durationUs = FfmpegNativeBridge.durationUs(handle),
                tracks = tracks,
            ).also { result ->
                openResult = result
                // Nothing is selected implicitly: routing chooses the exact video/audio/subtitle
                // tracks after evaluating capabilities and preferences.
                FfmpegNativeBridge.selectTracks(handle, intArrayOf())
            }
        } catch (throwable: Throwable) {
            if (openedHandle != 0L) FfmpegNativeBridge.close(openedHandle)
            handle = 0L
            openResult = null
            throw throwable
        }
    }

    override fun selectTracks(trackIds: Set<YTrackId>) {
        val result = requireOpenResult()
        val known = result.tracks.mapTo(mutableSetOf()) { it.id }
        require(trackIds.all { it in known }) { "Selected track does not belong to this demux session" }
        FfmpegNativeBridge.selectTracks(
            requireHandle(),
            trackIds.map(YTrackId::value).sorted().toIntArray(),
        )
    }

    override fun readSample(): YCompressedSample? {
        val handle = requireHandle()
        while (true) {
            val result = FfmpegNativeBridge.readPacket(handle, packetBuffer)
            require(result.size >= PACKET_RESULT_FIELDS) { "Invalid FFmpeg packet result" }
            when (result[PACKET_STATUS_INDEX]) {
                FFMPEG_PACKET_EOF -> return null
                FFMPEG_PACKET_GROW_BUFFER -> {
                    val required = result[PACKET_SIZE_INDEX]
                    require(required in 1..MAX_PACKET_BUFFER_BYTES.toLong()) {
                        "Encoded packet exceeds the YCore packet safety limit"
                    }
                    val nextSize =
                        maxOf(
                            required.toInt(),
                            (packetBuffer.capacity().toLong() * 2L)
                                .coerceAtMost(MAX_PACKET_BUFFER_BYTES.toLong())
                                .toInt(),
                        )
                    packetBuffer = ByteBuffer.allocateDirect(nextSize)
                }
                FFMPEG_PACKET_DATA -> {
                    val size = result[PACKET_SIZE_INDEX].toInt()
                    require(size in 0..packetBuffer.capacity()) { "Invalid FFmpeg packet size" }
                    val data = ByteArray(size)
                    val source = packetBuffer.duplicate().apply {
                        position(0)
                        limit(size)
                    }
                    source.get(data)
                    val packetFlags = result[PACKET_FLAGS_INDEX]
                    return YCompressedSample(
                        trackId = YTrackId(result[PACKET_STREAM_INDEX].toInt()),
                        data = data,
                        presentationTimeUs =
                            result[PACKET_PTS_INDEX].timestampOrNull()
                                ?: result[PACKET_DTS_INDEX].timestampOrNull()
                                ?: 0L,
                        decodeTimeUs = result[PACKET_DTS_INDEX].timestampOrNull(),
                        durationUs = result[PACKET_DURATION_INDEX].timestampOrNull(),
                        flags =
                            buildSet {
                                if (packetFlags and FFMPEG_SAMPLE_SYNC != 0L) add(YSampleFlag.Sync)
                                if (packetFlags and FFMPEG_SAMPLE_ENCRYPTED != 0L) add(YSampleFlag.Encrypted)
                            },
                    )
                }
                else -> error("Unknown FFmpeg packet status")
            }
        }
    }

    override fun seekTo(positionUs: Long) {
        FfmpegNativeBridge.seek(requireHandle(), positionUs.coerceAtLeast(0L))
    }

    override fun close() {
        val previous = handle
        handle = 0L
        openResult = null
        packetBuffer.clear()
        if (previous != 0L) FfmpegNativeBridge.close(previous)
    }

    private fun readTrack(index: Int): YDemuxTrack? {
        val handle = requireHandle()
        val type = FfmpegNativeBridge.trackType(handle, index)
        val codecName = FfmpegNativeBridge.trackCodecName(handle, index)?.lowercase().orEmpty()
        val language = FfmpegNativeBridge.trackLanguage(handle, index)?.takeIf(String::isNotBlank)
        val label = FfmpegNativeBridge.trackTitle(handle, index)?.takeIf(String::isNotBlank)
        val extradata =
            FfmpegNativeBridge.trackExtradata(handle, index)
                ?.takeIf(ByteArray::isNotEmpty)
                ?.let(::listOf)
                .orEmpty()
        val id = YTrackId(index)
        return when (type) {
            FFMPEG_TRACK_VIDEO -> {
                val info = FfmpegNativeBridge.trackVideoInfo(handle, index)
                require(info.size >= VIDEO_INFO_FIELDS) { "Invalid FFmpeg video track metadata" }
                val dolby = FfmpegNativeBridge.trackDolbyConfig(handle, index)?.toDolbyVisionConfig()
                val codec =
                    dolby?.codecFamily?.let { family ->
                        when (family) {
                            com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Hevc -> YVideoCodec.H265
                            com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Avc -> YVideoCodec.H264
                            com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Av1 -> YVideoCodec.Av1
                            com.yfuse.core2.dolby.YDolbyVisionCodecFamily.Unknown -> null
                        }
                    } ?: ffmpegVideoCodec(codecName)
                val packing = ffmpegPacking(info[VIDEO_PACKING_INDEX], info[VIDEO_LENGTH_BYTES_INDEX])
                val nativeHdr =
                    when (info[VIDEO_HDR_INDEX]) {
                        FFMPEG_HDR_PQ -> YHdrType.Hdr10
                        FFMPEG_HDR_HLG -> YHdrType.Hlg
                        else -> YHdrType.Sdr
                    }
                val bitDepth =
                    info[VIDEO_BIT_DEPTH_INDEX]
                        .toInt()
                        .takeIf { it > 0 }
                        ?: if (dolby != null || nativeHdr != YHdrType.Sdr) 10 else 8
                YDemuxTrack(
                    id = id,
                    type = YDemuxTrackType.Video,
                    language = language,
                    label = label,
                    video =
                        YVideoTrackFormat(
                            codec = codec,
                            mimeType = ffmpegVideoMime(codec, dolby != null),
                            width = info[VIDEO_WIDTH_INDEX].toInt().coerceAtLeast(0),
                            height = info[VIDEO_HEIGHT_INDEX].toInt().coerceAtLeast(0),
                            frameRate =
                                rationalToFloat(
                                    info[VIDEO_FPS_NUM_INDEX],
                                    info[VIDEO_FPS_DEN_INDEX],
                                ),
                            bitDepth = bitDepth,
                            hdrType = if (dolby != null) YHdrType.DolbyVision else nativeHdr,
                            samplePacking = packing,
                            codecPrivateData = YCodecPrivateData(extradata),
                            dolbyVisionConfig = dolby,
                        ),
                )
            }
            FFMPEG_TRACK_AUDIO -> {
                val info = FfmpegNativeBridge.trackAudioInfo(handle, index)
                require(info.size >= AUDIO_INFO_FIELDS) { "Invalid FFmpeg audio track metadata" }
                val profile = info[AUDIO_PROFILE_INDEX].toInt()
                val codec = ffmpegAudioCodec(codecName, profile)
                YDemuxTrack(
                    id = id,
                    type = YDemuxTrackType.Audio,
                    language = language,
                    label = label,
                    audio =
                        YAudioTrackFormat(
                            codec = codec,
                            mimeType = ffmpegAudioMime(codec),
                            channelCount = info[AUDIO_CHANNELS_INDEX].toInt().coerceAtLeast(0),
                            sampleRate = info[AUDIO_SAMPLE_RATE_INDEX].toInt().coerceAtLeast(0),
                            codecPrivateData = YCodecPrivateData(extradata),
                        ),
                )
            }
            FFMPEG_TRACK_SUBTITLE ->
                YDemuxTrack(
                    id = id,
                    type = YDemuxTrackType.Subtitle,
                    language = language,
                    label = label,
                )
            FFMPEG_TRACK_DATA ->
                YDemuxTrack(
                    id = id,
                    type = YDemuxTrackType.Data,
                    language = language,
                    label = label,
                )
            else -> null
        }
    }

    private fun requireHandle(): Long =
        handle.takeIf { it != 0L } ?: error("FFmpeg demux session has not been opened")

    private fun requireOpenResult(): YDemuxOpenResult =
        checkNotNull(openResult) { "FFmpeg demux session has not been opened" }
}

internal fun ffmpegContainer(name: String?): YContainer {
    val normalized = name?.lowercase().orEmpty()
    return when {
        "matroska" in normalized -> YContainer.Matroska
        "webm" in normalized -> YContainer.WebM
        "mpegts" in normalized -> YContainer.MpegTs
        "mov" in normalized || "mp4" in normalized -> YContainer.Mp4
        "bluray" in normalized -> YContainer.Bdmv
        else -> YContainer.Unknown
    }
}

internal fun ffmpegVideoCodec(name: String): YVideoCodec =
    when (name.lowercase()) {
        "h264" -> YVideoCodec.H264
        "hevc" -> YVideoCodec.H265
        "av1" -> YVideoCodec.Av1
        "vp9" -> YVideoCodec.Vp9
        "mpeg2video" -> YVideoCodec.Mpeg2
        "prores" -> YVideoCodec.ProRes
        else -> YVideoCodec.Unknown
    }

internal fun ffmpegAudioCodec(
    name: String,
    profile: Int,
): YAudioCodec =
    when (name.lowercase()) {
        "aac" -> YAudioCodec.Aac
        "ac3" -> YAudioCodec.Ac3
        "eac3" -> YAudioCodec.Eac3
        "flac" -> YAudioCodec.Flac
        "opus" -> YAudioCodec.Opus
        "truehd" -> YAudioCodec.TrueHd
        "dca", "dts" ->
            if (profile in DTS_HD_PROFILES) YAudioCodec.DtsHd else YAudioCodec.Dts
        "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be", "pcm_f32le" ->
            YAudioCodec.Pcm
        else -> YAudioCodec.Unknown
    }

internal fun ffmpegPacking(
    packing: Long,
    lengthBytes: Long,
): YSamplePacking? =
    when (packing) {
        FFMPEG_PACKING_ANNEX_B -> YSamplePacking.AnnexB
        FFMPEG_PACKING_LENGTH_PREFIXED ->
            lengthBytes.toInt().takeIf { it in 1..4 }?.let(YSamplePacking::LengthPrefixed)
        else -> null
    }

private fun ffmpegVideoMime(
    codec: YVideoCodec,
    dolbyVision: Boolean,
): String =
    if (dolbyVision) {
        "video/dolby-vision"
    } else {
        when (codec) {
            YVideoCodec.H264 -> "video/avc"
            YVideoCodec.H265 -> "video/hevc"
            YVideoCodec.Av1 -> "video/av01"
            YVideoCodec.Vp9 -> "video/x-vnd.on2.vp9"
            YVideoCodec.Mpeg2 -> "video/mpeg2"
            YVideoCodec.ProRes -> "video/prores"
            YVideoCodec.Unknown -> "video/x-ffmpeg-unknown"
        }
    }

private fun ffmpegAudioMime(codec: YAudioCodec): String =
    when (codec) {
        YAudioCodec.Aac -> "audio/mp4a-latm"
        YAudioCodec.Ac3 -> "audio/ac3"
        YAudioCodec.Eac3 -> "audio/eac3"
        YAudioCodec.Flac -> "audio/flac"
        YAudioCodec.Opus -> "audio/opus"
        YAudioCodec.TrueHd -> "audio/true-hd"
        YAudioCodec.Dts -> "audio/vnd.dts"
        YAudioCodec.DtsHd -> "audio/vnd.dts.hd"
        YAudioCodec.Pcm -> "audio/raw"
        YAudioCodec.Unknown -> "audio/x-ffmpeg-unknown"
    }

private fun IntArray.toDolbyVisionConfig(): YDolbyVisionConfig? {
    if (size < DOLBY_CONFIG_FIELDS) return null
    return YDolbyVisionConfig(
        versionMajor = this[0],
        versionMinor = this[1],
        profile = this[2],
        level = this[3],
        rpuPresent = this[4] != 0,
        enhancementLayerPresent = this[5] != 0,
        baseLayerPresent = this[6] != 0,
        baseLayerCompatibilityId = this[7],
        metadataCompression = this[8],
    )
}

private fun rationalToFloat(
    numerator: Long,
    denominator: Long,
): Float =
    if (numerator > 0 && denominator > 0) {
        (numerator.toDouble() / denominator.toDouble()).toFloat()
    } else {
        0f
    }

private val DTS_HD_PROFILES = setOf(50, 60, 61, 62)
private const val INITIAL_PACKET_BUFFER_BYTES = 2 * 1024 * 1024
private const val MAX_PACKET_BUFFER_BYTES = 64 * 1024 * 1024
private const val PACKET_RESULT_FIELDS = 7
private const val PACKET_STATUS_INDEX = 0
private const val PACKET_STREAM_INDEX = 1
private const val PACKET_SIZE_INDEX = 2
private const val PACKET_PTS_INDEX = 3
private const val PACKET_DTS_INDEX = 4
private const val PACKET_DURATION_INDEX = 5
private const val PACKET_FLAGS_INDEX = 6
private const val VIDEO_INFO_FIELDS = 10
private const val VIDEO_WIDTH_INDEX = 0
private const val VIDEO_HEIGHT_INDEX = 1
private const val VIDEO_FPS_NUM_INDEX = 2
private const val VIDEO_FPS_DEN_INDEX = 3
private const val VIDEO_BIT_DEPTH_INDEX = 4
private const val VIDEO_HDR_INDEX = 5
private const val VIDEO_PACKING_INDEX = 8
private const val VIDEO_LENGTH_BYTES_INDEX = 9
private const val AUDIO_INFO_FIELDS = 4
private const val AUDIO_CHANNELS_INDEX = 0
private const val AUDIO_SAMPLE_RATE_INDEX = 1
private const val AUDIO_PROFILE_INDEX = 2
private const val DOLBY_CONFIG_FIELDS = 9
