package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService

/** One low-priority disk writer, sharing the bounded range pool. No completed body is retained on heap. */
internal class AndroidForwardCacheWarmer(
    private val cache: AndroidYCoreBlockCache,
    private val executor: ExecutorService,
    private val createTransport: () -> YMediaTransport,
    private val load: (Long, YMediaTransport, () -> Boolean) -> Pair<ByteArray, Long?>,
    private val canWarm: () -> Boolean,
) {
    private val lock = Any()
    private var start = 0L
    private var end = 0L
    private var cursor = 0L
    private var running = false
    private var closed = false
    private var retryAfterNs = 0L
    private var activeIndex = -1L
    private var activeTransport: YMediaTransport? = null

    fun updateWindow(
        firstBlock: Long,
        endExclusive: Long,
    ) {
        val abandoned =
            synchronized(lock) {
                if (closed) return
                val continuous = firstBlock in start..end && endExclusive > firstBlock
                cursor = if (!continuous) firstBlock else maxOf(cursor, firstBlock)
                // Normal forward playback can overtake a moving disk request. Let it finish;
                // only a disjoint seek window abandons that response.
                start = if (continuous && activeTransport != null) minOf(firstBlock, activeIndex) else firstBlock
                end = endExclusive.coerceAtLeast(firstBlock)
                activeTransport?.takeIf { activeIndex !in start until end }
            }
        abandoned?.let { runCatching { runBlocking { it.close() } } }
        synchronized(lock) {
            if (!running && cursor < end && canWarm() && System.nanoTime() >= retryAfterNs) {
                running = true
                try {
                    executor.execute(::warm)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    running = false
                }
            }
        }
    }

    private fun warm() {
        try {
            while (canWarm()) {
                val index =
                    synchronized(lock) {
                        if (closed || cursor >= end) return
                        cursor
                    }
                if (cache.cachedBlockLength(index) == null) {
                    val transport = createTransport()
                    try {
                        synchronized(lock) {
                            if (closed || index !in start until end) return
                            activeIndex = index
                            activeTransport = transport
                        }
                        val (bytes, length) =
                            load(index, transport) {
                                synchronized(lock) { closed || index !in start until end }
                            }
                        // Ignore completions for an abandoned seek window.
                        synchronized(lock) {
                            if (!closed && index in start until end && bytes.isNotEmpty()) {
                                cache.writeBlock(index, bytes, length)
                            }
                        }
                    } finally {
                        runCatching { runBlocking { transport.close() } }
                        synchronized(lock) {
                            if (activeTransport === transport) activeTransport = null
                        }
                    }
                }
                synchronized(lock) { if (cursor == index) cursor++ }
            }
        } catch (_: Exception) {
            synchronized(lock) { retryAfterNs = System.nanoTime() + 2_000_000_000L }
        } finally {
            synchronized(lock) { running = false }
        }
    }

    fun close() {
        val transport =
            synchronized(lock) {
                closed = true
                activeTransport.also { activeTransport = null }
            }
        transport?.let { runCatching { runBlocking { it.close() } } }
    }
}

/** Immutable feedback published without taking the data source's blocking read monitor. */
internal data class YTransportPlaybackWindow(
    val targetAheadUs: Long = 60_000_000L,
    val speed: Float = 1f,
    val bufferedUs: Long = 0L,
    val minimumWarmBufferUs: Long = 3_000_000L,
    val playing: Boolean = false,
)
