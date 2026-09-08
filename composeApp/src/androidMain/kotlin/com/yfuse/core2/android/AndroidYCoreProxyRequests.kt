package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Registration and shutdown share a short lock; cancellation actions must never wait for workers. */
internal class YCoreProxyRequests : Closeable {
    private val lock = Any()
    private var closed = false
    private val cancellations = mutableSetOf<() -> Unit>()

    fun register(cancel: () -> Unit): Closeable? {
        val accepted = synchronized(lock) { !closed && cancellations.add(cancel) }
        if (!accepted) {
            cancel()
            return null
        }
        return Closeable { synchronized(lock) { cancellations.remove(cancel) } }
    }

    override fun close() {
        val pending =
            synchronized(lock) {
                if (closed) return
                closed = true
                cancellations.toList().also { cancellations.clear() }
            }
        pending.forEach { runCatching(it) }
    }
}

/**
 * Each open is a cancellable exchange, not a permanently closed transport: range retries reuse it.
 * Blocking upstream close runs on IO and retains its delegate until cleanup has really completed.
 */
internal class YCoreProxyTransport(
    private val delegate: YMediaTransport,
    private val requests: YCoreProxyRequests,
) : YMediaTransport by delegate {
    @Volatile
    private var exchange: Exchange? = registerExchange()
    private var opened = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        if (opened) close()
        val opening = exchange ?: registerExchange().also { exchange = it }
        opened = true
        return opening.run { delegate.open(request) }
    }

    private fun registerExchange(): Exchange {
        val opening = Exchange(delegate)
        val registration = requests.register(opening::cancel)
        opening.finished.invokeOnCompletion { registration?.close() }
        return opening
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        (exchange ?: throw CancellationException("Proxy exchange is closed")).run {
            delegate.read(destination, offset, length)
        }

    fun cancel() {
        exchange?.cancel()
    }

    override suspend fun close() {
        val closing = exchange ?: return
        closing.cancel()
        try {
            withContext(NonCancellable) { closing.finished.await() }
        } finally {
            if (exchange === closing) {
                exchange = null
                opened = false
            }
        }
    }

    private class Exchange(
        private val delegate: YMediaTransport,
    ) {
        private val lifetime = Job()
        private val closing = AtomicBoolean(false)
        val finished = CompletableDeferred<Unit>()

        suspend fun <T> run(block: suspend () -> T): T =
            coroutineScope {
                val current = currentCoroutineContext()
                val cancellation = lifetime.invokeOnCompletion { current.job.cancel() }
                try {
                    current.ensureActive()
                    block().also { current.ensureActive() }
                } finally {
                    cancellation.dispose()
                }
            }

        fun cancel() {
            if (!closing.compareAndSet(false, true)) return
            lifetime.cancel()
            cleanupScope.launch {
                try {
                    delegate.close()
                    finished.complete(Unit)
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
        }
    }
}

private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
