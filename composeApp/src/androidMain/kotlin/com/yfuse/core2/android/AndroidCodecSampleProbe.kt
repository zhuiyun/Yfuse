package com.yfuse.core2.android

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import com.yfuse.core2.api.YMediaItem
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bounded content-backed runtime probe: queue real compressed access units from the selected item,
 * decode them with the planned decoder and require a frame-render callback from a private Surface.
 */
internal class AndroidCodecSampleProbe(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun probe(
        item: YMediaItem,
        decoderName: String,
    ): YCodecConfigurationProbeResult {
        val demuxer = AndroidMediaExtractorDemuxNode(appContext)
        var codec: MediaCodec? = null
        var imageReader: ImageReader? = null
        var callbackThread: HandlerThread? = null
        var configured = false
        return try {
            demuxer.open(
                YAndroidMediaSource(
                    uri = item.uri,
                    headers = item.headers,
                    credentials = item.transportCredentials,
                    cacheIdentity = item.cacheIdentity,
                    cacheMaximumBytes = item.cacheMaximumBytes,
                ),
            )
            val trackIndex = demuxer.findFirstTrack("video/") ?: return YCodecConfigurationProbeResult.Inconclusive
            val format = demuxer.trackFormat(trackIndex)
            val width = format.integerOr(PROBE_WIDTH_KEY, MIN_PROBE_DIMENSION).coerceAtLeast(MIN_PROBE_DIMENSION)
            val height = format.integerOr(PROBE_HEIGHT_KEY, MIN_PROBE_DIMENSION).coerceAtLeast(MIN_PROBE_DIMENSION)
            imageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, PROBE_SURFACE_IMAGES)
            callbackThread = HandlerThread("YCore-codec-probe").apply { start() }
            val rendered = CountDownLatch(1)
            codec = MediaCodec.createByCodecName(decoderName)
            codec.setOnFrameRenderedListener(
                { _, _, _ -> rendered.countDown() },
                Handler(callbackThread.looper),
            )
            codec.configure(format, imageReader.surface, null, 0)
            codec.start()
            configured = true
            demuxer.selectTrack(trackIndex)
            val sampleBuffer =
                ByteBuffer.allocateDirect(
                    format.maxInputSizeOr(DEFAULT_SAMPLE_BUFFER_BYTES).coerceAtMost(MAX_SAMPLE_BUFFER_BYTES),
                )
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_TIMEOUT_MS)
            var queuedSamples = 0
            while (System.nanoTime() < deadline && queuedSamples < MAX_PROBE_SAMPLES) {
                val inputIndex = codec.dequeueInputBuffer(PROBE_DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val sample = demuxer.readSample(sampleBuffer) ?: break
                    val input = codec.getInputBuffer(inputIndex) ?: return YCodecConfigurationProbeResult.Inconclusive
                    input.clear()
                    if (sample.data.remaining() > input.remaining()) return YCodecConfigurationProbeResult.Inconclusive
                    input.put(sample.data.duplicate())
                    codec.queueInputBuffer(inputIndex, 0, sample.data.remaining(), sample.presentationTimeUs, 0)
                    queuedSamples++
                    demuxer.advance()
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, PROBE_DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val render = info.size > 0
                            codec.releaseOutputBuffer(outputIndex, render)
                            if (render && rendered.await(RENDER_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                return YCodecConfigurationProbeResult.Rendered
                            }
                        }
                    }
                }
            }
            if (configured) YCodecConfigurationProbeResult.Configured else YCodecConfigurationProbeResult.Inconclusive
        } catch (error: MediaCodec.CodecException) {
            if (configured || error.isRecoverable || error.isTransient) {
                YCodecConfigurationProbeResult.Inconclusive
            } else {
                YCodecConfigurationProbeResult.Rejected
            }
        } catch (_: IOException) {
            // Source/demux I/O says nothing about decoder support; never poison runtime evidence.
            YCodecConfigurationProbeResult.Inconclusive
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            YCodecConfigurationProbeResult.Inconclusive
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { imageReader?.close() }
            callbackThread?.quitSafely()
            runCatching { callbackThread?.join(CALLBACK_THREAD_JOIN_MS) }
            demuxer.release()
        }
    }
}

private fun android.media.MediaFormat.integerOr(
    key: String,
    fallback: Int,
): Int = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

private const val PROBE_WIDTH_KEY = "width"
private const val PROBE_HEIGHT_KEY = "height"
private const val MIN_PROBE_DIMENSION = 16
private const val PROBE_SURFACE_IMAGES = 2
private const val DEFAULT_SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
private const val MAX_PROBE_SAMPLES = 24
private const val PROBE_TIMEOUT_MS = 2_000L
private const val PROBE_DEQUEUE_TIMEOUT_US = 10_000L
private const val RENDER_CALLBACK_TIMEOUT_MS = 120L
private const val CALLBACK_THREAD_JOIN_MS = 500L
