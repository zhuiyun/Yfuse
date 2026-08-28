package com.yfuse.core2.recovery

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
}
