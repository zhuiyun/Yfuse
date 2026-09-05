package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import java.nio.ByteBuffer

/**
 * Thin JNI surface over the pinned FFmpeg demux libraries bundled with Yfuse's native AAR.
 *
 * Loading is optional: ordinary Legacy/NativeDirect playback remains usable when the custom native
 * AAR is absent. Callers must check [available] before opening an enhanced-demux session.
 */
internal object FfmpegNativeBridge {
    val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
            true
        }.getOrDefault(false)
    }

    val softwareDecodeAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        available && runCatching { nativeSoftwareDecoderApiVersion() >= SOFTWARE_DECODER_API_VERSION }.getOrDefault(false)
    }

    val assRendererAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        available && runCatching { nativeAssRendererApiVersion() >= ASS_RENDERER_API_VERSION }.getOrDefault(false)
    }

    val discNavigationAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        available && runCatching { nativeDiscApiVersion() >= DISC_API_VERSION }.getOrDefault(false)
    }

    /**
     * Whether the loaded `libycore_demux.so` can name the FFmpeg error behind a failed open.
     *
     * The getter and the stage-packed open status arrived together, so a library without it
     * also reports failures in an older shape this bridge cannot decode. The playback report
     * records the answer: a production package still carrying the older artifact is otherwise
     * indistinguishable from a real container failure in an exported bundle.
     */
    val openFailureDetailAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        available &&
            runCatching {
                nativeLastOpenFailure()
                true
            }.getOrDefault(false)
    }

    /**
     * How the loaded `libycore_demux.so` names an open session, so the bridge and the artifact
     * cannot silently disagree about the sign of a handle again.
     *
     * Artifacts before the getter (contract 1) returned the session pointer, which Android's
     * pointer tagging turns negative; contract 2 hands out positive registry ids. The report
     * records the answer, and [open] only reads a negative result as a failure by its shape when
     * the artifact still speaks the older contract.
     */
    val handleContractVersion: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!available) {
            0
        } else {
            runCatching { nativeDemuxHandleContractVersion() }.getOrDefault(LEGACY_HANDLE_CONTRACT)
        }
    }

    /** True when every negative open result is a classified failure status. */
    val registryHandles: Boolean get() = handleContractVersion >= REGISTRY_HANDLE_CONTRACT

    fun registerBluRaySource(source: Any): Long {
        check(discNavigationAvailable) { "YCore Blu-ray runtime is not installed" }
        return nativeRegisterBluRaySource(source).also { handle ->
            check(handle > 0L) { "YCore Blu-ray source was not registered" }
        }
    }

    fun unregisterBluRaySource(handle: Long) {
        if (handle > 0L && discNavigationAvailable) nativeUnregisterBluRaySource(handle)
    }

    fun selectDiscTitle(
        handle: Long,
        index: Int,
    ): Boolean = handle > 0L && index >= 0 && nativeSelectDiscTitle(handle, index)

    fun discChapterStartMs(
        handle: Long,
        index: Int,
    ): Long? =
        if (handle > 0L && index >= 0) {
            nativeDiscChapterStartMs(handle, index).takeIf { it >= 0L }
        } else {
            null
        }

    fun selectDiscAngle(
        handle: Long,
        index: Int,
    ): Boolean = handle > 0L && index >= 0 && nativeSelectDiscAngle(handle, index)

    fun sendDiscMenuCommand(
        handle: Long,
        command: Int,
    ): Boolean = handle > 0L && command in 0..6 && nativeSendDiscMenuCommand(handle, command)

    fun selectDiscMenuPoint(
        handle: Long,
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean =
        handle > 0L &&
            x >= 0 &&
            y >= 0 &&
            nativeSelectDiscMenuPoint(handle, x, y, activate)

    fun open(
        uri: String,
        headers: Map<String, String>,
        probeOnly: Boolean = false,
    ): Long {
        check(available) { "YCore FFmpeg demux bridge is not installed" }
        val entries = headers.entries.toList()
        val names = entries.map { it.key }.toTypedArray()
        val values = entries.map { it.value }.toTypedArray()
        val status =
            if (probeOnly) {
                // Older native artifacts predate the bounded probe entry point; the full open
                // answers the same questions, only slower.
                try {
                    nativeOpenProbe(uri, names, values)
                } catch (_: UnsatisfiedLinkError) {
                    nativeOpen(uri, names, values)
                }
            } else {
                nativeOpen(uri, names, values)
            }
        return status.also { handle ->
            val failed = if (registryHandles) handle < 0L else isFfmpegOpenFailure(handle)
            if (failed) {
                throwFfmpegFailure(handle, YPlaybackFailureStage.SourceOpen, lastOpenFailureDetail())
            }
            check(handle != 0L) { "YCore FFmpeg demux session was not created" }
        }
    }

    /**
     * FFmpeg's own reason for the open failure that just returned a negative status.
     *
     * Older native artifacts predate the getter, so a missing symbol degrades to the classified
     * status alone instead of failing the open a second time. The text is FFmpeg's `av_strerror`
     * output plus the stage name; it never contains the source URI or headers.
     */
    private fun lastOpenFailureDetail(): String? =
        runCatching { nativeLastOpenFailure() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    fun close(handle: Long) {
        if (handle != 0L && available) nativeClose(handle)
    }

    fun trackCount(handle: Long): Int = nativeTrackCount(handle)

    fun containerName(handle: Long): String? = nativeContainerName(handle)

    fun durationUs(handle: Long): Long? = nativeDurationUs(handle).timestampOrNull()

    fun bitRateBitsPerSecond(handle: Long): Long = nativeBitRateBitsPerSecond(handle).coerceAtLeast(0L)

    fun trackType(
        handle: Long,
        index: Int,
    ): Int = nativeTrackType(handle, index)

    fun trackCodecName(
        handle: Long,
        index: Int,
    ): String? = nativeTrackCodecName(handle, index)

    fun trackVideoInfo(
        handle: Long,
        index: Int,
    ): LongArray = checkNotNull(nativeTrackVideoInfo(handle, index)) { "FFmpeg video metadata is unavailable" }

    fun trackAudioInfo(
        handle: Long,
        index: Int,
    ): LongArray = checkNotNull(nativeTrackAudioInfo(handle, index)) { "FFmpeg audio metadata is unavailable" }

    fun trackLanguage(
        handle: Long,
        index: Int,
    ): String? = nativeTrackLanguage(handle, index)

    fun trackTitle(
        handle: Long,
        index: Int,
    ): String? = nativeTrackTitle(handle, index)

    fun trackExtradata(
        handle: Long,
        index: Int,
    ): ByteArray? = nativeTrackExtradata(handle, index)

    fun trackDolbyConfig(
        handle: Long,
        index: Int,
    ): IntArray? = nativeTrackDolbyConfig(handle, index)

    fun trackHdrStaticInfo(
        handle: Long,
        index: Int,
    ): IntArray? = nativeTrackHdrStaticInfo(handle, index)

    fun selectTracks(
        handle: Long,
        indexes: IntArray,
    ) = nativeSelectTracks(handle, indexes)

    fun readPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReadPacket(handle, target)) { "FFmpeg packet result is unavailable" }
            .also { result ->
                result.firstOrNull()?.takeIf { it <= FFMPEG_FAILURE_AUTHORIZATION }?.let { status ->
                    throwFfmpegFailure(status, YPlaybackFailureStage.Demux)
                }
            }

    fun decodeSubtitle(
        handle: Long,
        trackIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        durationUs: Long?,
    ): ByteArray? =
        nativeDecodeSubtitle(
            handle,
            trackIndex,
            data,
            presentationTimeUs,
            durationUs ?: Long.MIN_VALUE,
        )

    fun configureSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
        toneMapHdrToSdr: Boolean,
    ) {
        check(softwareDecodeAvailable) { "YCore FFmpeg software decoder is not installed" }
        nativeConfigureSoftwareDecoder(handle, trackIndex, toneMapHdrToSdr)
    }

    fun sendSoftwarePacket(
        handle: Long,
        trackIndex: Int,
        data: ByteArray?,
        presentationTimeUs: Long?,
        decodeTimeUs: Long?,
    ): Boolean =
        nativeSendSoftwarePacket(
            handle,
            trackIndex,
            data,
            presentationTimeUs ?: Long.MIN_VALUE,
            decodeTimeUs ?: presentationTimeUs ?: Long.MIN_VALUE,
        ) == SOFTWARE_PACKET_ACCEPTED

    fun receiveSoftwareVideoFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReceiveSoftwareVideoFrame(handle, trackIndex, target)) {
            "FFmpeg software video result is unavailable"
        }

    fun receiveSoftwareAudioFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReceiveSoftwareAudioFrame(handle, trackIndex, target)) {
            "FFmpeg software audio result is unavailable"
        }

    fun flushSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    ) = nativeFlushSoftwareDecoder(handle, trackIndex)

    fun seek(
        handle: Long,
        positionUs: Long,
    ) {
        val status = nativeSeek(handle, positionUs.coerceAtLeast(0L))
        if (status < 0) throwFfmpegFailure(status.toLong(), YPlaybackFailureStage.Seek)
    }

    private external fun nativeOpen(
        uri: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
    ): Long

    /** Same contract as [nativeOpen] with FFmpeg's stream analysis bounded for metadata probes. */
    private external fun nativeOpenProbe(
        uri: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
    ): Long

    private external fun nativeLastOpenFailure(): String?

    private external fun nativeDemuxHandleContractVersion(): Int

    private external fun nativeClose(handle: Long)

    private external fun nativeTrackCount(handle: Long): Int

    private external fun nativeContainerName(handle: Long): String?

    private external fun nativeDurationUs(handle: Long): Long

    private external fun nativeBitRateBitsPerSecond(handle: Long): Long

    private external fun nativeTrackType(
        handle: Long,
        index: Int,
    ): Int

    private external fun nativeTrackCodecName(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackVideoInfo(
        handle: Long,
        index: Int,
    ): LongArray?

    private external fun nativeTrackAudioInfo(
        handle: Long,
        index: Int,
    ): LongArray?

    private external fun nativeTrackLanguage(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackTitle(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackExtradata(
        handle: Long,
        index: Int,
    ): ByteArray?

    private external fun nativeTrackDolbyConfig(
        handle: Long,
        index: Int,
    ): IntArray?

    private external fun nativeTrackHdrStaticInfo(
        handle: Long,
        index: Int,
    ): IntArray?

    private external fun nativeSelectTracks(
        handle: Long,
        indexes: IntArray,
    )

    private external fun nativeReadPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeDecodeSubtitle(
        handle: Long,
        trackIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        durationUs: Long,
    ): ByteArray?

    private external fun nativeSoftwareDecoderApiVersion(): Int

    private external fun nativeAssRendererApiVersion(): Int

    private external fun nativeDiscApiVersion(): Int

    private external fun nativeRegisterBluRaySource(source: Any): Long

    private external fun nativeUnregisterBluRaySource(handle: Long)

    private external fun nativeSelectDiscTitle(
        handle: Long,
        index: Int,
    ): Boolean

    private external fun nativeDiscChapterStartMs(
        handle: Long,
        index: Int,
    ): Long

    private external fun nativeSelectDiscAngle(
        handle: Long,
        index: Int,
    ): Boolean

    private external fun nativeSendDiscMenuCommand(
        handle: Long,
        command: Int,
    ): Boolean

    private external fun nativeSelectDiscMenuPoint(
        handle: Long,
        x: Int,
        y: Int,
        activate: Boolean,
    ): Boolean

    private external fun nativeConfigureSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
        toneMapHdrToSdr: Boolean,
    )

    private external fun nativeSendSoftwarePacket(
        handle: Long,
        trackIndex: Int,
        data: ByteArray?,
        presentationTimeUs: Long,
        decodeTimeUs: Long,
    ): Int

    private external fun nativeReceiveSoftwareVideoFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeReceiveSoftwareAudioFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeFlushSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    )

    private external fun nativeSeek(
        handle: Long,
        positionUs: Long,
    ): Int
}

