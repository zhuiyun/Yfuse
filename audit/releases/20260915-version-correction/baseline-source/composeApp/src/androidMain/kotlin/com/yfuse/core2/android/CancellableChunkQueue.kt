package com.yfuse.core2.android

import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Each transport open owns one channel; closing wakes consumers and back-pressured producers. */
internal class CancellableChunkQueue<T : Any>(
    capacity: Int,
) {
    private val queue = ArrayBlockingQueue<Any>(capacity)
    private val terminal = Any()

    @Volatile private var closed = false

    fun put(value: T) {
        while (!closed) {
            if (queue.offer(value, 50L, TimeUnit.MILLISECONDS)) return
        }
    }

    fun offer(value: T): Boolean = !closed && queue.offer(value)

    fun poll(
        timeout: Long,
        unit: TimeUnit,
    ): T? {
        if (closed) throw InterruptedIOException("Transport channel closed")
        val value = queue.poll(timeout, unit)
        if (closed || value === terminal) throw InterruptedIOException("Transport channel closed")
        @Suppress("UNCHECKED_CAST")
        return value as T?
    }

    fun close() {
        closed = true
        queue.clear()
        queue.offer(terminal)
    }
}
