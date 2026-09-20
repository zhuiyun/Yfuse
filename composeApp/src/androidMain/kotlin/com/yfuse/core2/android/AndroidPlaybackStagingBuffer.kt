package com.yfuse.core2.android

import java.io.Closeable
import java.nio.ByteBuffer

/** Lazily allocated storage for the current native packet/frame; no speculative capacity is reserved. */
internal class AndroidPlaybackStagingBuffer(
    private val initialBytes: Int,
    private val maximumBytes: Int,
    private val reserve: () -> PlaybackMemoryReservation = AndroidPlaybackMemoryBudget::reserve,
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) : Closeable {
    private var buffer: ByteBuffer? = null
    private var reservation: PlaybackMemoryReservation? = null

    init {
        require(initialBytes in 1..maximumBytes)
    }

    fun get(): ByteBuffer = buffer ?: grow(initialBytes)

    fun grow(requiredBytes: Int): ByteBuffer {
        require(requiredBytes in 1..maximumBytes)
        val previous = buffer
        if (previous != null && previous.capacity() >= requiredBytes) return previous
        val memory = reservation ?: reserve().also { reservation = it }
        // During growth both allocations are reachable; relinquishing the old reference does not
        // promise immediate native-memory reclamation, which remains the VM's responsibility.
        memory.resize((previous?.capacity()?.toLong() ?: 0L) + requiredBytes)
        return try {
            allocate(requiredBytes).also {
                buffer = it
                memory.resize(requiredBytes.toLong())
            }
        } catch (failure: Throwable) {
            memory.resize(previous?.capacity()?.toLong() ?: 0L)
            throw failure
        }
    }

    override fun close() {
        buffer = null
        reservation?.close()
        reservation = null
    }
}