private fun throwFfmpegFailure(
    status: Long,
    stage: YPlaybackFailureStage,
    detail: String? = null,
): Nothing =
    throw YPlaybackException(
        category = ffmpegFailureCategory(status),
        stage = stage,
        safeDetail =
            buildString {
                append("FFmpeg ")
                append(stage.name)
                append(" returned a classified failure")
                (detail ?: ffmpegFailureDetail(status))?.let { reason ->
                    append(" (")
                    append(reason)
                    append(')')
                }
            },
    )

/**
 * Whether a native open result is a classified failure status rather than a session handle.
 *
 * Failure statuses are the bare -1..-4 classes or the stage-packed form, whose magnitude fits in
 * 48 bits. Session handles are positive registry ids from the current native artifact, but the
 * artifacts shipped up to 1.0.28 returned the session pointer itself, and Android 11+ tags every
 * arm64 heap pointer in its top byte (`0xb4...`), so such a handle arrives as a large negative
 * number. Treating it as a status decoded the pointer bits into a random failure class and stage,
 * reported a playable source as unplayable, and leaked the open session with its connection.
 */
internal fun isFfmpegOpenFailure(status: Long): Boolean = status < 0L && status >= -FFMPEG_OPEN_STATUS_LIMIT

internal fun ffmpegFailureCategory(status: Long): YPlaybackFailureCategory =
    when (ffmpegFailureClass(status)) {
        FFMPEG_FAILURE_AUTHORIZATION -> YPlaybackFailureCategory.Authorization
        FFMPEG_FAILURE_NETWORK -> YPlaybackFailureCategory.Network
        FFMPEG_FAILURE_CONTAINER -> YPlaybackFailureCategory.Container
        // Any other class byte means the native artifact packs its status differently from this
        // bridge (a production package can carry an older libycore_demux.so than the source tree).
        // Such a status must stay Unknown: calling it a container failure told the user the file
        // could not be parsed and let the failure ledger penalise the route for that container
        // and codec, so a flapping server taught the device to avoid ordinary HEVC MKV.
        else -> YPlaybackFailureCategory.Unknown
    }

