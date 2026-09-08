package com.yfuse.core2.android

import java.util.concurrent.atomic.AtomicBoolean

/** A frame stays exclusively leased through presentation; clearing never recycles an active frame. */
internal class BoundedFrameLeasePool<K, T>(
    private val capacity: Int,
    private val create: (K) -> T,
    private val dispose: (T) -> Unit,
) {
    private class Entry<K, T>(
        val key: K,
        val value: T,
        var busy: Boolean = true,
        var retired: Boolean = false,
    )

    private val entries = mutableListOf<Entry<K, T>>()

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun acquire(key: K): Lease<T>? {
        var entry = entries.firstOrNull { !it.busy && !it.retired && it.key == key }
        if (entry == null) {
            entries.firstOrNull { !it.busy }?.let { old ->
                entries.remove(old)
                dispose(old.value)
            }
            if (entries.size >= capacity) return null
            entry = Entry(key, create(key))
            entries += entry
        }
        entry.busy = true
        val selected = entry
        return Lease(selected.value) { release(selected) }
    }

    @Synchronized
    private fun release(entry: Entry<K, T>) {
        entry.busy = false
        if (entry.retired) {
            entries.remove(entry)
            dispose(entry.value)
        }
    }

    @Synchronized
    fun clear() {
        entries.toList().forEach { entry ->
            if (entry.busy) {
                entry.retired = true
            } else {
                entries.remove(entry)
                dispose(entry.value)
            }
        }
    }

    class Lease<T> internal constructor(
        val value: T,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
