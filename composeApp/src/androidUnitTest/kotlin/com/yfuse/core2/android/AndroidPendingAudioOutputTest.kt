package com.yfuse.core2.android

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidPendingAudioOutputTest {
    @Test
    fun partialWriteRetainsOwnershipUntilConsumed() {
        val released = mutableListOf<Int>()
        val owner = AndroidPendingAudioOutput<Int> { released += it }
        val data = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val pending = owner.hold(7, data)
        data.position(2)
        assertFailsWith<IllegalStateException> { owner.complete(pending) }
        assertFailsWith<IllegalStateException> { owner.hold(8, ByteBuffer.allocate(1)) }
        assertSame(pending, owner.current)
        assertEquals(emptyList(), released)
        data.position(4)
        owner.complete(pending)
        owner.discard()
        assertEquals(listOf(7), released)
        assertNull(owner.current)
    }

    @Test
    fun seekDiscardAndFailedReleaseCannotReleaseTwiceOrConsumeANewBuffer() {
        val released = mutableListOf<Int>()
        val owner =
            AndroidPendingAudioOutput<Int> {
                released += it
                error("codec released")
            }
        val old = owner.hold(1, ByteBuffer.allocate(0))
        owner.discard()
        owner.discard()
        val next = owner.hold(2, ByteBuffer.allocate(0))
        assertFailsWith<IllegalStateException> { owner.complete(old) }
        assertSame(next, owner.current)
        assertFailsWith<IllegalStateException> { owner.complete(next) }
        owner.discard()
        assertEquals(listOf(1, 2), released)
        assertNull(owner.current)
    }
}