/**
 * The failure class of a native status. Read and seek paths still return the bare -2/-3/-4
 * classes; the open path packs the class into bits 32-39 together with the open stage in bits
 * 40-47 and the AVERROR magnitude in the low 32 bits, so the bundle can name the failing call.
 */
internal fun ffmpegFailureClass(status: Long): Long =
    if (status >= FFMPEG_FAILURE_CONTAINER) status else -((-status ushr 32) and 0xFFL)

/** Human-readable stage and error for a packed open status; null for the bare legacy classes. */
internal fun ffmpegFailureDetail(status: Long): String? {
    if (status >= FFMPEG_FAILURE_CONTAINER) return null
    val packed = -status
    val stage =
        when (((packed ushr 40) and 0xFFL).toInt()) {
            FFMPEG_OPEN_STAGE_DISC -> "disc_open"
            FFMPEG_OPEN_STAGE_OPEN_INPUT -> "open_input"
            FFMPEG_OPEN_STAGE_STREAM_INFO -> "find_stream_info"
            else -> "unknown"
        }
    val failureClass = ffmpegFailureClass(status)
    val recognizedClass =
        failureClass == FFMPEG_FAILURE_AUTHORIZATION ||
            failureClass == FFMPEG_FAILURE_NETWORK ||
            failureClass == FFMPEG_FAILURE_CONTAINER
    return buildString {
        append("stage=")
        append(stage)
        if (!recognizedClass) {
            // Keep the raw status: an unrecognised layout is only decodable against the native
            // artifact that produced it, and the report already names that artifact's hash.
            append(" class=")
            append(-failureClass)
            append(" raw=")
            append(status)
        }
        append(" error=")
        append(ffmpegErrorLabel(packed and 0xFFFF_FFFFL))
    }
}

