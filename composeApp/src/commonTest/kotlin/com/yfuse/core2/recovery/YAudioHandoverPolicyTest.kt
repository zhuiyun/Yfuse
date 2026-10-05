package com.yfuse.core2.recovery

import com.yfuse.core2.capability.YAudioOutputPath
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YAudioHandoverPolicyTest {
    @Test
    fun `clear one-times audio may keep passthrough`() {
        assertFalse(
            requiresPcmAudioPath(
                protectedContent = false,
                passthroughRejected = false,
                speed = 1f,
            ),
        )
    }

    @Test
    fun `protected rejected and speed-adjusted audio require pcm`() {
        assertTrue(requiresPcmAudioPath(protectedContent = true, passthroughRejected = false, speed = 1f))
        assertTrue(requiresPcmAudioPath(protectedContent = false, passthroughRejected = true, speed = 1f))
        assertTrue(requiresPcmAudioPath(protectedContent = false, passthroughRejected = false, speed = 1.25f))
    }

    @Test
    fun `invalid speed is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            requiresPcmAudioPath(protectedContent = false, passthroughRejected = false, speed = 0f)
        }
    }

    @Test
    fun `passthrough returns once speed and delay are back to neutral`() {
        fun restorable(
            currentPath: YAudioOutputPath = YAudioOutputPath.DecodePcm,
            devicePath: YAudioOutputPath = YAudioOutputPath.Passthrough,
            passthroughRejected: Boolean = false,
            speed: Float = 1f,
            audioDelayMs: Long = 0L,
        ) = passthroughRestorable(currentPath, devicePath, false, passthroughRejected, speed, audioDelayMs)

        assertTrue(restorable())
        // Still stretched or shifted, refused by the sink, or never passthrough on this device.
        assertFalse(restorable(speed = 1.25f))
        assertFalse(restorable(audioDelayMs = -120L))
        assertFalse(restorable(passthroughRejected = true))
        assertFalse(restorable(devicePath = YAudioOutputPath.DecodePcm))
        assertFalse(restorable(currentPath = YAudioOutputPath.Passthrough))
        // Protected content never leaves PCM.
        assertFalse(
            passthroughRestorable(
                currentPath = YAudioOutputPath.DecodePcm,
                devicePath = YAudioOutputPath.Passthrough,
                protectedContent = true,
                passthroughRejected = false,
                speed = 1f,
                audioDelayMs = 0L,
            ),
        )
    }
}
