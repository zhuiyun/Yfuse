package com.yfuse.core.playback

import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes a proxy response body, closing the socket once the client has stopped reading.
 *
 * Java sockets have no write timeout: a client that stops reading parks the worker inside `write`
 * forever, holding its playback slot, memory lease and cache entry until the whole proxy closes.
 * Every write [block] performs reports progress; when none arrives for [idleTimeoutMs] the socket
 * is closed from the watchdog thread, which is the only way to unblock the write.
 *
 * The budget is deliberately generous: a paused player legitimately stops reading for a while,
 * and the goal is to reclaim slots abandoned by dead clients, not to police idle playback.
 */
internal fun Socket.writeResponseBodyWithWatchdog(
    idleTimeoutMs: Long = PLAYBACK_PROXY_WRITE_IDLE_TIMEOUT_MS,
    block: (OutputStream) -> Unit,
) {
    val watchdog = PlaybackProxyWriteWatchdog(this, idleTimeoutMs)
    val delegate = getOutputStream()
    val output =
        object : OutputStream() {
            override fun write(b: Int) {
                delegate.write(b)
                watchdog.progress()
            }

            override fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) {
                delegate.write(buffer, offset, length)
                watchdog.progress()
            }

            override fun flush() {
                delegate.flush()
                watchdog.progress()
            }
        }
    try {
        block(output)
        runCatching { output.flush() }
    } finally {
        watchdog.close()
    }
}

internal class PlaybackProxyWriteWatchdog(
    private val socket: Socket,
    idleTimeoutMs: Long = PLAYBACK_PROXY_WRITE_IDLE_TIMEOUT_MS,
) {
    private val idleTimeoutMs = idleTimeoutMs.also { require(it > 0L) }
    private val lastProgressNs = AtomicLong(System.nanoTime())
    private val closed = AtomicBoolean(false)
    private val task: ScheduledFuture<*>

    init {
        val intervalMs = (idleTimeoutMs / 4).coerceAtLeast(MIN_CHECK_INTERVAL_MS)
        task = scheduler.scheduleWithFixedDelay({ closeWhenStalled() }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun progress() {
        lastProgressNs.set(System.nanoTime())
    }

    fun close() {
        if (closed.compareAndSet(false, true)) task.cancel(false)
    }

    private fun closeWhenStalled() {
        if (closed.get()) return
        val idleMs = (System.nanoTime() - lastProgressNs.get()) / 1_000_000L
        if (idleMs < idleTimeoutMs) return
        if (closed.compareAndSet(false, true)) {
            task.cancel(false)
            runCatching { socket.close() }
        }
    }

    private companion object {
        private const val MIN_CHECK_INTERVAL_MS = 1_000L
        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "playback-proxy-write-watchdog").apply { isDaemon = true }
            }
    }
}

internal const val PLAYBACK_PROXY_WRITE_IDLE_TIMEOUT_MS = 120_000L