/**
 * Labels an AVERROR magnitude: small values are errno numbers, larger ones are FFmpeg's
 * four-character tags (little-endian, a leading 0xF8 byte marks the reserved "!" family, as in
 * `!404` or `!DEM`).
 */
internal fun ffmpegErrorLabel(magnitude: Long): String {
    if (magnitude < FFMPEG_ERRNO_LIMIT) return "errno:$magnitude"
    val bytes = (0 until 4).map { index -> ((magnitude ushr (8 * index)) and 0xFFL).toInt() }
    val printable = bytes.drop(1).all { it in 0x20..0x7E }
    if (!printable) return "code:$magnitude"
    val head = if (bytes[0] == FFMPEG_RESERVED_TAG_BYTE) "!" else bytes[0].toChar().toString()
    if (bytes[0] != FFMPEG_RESERVED_TAG_BYTE && bytes[0] !in 0x20..0x7E) return "code:$magnitude"
    return "tag:" + head + bytes.drop(1).joinToString("") { it.toChar().toString() }
}

internal fun Long.timestampOrNull(): Long? = takeUnless { it == Long.MIN_VALUE }

internal const val FFMPEG_PACKET_EOF = 0L
internal const val FFMPEG_PACKET_DATA = 1L
internal const val FFMPEG_PACKET_GROW_BUFFER = -1L
internal const val FFMPEG_FAILURE_AUTHORIZATION = -2L
internal const val FFMPEG_FAILURE_NETWORK = -3L
internal const val FFMPEG_FAILURE_CONTAINER = -4L
internal const val FFMPEG_OPEN_STAGE_DISC = 1
internal const val FFMPEG_OPEN_STAGE_OPEN_INPUT = 2
internal const val FFMPEG_OPEN_STAGE_STREAM_INFO = 3

