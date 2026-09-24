package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecOutputFormatReplayTest {
    @Test
    fun `a buffer without a reported format waits behind a synthesized format change`() {
        val replay = CodecOutputFormatReplay<String>()

        assertTrue(replay.holdIfFormatMissing("pcm-1"))
        assertEquals("pcm-1", replay.takeHeld())
        assertNull(replay.takeHeld())
        assertFalse(replay.holdIfFormatMissing("pcm-2"))
    }

    @Test
    fun `a delivered format survives flush so later buffers pass straight through`() {
        val replay = CodecOutputFormatReplay<String>()
        replay.formatReported()

        replay.flush()

        assertFalse(replay.holdIfFormatMissing("pcm-after-seek"))
    }

    @Test
    fun `flush drops a held buffer from the previous generation`() {
        val replay = CodecOutputFormatReplay<String>()
        assertTrue(replay.holdIfFormatMissing("stale"))

        replay.flush()

        assertNull(replay.takeHeld())
    }

    @Test
    fun `the node rebuilds rather than flushes only while no format is known`() {
        // AndroidMediaCodecAudioNode.flush() reads formatKnown to choose between flush and rebuild.
        val replay = CodecOutputFormatReplay<String>()
        assertFalse(replay.formatKnown)

        assertTrue(replay.holdIfFormatMissing("pcm"))
        assertTrue(replay.formatKnown, "a synthesized format counts as delivered")

        replay.flush()
        assertTrue(replay.formatKnown, "a flush keeps a delivered format")
        replay.reset()
        assertFalse(replay.formatKnown)
    }

    @Test
    fun `reconfiguring the codec requires a new format`() {
        val replay = CodecOutputFormatReplay<String>()
        replay.formatReported()

        replay.reset()

        assertTrue(replay.holdIfFormatMissing("first-after-configure"))
    }
}
