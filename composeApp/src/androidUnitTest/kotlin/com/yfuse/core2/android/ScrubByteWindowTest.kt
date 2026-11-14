package com.yfuse.core2.android

import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScrubByteWindowTest {
    @Test fun holesAcrossPageEdgesNeverBecomeZeroFilledFrames() {
        val w = ScrubByteWindow(3, 4)
        w.add(0, byteArrayOf(1, 2, 3), 0, 3, 12)
        w.add(4, byteArrayOf(5, 6, 7, 8), 0, 4, 12)
        assertFailsWith<EOFException> { w.read(1, ByteArray(5), 0, 5) }
        w.add(3, byteArrayOf(4), 0, 1, 12)
        val bytes = ByteArray(5)
        assertEquals(5, w.read(1, bytes, 0, 5))
        assertContentEquals(byteArrayOf(2, 3, 4, 5, 6), bytes)
        assertEquals(-1, w.read(12, bytes, 0, 5))
    }

    @Test fun evictionAndReplacementDoNotReturnAnEarlierFrame() {
        val w = ScrubByteWindow(3, 4)
        repeat(5) { i -> w.add(i * 4L, ByteArray(4) { i.toByte() }, 0, 4, 20) }
        assertFailsWith<EOFException> { w.read(4, ByteArray(1), 0, 1) }
        assertEquals(1, w.read(0, ByteArray(1), 0, 1))
        w.add(16, ByteArray(4), 0, 4, 24)
        assertFailsWith<EOFException> { w.read(0, ByteArray(1), 0, 1) }
        w.reset()
        assertFailsWith<EOFException> { w.read(16, ByteArray(1), 0, 1) }
    }
}
