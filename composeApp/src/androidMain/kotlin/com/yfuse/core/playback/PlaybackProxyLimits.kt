package com.yfuse.core.playback

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Both native-player proxies share this process limit, including workers still cleaning up. */
internal class PlaybackProxyConnections(
    maximumConnections: Int = 64,
) {
    private val permits = Semaphore(maximumConnections.also { require(it > 0) })

    internal fun acquire(): Boolean = permits.tryAcquire()

    internal fun release() = permits.release()
}

internal class PlaybackProxyAdmission(
    val maximumConnections: Int = 16,
    private val process: PlaybackProxyConnections = processConnections,
) {
    private val permits = Semaphore(maximumConnections.also { require(it > 0) })

    val activeConnections: Int get() = maximumConnections - permits.availablePermits()

    fun tryAcquire(): Closeable? {
        if (!permits.tryAcquire()) return null
        if (!process.acquire()) {
            permits.release()
            return null
        }
        val released = AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true)) {
                process.release()
                permits.release()
            }
        }
    }

    /** No queued sockets can be orphaned by shutdownNow; rejection returns ownership to the caller. */
    fun workers(name: String): ExecutorService =
        ThreadPoolExecutor(
            0,
            maximumConnections,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task -> Thread(task, name).apply { isDaemon = true } },
        )
}

/** Bounds the whole request header, rather than restarting a timeout for each arriving byte. */
internal class PlaybackProxyHeaderReader(
    socket: Socket,
    timeoutMs: Long = PLAYBACK_PROXY_HEADER_TIMEOUT_MS,
    private val acceptedAtNs: Long = System.nanoTime(),
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val timeoutNs = timeoutMs.also { require(it in 1..Int.MAX_VALUE.toLong()) } * 1_000_000L
    private val source = socket.getInputStream()
    private val input =
        BufferedInputStream(
            object : InputStream() {
                override fun read(): Int {
                    socket.soTimeout = remainingMs()
                    return source.read()
                }

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    socket.soTimeout = remainingMs()
                    return source.read(buffer, offset, length)
                }
            },
        )

    private fun remainingMs(): Int {
        val remaining = timeoutNs - (nowNs() - acceptedAtNs)
        if (remaining <= 0L) throw SocketTimeoutException("Playback proxy request headers timed out")
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            remainingMs()
            val next = input.read()
            remainingMs()
            if (next == -1) {
                if (line.isEmpty()) return null
                throw EOFException("Incomplete playback proxy request header")
            }
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_PROXY_HEADER_LINE_BYTES) throw IOException("Playback proxy header line is too long")
            line.append(next.toChar())
        }
    }

    fun readHeaders(): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var totalBytes = 0
        var count = 0
        while (true) {
            val line = readLine() ?: throw EOFException("Incomplete playback proxy request headers")
            if (line.isEmpty()) return headers
            totalBytes += line.length + 2
            if (++count > MAX_PROXY_HEADER_COUNT || totalBytes > MAX_PROXY_HEADERS_BYTES) {
                throw IOException("Playback proxy request headers exceed their limit")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed playback proxy request header")
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }
}

internal const val PLAYBACK_PROXY_HEADER_TIMEOUT_MS = 10_000L
private const val MAX_PROXY_HEADER_LINE_BYTES = 8 * 1024
private const val MAX_PROXY_HEADER_COUNT = 64
private const val MAX_PROXY_HEADERS_BYTES = 64 * 1024
private val processConnections = PlaybackProxyConnections()
