package com.yfuse.core2.android

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** Creates synthetic pixels locally; callers must delete the returned fixture after playback. */
internal object GeneratedAvcTestMedia {
    const val WIDTH = 320
    const val HEIGHT = 180
    const val FRAME_RATE = 10
    const val BIT_RATE = 250_000
    private const val FRAME_COUNT = FRAME_RATE * 10
    private const val TIMEOUT_MS = 20_000L
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    fun create(cacheDirectory: File): File {
        val deadlineMs = SystemClock.elapsedRealtime() + TIMEOUT_MS
        val mediaFile = File.createTempFile("ycore-generated-avc-", ".mp4", cacheDirectory)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var complete = false
        try {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            val encoderName =
                checkNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)) {
                    "No AVC encoder supports the generated YUV420 smoke fixture"
                }
            val encoder = MediaCodec.createByCodecName(encoderName)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val outputMuxer = MediaMuxer(mediaFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = outputMuxer
            encoder.start()
            codecStarted = true
            val bufferInfo = MediaCodec.BufferInfo()
            var inputFrame = 0
            var inputEnded = false
            var outputEnded = false
            var outputTrack = -1
            var samplesWritten = 0
            while (!outputEnded) {
                checkEncodingDeadline(deadlineMs, inputFrame, samplesWritten)
                if (!inputEnded) {
                    val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val presentationTimeUs = inputFrame * 1_000_000L / FRAME_RATE
                        if (inputFrame == FRAME_COUNT) {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            // Read the allocation size before getInputImage invalidates the ByteBuffer view.
                            // The allocation includes vendor stride padding; Image describes the actual pixels.
                            val inputSize = checkNotNull(encoder.getInputBuffer(inputIndex)).capacity()
                            val inputImage = checkNotNull(encoder.getInputImage(inputIndex))
                            try {
                                fillSolidColor(inputImage)
                            } finally {
                                inputImage.close()
                            }
                            encoder.queueInputBuffer(inputIndex, 0, inputSize, presentationTimeUs, 0)
                            inputFrame += 1
                        }
                    }
                }
                when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AVC encoder changed format after muxing started" }
                        outputTrack = outputMuxer.addTrack(encoder.outputFormat)
                        outputMuxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        check(outputIndex >= 0) { "Unexpected AVC encoder status: $outputIndex" }
                        try {
                            val codecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!codecConfig && bufferInfo.size > 0) {
                                check(muxerStarted) { "AVC sample arrived before its output format" }
                                val outputBuffer = checkNotNull(encoder.getOutputBuffer(outputIndex))
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputMuxer.writeSampleData(outputTrack, outputBuffer, bufferInfo)
                                samplesWritten += 1
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            checkEncodingDeadline(deadlineMs, inputFrame, samplesWritten)
            check(inputEnded && inputFrame == FRAME_COUNT && samplesWritten == FRAME_COUNT) {
                "Incomplete generated AVC fixture: input=$inputFrame output=$samplesWritten"
            }
            outputMuxer.stop()
            muxerStarted = false
            check(mediaFile.length() > 0L) { "Generated AVC fixture is empty" }
            complete = true
            return mediaFile
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!complete) mediaFile.delete()
        }
    }

    private fun checkEncodingDeadline(
        deadlineMs: Long,
        inputFrames: Int,
        outputFrames: Int,
    ) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Generated AVC encoding interrupted")
        check(SystemClock.elapsedRealtime() < deadlineMs) {
            "Generated AVC encoding exceeded ${TIMEOUT_MS}ms: input=$inputFrames output=$outputFrames"
        }
    }

    private fun fillSolidColor(image: Image) {
        check(image.format == ImageFormat.YUV_420_888) { "Unexpected encoder image format: ${image.format}" }
        val crop = image.cropRect
        check(crop.width() == WIDTH && crop.height() == HEIGHT) { "Unexpected encoder crop: $crop" }
        check(crop.left % 2 == 0 && crop.top % 2 == 0) { "Unaligned encoder crop: $crop" }
        check(image.planes.size == 3) { "Expected three YUV planes" }
        image.planes.forEachIndexed { planeIndex, plane ->
            val subsample = if (planeIndex == 0) 1 else 2
            val width = WIDTH / subsample
            val height = HEIGHT / subsample
            val buffer = plane.buffer.duplicate()
            val base =
                buffer.position() +
                    (crop.top / subsample) * plane.rowStride +
                    (crop.left / subsample) * plane.pixelStride
            val lastPixel = base + (height - 1) * plane.rowStride + (width - 1) * plane.pixelStride
            check(plane.rowStride > 0 && plane.pixelStride > 0 && base >= 0 && lastPixel < buffer.limit()) {
                "Encoder YUV plane $planeIndex does not contain its advertised pixels"
            }
            // Every frame is a neutral gray generated from constants; no screen or media data is read.
            val value = (if (planeIndex == 0) 96 else 128).toByte()
            repeat(height) { row ->
                val rowOffset = base + row * plane.rowStride
                repeat(width) { column -> buffer.put(rowOffset + column * plane.pixelStride, value) }
            }
        }
    }
}
