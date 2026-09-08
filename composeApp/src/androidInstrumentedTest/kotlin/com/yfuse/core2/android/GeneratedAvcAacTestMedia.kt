package com.yfuse.core2.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/** Locally generated frame-marked AVC plus ten seconds of silent mono AAC; no external media is read. */
internal object GeneratedAvcAacTestMedia {
    private const val SAMPLE_RATE = 48_000
    private const val SAMPLE_COUNT = SAMPLE_RATE * 10
    private const val CHANNELS = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val AAC_FRAME_SAMPLES = 1_024
    private const val TIMEOUT_MS = 20_000L
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** The caller owns the final file; all intermediate files and native resources are closed here. */
    fun create(cacheDirectory: File): File {
        val deadlineMs = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var video: File? = null
        var audio: File? = null
        var result: File? = null
        var complete = false
        try {
            video = GeneratedAvcTestMedia.create(cacheDirectory, frameMarkers = true)
            checkDeadline(deadlineMs)
            audio = File.createTempFile("ycore-generated-aac-", ".m4a", cacheDirectory)
            encodeAudio(audio, deadlineMs)
            result = File.createTempFile("ycore-generated-avc-aac-", ".mp4", cacheDirectory)
            combine(video, audio, result, deadlineMs)
            checkDeadline(deadlineMs)
            check(result.length() > 0) { "Generated AVC/AAC fixture is empty" }
            complete = true
            return result
        } finally {
            video?.delete()
            audio?.delete()
            if (!complete) result?.delete()
        }
    }

    private fun encodeAudio(
        file: File,
        deadlineMs: Long,
    ) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        try {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_FRAME_SAMPLES * BYTES_PER_SAMPLE)
                }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            encoder.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            val silence = ByteArray(AAC_FRAME_SAMPLES * BYTES_PER_SAMPLE)
            var samplesQueued = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            var written = 0
            while (!outputEnded) {
                checkDeadline(deadlineMs)
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val timestampUs = samplesQueued * 1_000_000L / SAMPLE_RATE
                        if (samplesQueued == SAMPLE_COUNT) {
                            encoder.queueInputBuffer(index, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val buffer = checkNotNull(encoder.getInputBuffer(index))
                            buffer.clear()
                            val samples =
                                minOf(
                                    AAC_FRAME_SAMPLES,
                                    SAMPLE_COUNT - samplesQueued,
                                    buffer.remaining() / BYTES_PER_SAMPLE,
                                )
                            check(samples > 0) { "AAC encoder input buffer cannot hold PCM samples" }
                            val bytes = samples * BYTES_PER_SAMPLE
                            buffer.put(silence, 0, bytes)
                            encoder.queueInputBuffer(index, 0, bytes, timestampUs, 0)
                            samplesQueued += samples
                        }
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC format changed after muxing started" }
                        track = output.addTrack(encoder.outputFormat)
                        output.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        check(index >= 0) { "Unexpected AAC encoder status: $index" }
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                check(muxerStarted) { "AAC sample arrived before output format" }
                                val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                output.writeSampleData(track, buffer, info)
                                written++
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            check(inputEnded && samplesQueued == SAMPLE_COUNT && written > 0) { "Generated AAC fixture is incomplete" }
            checkDeadline(deadlineMs)
            output.stop()
            muxerStarted = false
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun combine(
        video: File,
        audio: File,
        output: File,
        deadlineMs: Long,
    ) {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val audioInput = MediaExtractor().also { audioExtractor = it }
            videoExtractor.setDataSource(video.absolutePath)
            audioInput.setDataSource(audio.absolutePath)
            val videoIndex = findTrack(videoExtractor, "video/")
            val audioIndex = findTrack(audioInput, "audio/")
            videoExtractor.selectTrack(videoIndex)
            audioInput.selectTrack(audioIndex)
            val target = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = target
            val videoTrack = target.addTrack(videoExtractor.getTrackFormat(videoIndex))
            val audioTrack = target.addTrack(audioInput.getTrackFormat(audioIndex))
            target.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var writtenVideo = 0
            var writtenAudio = 0
            while (videoExtractor.sampleTime >= 0 || audioInput.sampleTime >= 0) {
                checkDeadline(deadlineMs)
                val useVideo =
                    videoExtractor.sampleTime >= 0 &&
                        (audioInput.sampleTime < 0 || videoExtractor.sampleTime <= audioInput.sampleTime)
                val input = if (useVideo) videoExtractor else audioInput
                buffer.clear()
                val size = input.readSampleData(buffer, 0)
                check(size in 1..buffer.capacity()) { "Generated sample does not fit the mux buffer" }
                val flags =
                    if (input.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC !=
                        0
                    ) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                info.set(0, size, input.sampleTime, flags)
                buffer.position(0)
                buffer.limit(size)
                target.writeSampleData(if (useVideo) videoTrack else audioTrack, buffer, info)
                if (useVideo) writtenVideo++ else writtenAudio++
                input.advance()
            }
            check(writtenVideo > 0 && writtenAudio > 0) { "Generated fixture is missing an audio/video track" }
            target.stop()
            started = false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun findTrack(
        extractor: MediaExtractor,
        prefix: String,
    ): Int =
        checkNotNull(
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            },
        ) { "Generated fixture is missing its $prefix track" }

    private fun checkDeadline(deadlineMs: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Generated AVC/AAC fixture interrupted")
        check(SystemClock.elapsedRealtime() < deadlineMs) { "Generated AVC/AAC fixture exceeded ${TIMEOUT_MS}ms" }
    }
}
