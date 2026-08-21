package com.yfuse.core2.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi
import com.yfuse.core2.graph.YVideoDecodeNode
import java.nio.ByteBuffer

/** Result of a non-blocking compressed-sample enqueue. */
internal enum class YCodecQueueResult {
    Queued,
    TryAgain,
}

internal sealed interface YCodecOutputResult {
    data object TryAgain : YCodecOutputResult

    data class Buffer(
        val index: Int,
        val presentationTimeUs: Long,
        val flags: Int,
        val size: Int,
    ) : YCodecOutputResult {
        val endOfStream: Boolean get() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    data class FormatChanged(
        val format: MediaFormat,
    ) : YCodecOutputResult
}

internal sealed interface YVideoFrameReleaseDecision {
    data object Hold : YVideoFrameReleaseDecision

    data object Drop : YVideoFrameReleaseDecision

    data class Render(
        val releaseTimeNs: Long,
    ) : YVideoFrameReleaseDecision
}

/** Shared direct-Surface pacing policy. It drops only frames that can no longer meet the master clock. */
internal fun videoFrameReleaseDecision(
    presentationTimeUs: Long,
    masterPositionUs: Long,
    desiredReleaseTimeNs: Long,
    nowNs: Long,
    maximumScheduleAheadUs: Long,
    lateDropThresholdNs: Long,
    lateImmediateAllowanceNs: Long,
): YVideoFrameReleaseDecision {
    require(maximumScheduleAheadUs >= 0L)
    require(lateDropThresholdNs >= 0L)
    require(lateImmediateAllowanceNs >= 0L)
    if (presentationTimeUs - masterPositionUs > maximumScheduleAheadUs) {
        return YVideoFrameReleaseDecision.Hold
    }
    if (desiredReleaseTimeNs < nowNs - lateDropThresholdNs) {
        return YVideoFrameReleaseDecision.Drop
    }
    return YVideoFrameReleaseDecision.Render(
        releaseTimeNs = desiredReleaseTimeNs.coerceAtLeast(nowNs - lateImmediateAllowanceNs),
    )
}

internal fun preserveFirstVideoFrame(
    decision: YVideoFrameReleaseDecision,
    firstFrameRendered: Boolean,
    nowNs: Long,
): YVideoFrameReleaseDecision =
    if (!firstFrameRendered && decision == YVideoFrameReleaseDecision.Drop) {
        YVideoFrameReleaseDecision.Render(nowNs)
    } else {
        decision
    }

/**
 * Core2's native video primitive: compressed access units enter MediaCodec and decoded output goes
 * straight to a Surface. No decoded YUV frame is copied back through the CPU or Compose.
 *
 * Direct mode dequeues/release buffers so the audio-master clock can schedule them. Tunnel mode is
 * different: the decoder may expose no app-owned output buffers at all, so callers observe its
 * sideband output with [setOnFrameRenderedListener] instead of draining it.
 */
internal class AndroidMediaCodecVideoNode(
    private val createDecoder: (String) -> MediaCodec = MediaCodec::createDecoderByType,
    private val createDecoderByName: (String) -> MediaCodec = MediaCodec::createByCodecName,
) : YVideoDecodeNode {
    override val name: String = "MediaCodec"

    private var codec: MediaCodec? = null
    private var started = false

    val decoderName: String? get() = codec?.name

    fun configure(
        format: MediaFormat,
        surface: Surface,
        decoderName: String? = null,
    ) {
        release()
        val mime =
            format.getString(MediaFormat.KEY_MIME)
                ?: error("Video MediaFormat is missing ${MediaFormat.KEY_MIME}")
        val decoder =
            createPlannedVideoDecoder(
                mime = mime,
                decoderName = decoderName,
                createByType = createDecoder,
                createByName = createDecoderByName,
            )
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            started = true
        } catch (throwable: Throwable) {
            runCatching { decoder.release() }
            throw throwable
        }
    }

    /** Changes the target without decoding through a texture or CPU buffer. */
    fun setOutputSurface(surface: Surface) {
        requireStartedCodec().setOutputSurface(surface)
    }

    /** Informational render evidence used by tunnel mode; never drives presentation timing. */
    fun setOnFrameRenderedListener(
        handler: Handler? = null,
        listener: ((presentationTimeUs: Long, nanoTime: Long) -> Unit)?,
    ) {
        val decoder = requireStartedCodec()
        decoder.setOnFrameRenderedListener(
            listener?.let { callback ->
                MediaCodec.OnFrameRenderedListener { _, presentationTimeUs, nanoTime ->
                    callback(presentationTimeUs, nanoTime)
                }
            },
            handler,
        )
    }

    /**
     * API 31+ evidence that a tunneled first frame is decoded and ready. Older releases simply do
     * not expose this callback, so callers must rely on the rendered-frame listener instead.
     */
    fun setOnFirstTunnelFrameReadyListener(
        handler: Handler? = null,
        listener: (() -> Unit)?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val decoder = requireStartedCodec()
        Api31TunnelFrameReadyListener.set(decoder, handler, listener)
        return true
    }

    /**
     * Queues one complete encoded access unit. The compressed sample is copied only into the codec
     * input buffer; decoded output remains on the Surface path.
     */
    fun queueAccessUnit(
        data: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int = 0,
    ): YCodecQueueResult {
        val decoder = requireStartedCodec()
        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex < 0) return YCodecQueueResult.TryAgain

