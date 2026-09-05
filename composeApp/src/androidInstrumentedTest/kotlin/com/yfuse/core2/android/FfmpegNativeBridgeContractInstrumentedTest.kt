package com.yfuse.core2.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * The one check no unit test can make: that the bundled `libycore_demux.so` and this build's
 * Kotlin bridge agree on what an open returns.
 *
 * A 1.0.28 device shipped with an artifact that returned the session pointer as the handle;
 * Android's pointer tagging made it negative and the bridge reported every playable file as an
 * open failure. The file is generated on the device, so the test needs no corpus and runs in the
 * ordinary device lane before any release.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegNativeBridgeContractInstrumentedTest {
    @Test
    fun bundled_demux_artifact_opens_a_generated_file() {
        assumeTrue("libycore_demux.so is not bundled in this build", FfmpegNativeBridge.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "ycore-demux-contract-smoke.mp4")
        try {
            writeTinyAvcMp4(file)
            val handle = FfmpegNativeBridge.open("file://${file.absolutePath}", emptyMap())
            try {
                if (FfmpegNativeBridge.registryHandles) {
                    assertTrue("registry contract must hand out positive ids, got $handle", handle > 0L)
                } else {
                    assertTrue("legacy artifact must not produce a zero handle", handle != 0L)
                }
                assertEquals(1, FfmpegNativeBridge.trackCount(handle))
            } finally {
                FfmpegNativeBridge.close(handle)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun bundled_demux_artifact_declares_a_handle_contract_this_bridge_knows() {
        assumeTrue("libycore_demux.so is not bundled in this build", FfmpegNativeBridge.available)
        val version = FfmpegNativeBridge.handleContractVersion
        assertTrue(
            "unknown demux handle contract $version; update FfmpegNativeBridge before shipping this artifact",
            version in LEGACY_HANDLE_CONTRACT..REGISTRY_HANDLE_CONTRACT,
        )
    }

    /** Ten grey 320×240 AVC frames in an MP4, from the platform encoder; nothing is read from disk. */
    private fun writeTinyAvcMp4(target: File) {
        val width = 320
        val height = 240
        val frameCount = 10
        val frameDurationUs = 1_000_000L / 30
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var framesQueued = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        if (framesQueued == frameCount) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = encoder.getInputImage(inputIndex)
                            val size =
                                if (image != null) {
                                    image.planes.forEach { plane -> fill(plane.buffer, 0x80) }
                                    0
                                } else {
                                    val buffer = checkNotNull(encoder.getInputBuffer(inputIndex))
                                    val bytes = width * height * 3 / 2
                                    buffer.clear()
                                    repeat(bytes) { buffer.put(0x80.toByte()) }
                                    bytes
                                }
                            encoder.queueInputBuffer(inputIndex, 0, size, framesQueued * frameDurationUs, 0)
                            framesQueued += 1
                        }
                    }
                }
                val outputIndex = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val buffer = checkNotNull(encoder.getOutputBuffer(outputIndex))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted) { "encoder produced samples before its output format" }
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        assertTrue("generated fixture is empty", target.length() > 0L)
    }

    private fun fill(
        buffer: ByteBuffer,
        value: Int,
    ) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(value.toByte())
    }
}
