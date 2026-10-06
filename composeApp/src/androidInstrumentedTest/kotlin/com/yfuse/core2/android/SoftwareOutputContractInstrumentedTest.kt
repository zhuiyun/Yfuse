package com.yfuse.core2.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real bundled JNI, including buffer-growth retries and an EOF with a second field pending. */
@RunWith(AndroidJUnit4::class)
class SoftwareOutputContractInstrumentedTest {
    @Test
    fun interlaced_video_delivers_both_fields_with_monotonic_timestamps() {
        for ((fixture, fieldDurationUs) in listOf("top25" to 20_000L, "bottom30" to 16_683L)) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val file = File(instrumentation.targetContext.cacheDir, "$fixture.mkv")
            file.writeBytes(
                Base64.decode(
                    instrumentation.context.assets.open("ycore/$fixture.mkv.b64").use {
                        it.readBytes()
                    },
                    Base64.DEFAULT,
                ),
            )
            try {
                val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
                try {
                    FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                    FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, false)
                    val pts = mutableListOf<Long>()
                    decode(handle, video = true) { metadata, _ -> pts += metadata[2] }
                    assertEquals("Every interlaced picture must deliver two fields", 6, pts.size)
                    assertTrue(pts.zipWithNext().all { (a, b) -> b > a })
                    for (first in listOf(0, 2, 4)) {
                        assertTrue(
                            "Field spacing for $fixture: $pts",
                            kotlin.math.abs(pts[first + 1] - pts[first] - fieldDurationUs) < 1_000L,
                        )
                    }
                    FfmpegNativeBridge.flushSoftwareDecoder(handle, 0)
                    assertEquals(
                        0L,
                        FfmpegNativeBridge.receiveSoftwareVideoFrame(handle, 0, ByteBuffer.allocateDirect(1))[0],
                    )
                } finally {
                    FfmpegNativeBridge.close(handle)
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun float_pcm_retains_bits_below_signed_16_resolution() {
        assertTrue("Bundled native audio must support float PCM", FfmpegNativeBridge.softwareAudioFloat)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "precision24.wav")
        val samples = intArrayOf(1, -1, 255, -255, 65537, -65537)
        val wave = ByteBuffer.allocate(44 + samples.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(wave.capacity() - 8).put("WAVEfmt ".toByteArray())
        wave
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(48000)
            .putInt(144000)
            .putShort(3)
            .putShort(24)
        wave.put("data".toByteArray()).putInt(samples.size * 3)
        samples.forEach { value -> repeat(3) { byte -> wave.put((value shr (8 * byte)).toByte()) } }
        file.writeBytes(wave.array())
        try {
            val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
            try {
                FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, false)
                val decoded = mutableListOf<Float>()
                decode(handle, video = false) { metadata, data ->
                    assertEquals(1L, metadata[3])
                    assertEquals(48000L, metadata[4])
                    repeat(metadata[5].toInt()) { decoded += data.getFloat(it * 4) }
                }
                assertEquals(samples.size, decoded.size)
                samples.forEachIndexed { index, value -> assertEquals(value / 8388608f, decoded[index], 0f) }
            } finally {
                FfmpegNativeBridge.close(handle)
            }
        } finally {
            file.delete()
        }
    }

    private fun decode(
        handle: Long,
        video: Boolean,
        output: (LongArray, ByteBuffer) -> Unit,
    ) {
        val packetBuffer = ByteBuffer.allocateDirect(65536)
        var frameBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        var eof = false
        repeat(100) {
            while (true) {
                val result =
                    if (video) {
                        FfmpegNativeBridge.receiveSoftwareVideoFrame(handle, 0, frameBuffer)
                    } else {
                        FfmpegNativeBridge.receiveSoftwareAudioFrame(handle, 0, frameBuffer)
                    }
                when (result[0]) {
                    -1L -> frameBuffer = ByteBuffer.allocateDirect(result[1].toInt()).order(ByteOrder.nativeOrder())
                    1L -> output(result, frameBuffer)
                    2L -> return
                    0L -> break
                    else -> error("Unexpected software frame ${result[0]}")
                }
            }
            check(!eof) { "Decoder requested input after draining EOF" }
            packetBuffer.clear()
            val packet = FfmpegNativeBridge.readPacket(handle, packetBuffer)
            if (packet[0] == 0L) {
                eof = true
                check(FfmpegNativeBridge.sendSoftwarePacket(handle, 0, null, null, null))
            } else {
                check(packet[0] == 1L && packet[1] == 0L)
                val data = ByteArray(packet[2].toInt())
                packetBuffer.position(0)
                packetBuffer.get(data)
                check(FfmpegNativeBridge.sendSoftwarePacket(handle, 0, data, packet[3], packet[4]))
            }
        }
        error("Software decoder did not reach EOF")
    }
}