        val input = decoder.getInputBuffer(inputIndex) ?: error("MediaCodec input buffer unavailable")
        input.clear()
        val sample = data.duplicate()
        val size = sample.remaining()
        require(size <= input.remaining()) {
            "Encoded access unit ($size bytes) exceeds MediaCodec input buffer (${input.remaining()} bytes)"
        }
        input.put(sample)
        decoder.queueInputBuffer(
            inputIndex,
            0,
            size,
            presentationTimeUs,
            flags.toCodecInputFlags(),
        )
        return YCodecQueueResult.Queued
    }

    /** Applies per-access-unit HDR10+ ITU-T T.35 metadata when the platform codec supports it. */
    fun setHdr10PlusMetadata(payload: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || payload.isEmpty()) return false
        return runCatching {
            requireStartedCodec().setParameters(
                Bundle().apply { putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, payload) },
            )
            true
        }.getOrDefault(false)
    }

    fun queueEndOfStream(presentationTimeUs: Long): YCodecQueueResult {
        val decoder = requireStartedCodec()
        val inputIndex = decoder.dequeueInputBuffer(0L)
        if (inputIndex < 0) return YCodecQueueResult.TryAgain
        decoder.queueInputBuffer(
            inputIndex,
            0,
            0,
            presentationTimeUs.coerceAtLeast(0L),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        return YCodecQueueResult.Queued
    }

    /** Non-blocking output dequeue. The caller owns the returned buffer until releaseOutput(). */
    fun dequeueOutput(): YCodecOutputResult {
        val decoder = requireStartedCodec()
        val info = MediaCodec.BufferInfo()
        return when (val outputIndex = decoder.dequeueOutputBuffer(info, 0L)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> YCodecOutputResult.TryAgain
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                YCodecOutputResult.FormatChanged(decoder.outputFormat)
            else -> {
                if (outputIndex < 0) return YCodecOutputResult.TryAgain
                YCodecOutputResult.Buffer(
                    index = outputIndex,
                    presentationTimeUs = info.presentationTimeUs,
                    flags = info.flags,
                    size = info.size,
                )
            }
        }
    }

    /**
     * Releases a decoded frame directly to the Surface. A non-null [renderTimeNs] uses Android's
     * timed release API; null renders immediately. `render=false` discards without touching a GPU.
     */
    fun releaseOutput(
        output: YCodecOutputResult.Buffer,
        render: Boolean,
        renderTimeNs: Long? = null,
    ) {
        val decoder = requireStartedCodec()
        if (!render) {
            decoder.releaseOutputBuffer(output.index, false)
        } else if (renderTimeNs != null) {
            decoder.releaseOutputBuffer(output.index, renderTimeNs)
        } else {
            decoder.releaseOutputBuffer(output.index, true)
        }
    }

    override fun flush() {
        if (started) codec?.flush()
    }

    override fun release() {
        val decoder = codec
        codec = null
        val wasStarted = started
        started = false
        if (decoder != null) {
            if (wasStarted) runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun requireStartedCodec(): MediaCodec =
        checkNotNull(codec).also {
            check(started) { "MediaCodec video node has not been configured" }
        }
}

/** Keeps API 31-only verifier types out of [AndroidMediaCodecVideoNode] on Android 10 and older. */
@RequiresApi(Build.VERSION_CODES.S)
private object Api31TunnelFrameReadyListener {
    fun set(
        codec: MediaCodec,
        handler: Handler?,
        listener: (() -> Unit)?,
    ) {
        codec.setOnFirstTunnelFrameReadyListener(
            handler,
            listener?.let { callback ->
                MediaCodec.OnFirstTunnelFrameReadyListener { callback() }
            },
        )
    }
}

internal fun <T> createPlannedVideoDecoder(
    mime: String,
    decoderName: String?,
    createByType: (String) -> T,
    createByName: (String) -> T,
): T =
    decoderName
        ?.takeIf(String::isNotBlank)
        ?.let(createByName)
        ?: createByType(mime)

internal fun emptyTailSeekRetryTarget(
    currentTargetUs: Long,
    retryCount: Int,
    maxRetries: Int = DEFAULT_EMPTY_TAIL_SEEK_RETRIES,
    retryStepUs: Long = DEFAULT_EMPTY_TAIL_SEEK_RETRY_STEP_US,
): Long? {
    require(currentTargetUs >= 0L)
    require(retryCount >= 0)
    require(maxRetries >= 0)
    require(retryStepUs > 0L)
    if (currentTargetUs == 0L || retryCount >= maxRetries) return null
    return (currentTargetUs - retryStepUs).coerceAtLeast(0L)
}

/** MediaExtractor's SYNC bit matches MediaCodec's key-frame bit; encrypted samples are not queued here. */
private fun Int.toCodecInputFlags(): Int =
    if (this and MediaExtractorFlags.ENCRYPTED != 0) {
        error("Encrypted samples require a MediaCrypto queue path")
    } else {
        if (this and MediaExtractorFlags.SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }

/** Kept local so the video node does not depend on MediaExtractor at runtime. */
private object MediaExtractorFlags {
    const val SYNC = 1
    const val ENCRYPTED = 2
}

private const val DEFAULT_EMPTY_TAIL_SEEK_RETRY_STEP_US = 1_000_000L
private const val DEFAULT_EMPTY_TAIL_SEEK_RETRIES = 3
