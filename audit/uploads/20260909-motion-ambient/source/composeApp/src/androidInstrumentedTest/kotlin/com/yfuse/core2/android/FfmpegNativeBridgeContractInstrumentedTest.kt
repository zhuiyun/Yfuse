package com.yfuse.core2.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val file = File(context.cacheDir, "ycore-demux-contract-smoke.wav")
        try {
            writeTinyPcmWave(file)
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

    /** A deterministic PCM fixture tests the JNI contract without depending on a hardware encoder. */
    private fun writeTinyPcmWave(target: File) {
        val sampleRate = 8_000
        val payloadBytes = sampleRate / 10 * 2
        val bytes = ByteBuffer.allocate(44 + payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(36 + payloadBytes)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * 2)
        bytes.putShort(2)
        bytes.putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(payloadBytes)
        target.writeBytes(bytes.array())
    }
}