/** Packed open statuses use bits 0-31 (AVERROR), 32-39 (class) and 40-47 (stage); nothing above. */
internal const val FFMPEG_OPEN_STATUS_LIMIT = 1L shl 48

/** Artifacts that predate [FfmpegNativeBridge.handleContractVersion] return session pointers. */
internal const val LEGACY_HANDLE_CONTRACT = 1
internal const val REGISTRY_HANDLE_CONTRACT = 2
private const val FFMPEG_ERRNO_LIMIT = 0x1000L
private const val FFMPEG_RESERVED_TAG_BYTE = 0xF8
internal const val FFMPEG_SAMPLE_SYNC = 1L shl 0
internal const val FFMPEG_SAMPLE_ENCRYPTED = 1L shl 1
internal const val FFMPEG_TRACK_VIDEO = 1
internal const val FFMPEG_TRACK_AUDIO = 2
internal const val FFMPEG_TRACK_SUBTITLE = 3
internal const val FFMPEG_TRACK_DATA = 4
internal const val FFMPEG_HDR_PQ = 1L
internal const val FFMPEG_HDR_HLG = 2L
internal const val FFMPEG_HDR10_PLUS = 3L
internal const val FFMPEG_PACKING_ANNEX_B = 1L
internal const val FFMPEG_PACKING_LENGTH_PREFIXED = 2L
private const val LIBRARY_NAME = "ycore_demux"
private const val SOFTWARE_DECODER_API_VERSION = 2
private const val ASS_RENDERER_API_VERSION = 1
private const val DISC_API_VERSION = 2
private const val SOFTWARE_PACKET_ACCEPTED = 0
