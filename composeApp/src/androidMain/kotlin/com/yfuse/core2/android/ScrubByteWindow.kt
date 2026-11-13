package com.yfuse.core2.android

import java.util.BitSet

/** A small sparse window of the bytes already delivered by playback. There is no loader on a miss. */
internal class ScrubByteWindow(
    private val maxPages: Int = 128,
    private val pageSize: Int = 64 * 1024,
) {
    private class Page(size: Int) {
        val bytes = ByteArray(size)
        val valid = BitSet(size)
    }

    private val pages = LinkedHashMap<Long, Page>(16, .75f, true)
    private var length = -1L
    var epoch = 0L
        private set

    init { require(maxPages >= 3 && pageSize > 0) }

    @Synchronized
    fun reset() {
        epoch++
        pages.clear()
        length = -1L
    }

    @Synchronized
    fun size(): Long = length

    @Synchronized
    fun add(position: Long, bytes: ByteArray, offset: Int, count: Int, total: Long) {
        if (total <= 0 || position < 0 || count <= 0 || position > total - count ||
            offset < 0 || offset > bytes.size - count
        ) return
        if (length >= 0 && length != total) reset()
        length = total
        var current = position
        var copied = 0
        while (copied < count) {
            val index = current / pageSize
            val within = (current % pageSize).toInt()
            val n = minOf(count - copied, pageSize - within)
            val page = pages.getOrPut(index) { Page(pageSize) }
            bytes.copyInto(page.bytes, within, offset + copied, offset + copied + n)
            page.valid.set(within, within + n)
            current += n
            copied += n
        }
        val tail = (length - 1) / pageSize
        val iter = pages.entries.iterator()
        while (pages.size > maxPages && iter.hasNext()) {
            val index = iter.next().key
            if (index != 0L && index != tail) {
                iter.remove()
                epoch++
            }
        }
    }

    /** A hole is different from EOF. Never let a decoder treat missing bytes as a complete file. */
    @Synchronized
    fun read(position: Long, out: ByteArray, offset: Int, count: Int): Int {
        require(position >= 0 && count >= 0 && offset >= 0 && offset <= out.size - count)
        if (count == 0) return 0
        if (length <= 0) throw java.io.EOFException("No playback data")
        if (position >= length) return -1
        val wanted = minOf(count.toLong(), length - position).toInt()
        var copied = 0
        while (copied < wanted) {
            val p = position + copied
            val page = pages[p / pageSize] ?: throw java.io.EOFException("Unbuffered")
            val within = (p % pageSize).toInt()
            val n = minOf(wanted - copied, pageSize - within)
            if (page.valid.nextClearBit(within) < within + n) throw java.io.EOFException("Unbuffered")
            page.bytes.copyInto(out, offset + copied, within, within + n)
            copied += n
        }
        return copied
    }
}
