package com.yfuse.feature.player

import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors

/** HttpURLConnection.disconnect can wait for a blocking body read; never run it on the closing caller. */
private object PlaybackProxyUpstreamCleanup {
    private val executor =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task, "playback-proxy-cleanup").apply { isDaemon = true }
        }

    fun cancel(upstream: () -> Unit) {
        executor.execute { runCatching(upstream) }
    }
}

/** Keeps cache ownership until every accepted worker has completed its own cleanup. */
internal class PlaybackProxyRequests(
    private val onDrained: () -> Unit,
) {
    private val lock = Any()
    private val active = mutableSetOf<PlaybackProxyRequest>()
    private var closed = false
    private var drained = false

    fun register(socket: Socket): PlaybackProxyRequest? {
        val request =
            synchronized(lock) {
                if (closed) null else PlaybackProxyRequest(socket).also(active::add)
            }
        if (request == null) runCatching { socket.close() }
        return request
    }

    fun finish(request: PlaybackProxyRequest) {
        request.cancel()
        val release =
            synchronized(lock) {
                active.remove(request)
                claimDrain()
            }
        if (release) onDrained()
    }

    fun close() {
        val requests: List<PlaybackProxyRequest>
        val release: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            requests = active.toList()
            release = claimDrain()
        }
        requests.forEach(PlaybackProxyRequest::cancel)
        if (release) onDrained()
    }

    private fun claimDrain(): Boolean = (closed && active.isEmpty() && !drained).also { if (it) drained = true }
}

internal class PlaybackProxyRequest(
    val socket: Socket,
) {
    private val lock = Any()

    @Volatile private var cancelled = false
    private var cancelUpstream: (() -> Unit)? = null

    val isCancelled: Boolean get() = cancelled

    fun attachUpstreamCancellation(cancel: () -> Unit) {
        val accepted =
            synchronized(lock) {
                if (cancelled) {
                    false
                } else {
                    cancelUpstream = cancel
                    true
                }
            }
        if (!accepted) {
            PlaybackProxyUpstreamCleanup.cancel(cancel)
            ensureOpen()
        }
    }

    fun ensureOpen() {
        if (cancelled) throw IOException("Playback proxy request closed")
    }

    fun cancel() {
        val upstream =
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                cancelUpstream.also { cancelUpstream = null }
            }
        runCatching { socket.close() }
        if (upstream != null) PlaybackProxyUpstreamCleanup.cancel(upstream)
    }
}
