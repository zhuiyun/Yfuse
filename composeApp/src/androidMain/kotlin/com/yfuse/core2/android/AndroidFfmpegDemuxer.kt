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
import com.yfuse.core2.demux.YSubtitlePacketDecoder
import com.yfuse.core2.demux.YSubtitleTrackFormat
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.hdr.YHdrStaticMetadata
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.sync.YMediaTimestampTimeline
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enhanced-demux implementation backed by the pinned FFmpeg 8.1 libraries in the custom native AAR.
 *
 * FFmpeg normally stops at AVPacket. An optional versioned extension also exposes bounded software
 * video/audio decode for codecs with no executable MediaCodec route. Encoded samples are copied
 * once from a reusable native DirectByteBuffer into the immutable common demux contract.
 *
 * FFmpeg exposes container timestamps rather than the zero-based product timeline used by the
 * other player engines. The first selected compressed packet establishes the source timestamp
 * origin; all outgoing PTS/DTS values are rebased and seek targets are translated back before JNI.
 */
internal class AndroidFfmpegDemuxer :
    YDemuxer,
    YSubtitlePacketDecoder {
    override val name: String = "FFmpeg 8.1 / libavformat"

    private val timeline = YMediaTimestampTimeline()
    private var handle = 0L
    private var openResult: YDemuxOpenResult? = null
    private var packetBuffer = ByteBuffer.allocateDirect(INITIAL_PACKET_BUFFER_BYTES)
    private var prefetchedSample: YCompressedSample? = null

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
                bitRateBitsPerSecond = FfmpegNativeBridge.bitRateBitsPerSecond(handle),
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
            prefetchedSample = null
            timeline.reset()
            throw throwable
        }
    }

    override fun selectTracks(trackIds: Set<YTrackId>) {
        val result = requireOpenResult()
        val known = result.tracks.mapTo(mutableSetOf()) { it.id }
        require(trackIds.all { it in known }) { "Selected track does not belong to this demux session" }
        val retainedPrefetched = prefetchedSample?.takeIf { it.trackId in trackIds }
        FfmpegNativeBridge.selectTracks(
            requireHandle(),
            trackIds.map(YTrackId::value).sorted().toIntArray(),
        )
        prefetchedSample = retainedPrefetched
        // The first packet is retained rather than consumed. At an ordinary 0 ms start it becomes
        // the first packet returned to the decoder; for a resume seek it only supplies the source
        // timestamp origin before the seek invalidates it.
        if (!timeline.established && trackIds.isNotEmpty()) {
            readRawSample()?.let { first ->
                timeline.establish(first.presentationTimeUs)
                prefetchedSample = first
            }
        }
    }

    override fun readSample(): YCompressedSample? {
        val raw = prefetchedSample?.also { prefetchedSample = null } ?: readRawSample() ?: return null
        if (!timeline.established) timeline.establish(raw.presentationTimeUs)
        return raw.copy(
            presentationTimeUs = timeline.presentationTimeUs(raw.presentationTimeUs),
            decodeTimeUs = raw.decodeTimeUs?.let(timeline::decodeTimeUs),
        )
    }

    private fun readRawSample(): YCompressedSample? {
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
                    val source =
                        packetBuffer.duplicate().apply {
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
        prefetchedSample = null
        FfmpegNativeBridge.seek(
            requireHandle(),
            timeline.sourceTimeUs(positionUs),
        )
    }

    override fun decodeSubtitle(sample: YCompressedSample): List<YSubtitleCue> {
        val track =
            requireOpenResult().tracks.firstOrNull { it.id == sample.trackId }
                ?: error("Subtitle sample does not belong to this demux session")
        require(track.subtitle?.format in BITMAP_SUBTITLE_FORMATS) {
            "Only FFmpeg bitmap subtitle tracks use the native subtitle decoder"
        }
        val decoded =
            FfmpegNativeBridge.decodeSubtitle(
                handle = requireHandle(),
                trackIndex = sample.trackId.value,
                data = sample.data,
                // The native subtitle decoder still owns stream-local codec state. Feed it the
                // original timestamp while keeping the cue emitted below on the normalized clock.
                presentationTimeUs = timeline.sourceTimeUs(sample.presentationTimeUs),
                durationUs = sample.durationUs,
            ) ?: return emptyList()
        return decoded.toBitmapSubtitleCues(sample)
    }

    val softwareDecodeAvailable: Boolean get() = FfmpegNativeBridge.softwareDecodeAvailable

    fun configureSoftwareDecoder(
        trackId: YTrackId,
        toneMapHdrToSdr: Boolean = false,
    ) {
        requireOpenResult().tracks.singleOrNull { it.id == trackId }
            ?: error("Software decoder track does not belong to this demux session")
        FfmpegNativeBridge.configureSoftwareDecoder(requireHandle(), trackId.value, toneMapHdrToSdr)
    }

    fun sendSoftwarePacket(
        trackId: YTrackId,
        sample: YCompressedSample?,
    ): Boolean {
        requireOpenResult().tracks.singleOrNull { it.id == trackId }
            ?: error("Software decoder track does not belong to this demux session")
        require(sample == null || sample.trackId == trackId) { "Software packet track is inconsistent" }
        require(sample == null || YSampleFlag.Encrypted !in sample.flags) {
            "Encrypted samples cannot enter FFmpeg software decode"
        }
        return FfmpegNativeBridge.sendSoftwarePacket(
            handle = requireHandle(),
            trackIndex = trackId.value,
            data = sample?.data,
            presentationTimeUs = sample?.presentationTimeUs,
            decodeTimeUs = sample?.decodeTimeUs,
        )
    }

    fun receiveSoftwareVideoFrame(
        trackId: YTrackId,
        target: ByteBuffer,
    ): LongArray = FfmpegNativeBridge.receiveSoftwareVideoFrame(requireHandle(), trackId.value, target)

    fun receiveSoftwareAudioFrame(
        trackId: YTrackId,
        target: ByteBuffer,
    ): LongArray = FfmpegNativeBridge.receiveSoftwareAudioFrame(requireHandle(), trackId.value, target)

    fun flushSoftwareDecoder(trackId: YTrackId) {
        FfmpegNativeBridge.flushSoftwareDecoder(requireHandle(), trackId.value)
    }

    override fun close() {
        val previous = handle
        handle = 0L
        openResult = null
        prefetchedSample = null
        timeline.reset()
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
            FfmpegNativeBridge
                .trackExtradata(handle, index)
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
                        FFMPEG_HDR10_PLUS -> YHdrType.Hdr10Plus
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
                            hdrStaticMetadata =
                                FfmpegNativeBridge
                                    .trackHdrStaticInfo(handle, index)
                                    ?.toHdrStaticMetadata(),
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
                    subtitle =
                        YSubtitleTrackFormat(
                            format = ffmpegSubtitleFormat(codecName),
                            mimeType = ffmpegSubtitleMime(codecName),
                            codecPrivateData = YCodecPrivateData(extradata),
                        ),
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

    private fun requireHandle(): Long = handle.takeIf { it != 0L } ?: error("FFmpeg demux session has not been opened")

    private fun requireOpenResult(): YDemuxOpenResult = checkNotNull(openResult) { "FFmpeg demux session has not been opened" }
}

internal fun ByteArray.toBitmapSubtitleCues(sample: YCompressedSample): List<YSubtitleCue> {
    val input = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    require(input.remaining() >= SUBTITLE_PAYLOAD_HEADER_BYTES) { "FFmpeg subtitle payload is truncated" }
    require(input.int == SUBTITLE_PAYLOAD_MAGIC) { "FFmpeg subtitle payload has an invalid signature" }
    require(input.int == SUBTITLE_PAYLOAD_VERSION) { "FFmpeg subtitle payload version is unsupported" }
    val canvasWidth = input.positiveSubtitleDimension()
    val canvasHeight = input.positiveSubtitleDimension()
    val startOffsetUs = input.unsignedIntToLong() * MICROS_PER_MILLISECOND
    val endOffsetUs = input.unsignedIntToLong() * MICROS_PER_MILLISECOND
    val rectCount = input.int
    require(rectCount in 1..MAX_SUBTITLE_RECTS) { "FFmpeg subtitle rectangle count is invalid" }
    val startUs = sample.presentationTimeUs.coerceAtLeast(0L) + startOffsetUs
    val fallbackDurationUs = sample.durationUs?.takeIf { it > 0L } ?: DEFAULT_BITMAP_SUBTITLE_DURATION_US
    val endUs =
        if (endOffsetUs > startOffsetUs) {
            sample.presentationTimeUs.coerceAtLeast(0L) + endOffsetUs
        } else {
            startUs + fallbackDurationUs
        }
    return List(rectCount) { rectIndex ->
        require(input.remaining() >= SUBTITLE_RECT_HEADER_BYTES) { "FFmpeg subtitle rectangle is truncated" }
        val x = input.nonNegativeSubtitleCoordinate()
        val y = input.nonNegativeSubtitleCoordinate()
        val width = input.positiveSubtitleDimension()
        val height = input.positiveSubtitleDimension()
        input.int // authored flags are retained in native diagnostics, not presentation policy
        val pixelCount = input.int
        input.int // reserved
        require(pixelCount == width * height && pixelCount <= MAX_SUBTITLE_PIXELS) {
            "FFmpeg subtitle rectangle pixel count is invalid"
        }
        require(input.remaining() >= pixelCount * Int.SIZE_BYTES) { "FFmpeg subtitle pixels are truncated" }
        require(x + width <= canvasWidth && y + height <= canvasHeight) {
            "FFmpeg subtitle rectangle exceeds its authored canvas"
        }
        val pixels = IntArray(pixelCount) { input.int }
        YSubtitleCue(
            id = "${sample.trackId.value}:${sample.presentationTimeUs}:$rectIndex",
            startUs = startUs,
            endUs = endUs.coerceAtLeast(startUs + 1L),
            payload =
                YSubtitlePayload.BitmapArgb(
                    width = width,
                    height = height,
                    x = x,
                    y = y,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    pixels = pixels,
                ),
        )
    }.also {
        require(!input.hasRemaining()) { "FFmpeg subtitle payload has trailing data" }
    }
}

private fun ByteBuffer.positiveSubtitleDimension(): Int =
    int.also { require(it in 1..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle dimension is invalid" } }

private fun ByteBuffer.nonNegativeSubtitleCoordinate(): Int =
    int.also { require(it in 0..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle coordinate is invalid" } }

private fun ByteBuffer.unsignedIntToLong(): Long = int.toLong() and 0xffff_ffffL

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
        "vc1", "wmv3" -> YVideoCodec.Vc1
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
        "alac" -> YAudioCodec.Alac
        "mp3", "mp2" -> YAudioCodec.Mp3
        "ac3" -> YAudioCodec.Ac3
        "eac3" -> if (profile == ATMOS_PROFILE) YAudioCodec.Eac3Joc else YAudioCodec.Eac3
        "flac" -> YAudioCodec.Flac
        "opus" -> YAudioCodec.Opus
        "truehd" -> if (profile == ATMOS_PROFILE) YAudioCodec.TrueHdAtmos else YAudioCodec.TrueHd
        "dca", "dts" ->
            when (profile) {
                in DTS_X_PROFILES -> YAudioCodec.DtsX
                in DTS_HD_PROFILES -> YAudioCodec.DtsHd
                else -> YAudioCodec.Dts
            }
        "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_s32be", "pcm_f32le" ->
            YAudioCodec.Pcm
        else -> YAudioCodec.Unknown
    }

internal fun ffmpegSubtitleFormat(name: String): YSubtitleFormat =
    when (name.lowercase()) {
        "subrip", "srt" -> YSubtitleFormat.Srt
        "webvtt" -> YSubtitleFormat.WebVtt
        "ass" -> YSubtitleFormat.Ass
        "ssa" -> YSubtitleFormat.Ssa
        "hdmv_pgs_subtitle", "pgssub" -> YSubtitleFormat.Pgs
        "dvd_subtitle", "dvdsub" -> YSubtitleFormat.VobSub
        "mov_text", "tx3g" -> YSubtitleFormat.Tx3g
        else -> YSubtitleFormat.Unknown
    }

private fun ffmpegSubtitleMime(name: String): String =
    when (ffmpegSubtitleFormat(name)) {
        YSubtitleFormat.Srt -> "application/x-subrip"
        YSubtitleFormat.WebVtt -> "text/vtt"
        YSubtitleFormat.Ass, YSubtitleFormat.Ssa -> "text/x-ssa"
        YSubtitleFormat.Pgs -> "application/pgs"
        YSubtitleFormat.VobSub -> "application/vobsub"
        YSubtitleFormat.Tx3g -> "application/x-quicktime-tx3g"
        YSubtitleFormat.Unknown -> "application/x-ffmpeg-subtitle"
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
            YVideoCodec.Vc1 -> "video/wvc1"
            YVideoCodec.Mpeg2 -> "video/mpeg2"
            YVideoCodec.ProRes -> "video/prores"
            YVideoCodec.Unknown -> "video/x-ffmpeg-unknown"
        }
    }

private fun ffmpegAudioMime(codec: YAudioCodec): String =
    when (codec) {
        YAudioCodec.Aac -> "audio/mp4a-latm"
        YAudioCodec.Alac -> "audio/alac"
        YAudioCodec.Mp3 -> "audio/mpeg"
        YAudioCodec.Ac3 -> "audio/ac3"
        YAudioCodec.Eac3 -> "audio/eac3"
        YAudioCodec.Eac3Joc -> "audio/eac3-joc"
        YAudioCodec.Flac -> "audio/flac"
        YAudioCodec.Opus -> "audio/opus"
        YAudioCodec.TrueHd -> "audio/true-hd"
        YAudioCodec.TrueHdAtmos -> "audio/true-hd"
        YAudioCodec.Dts -> "audio/vnd.dts"
        YAudioCodec.DtsHd -> "audio/vnd.dts.hd"
        YAudioCodec.DtsX -> "audio/vnd.dts.hd"
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

private fun IntArray.toHdrStaticMetadata(): YHdrStaticMetadata? {
    if (size < HDR_STATIC_INFO_FIELDS) return null
    return YHdrStaticMetadata(
        redX = this[0],
        redY = this[1],
        greenX = this[2],
        greenY = this[3],
        blueX = this[4],
        blueY = this[5],
        whiteX = this[6],
        whiteY = this[7],
        maxDisplayLuminance = this[8],
        minDisplayLuminance = this[9],
        maxContentLightLevel = this[10],
        maxFrameAverageLightLevel = this[11],
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

private val DTS_HD_PROFILES = setOf(50, 60)
private val DTS_X_PROFILES = setOf(61, 62)
private val BITMAP_SUBTITLE_FORMATS = setOf(YSubtitleFormat.Pgs, YSubtitleFormat.VobSub)
private const val ATMOS_PROFILE = 30
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
private const val HDR_STATIC_INFO_FIELDS = 12
private const val SUBTITLE_PAYLOAD_MAGIC = 0x42555359
private const val SUBTITLE_PAYLOAD_VERSION = 1
private const val SUBTITLE_PAYLOAD_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val SUBTITLE_RECT_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val MAX_SUBTITLE_RECTS = 64
private const val MAX_SUBTITLE_DIMENSION = 16_384
private const val MAX_SUBTITLE_PIXELS = 8 * 1024 * 1024
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_BITMAP_SUBTITLE_DURATION_US = 5_000_000L
