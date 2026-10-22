package com.yfuse.watch

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val bodyDeadlines =
    ScheduledThreadPoolExecutor(1) { task -> Thread(task, "calendar-body-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

/** HttpRequest.timeout ends at the headers for ofInputStream; close the body to unblock a stalled read. */
internal class DeadlineInputStream(
    source: InputStream,
    timeoutMs: Long,
) : FilterInputStream(source) {
    private val expired = AtomicBoolean(false)
    private val deadline =
        bodyDeadlines.schedule(
            {
                expired.set(true)
                runCatching { source.close() }
            },
            timeoutMs.also { require(it > 0L) },
            TimeUnit.MILLISECONDS,
        )

    override fun read(): Int = beforeDeadline { `in`.read() }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = beforeDeadline { `in`.read(buffer, offset, length) }

    override fun skip(n: Long): Long = beforeDeadline { `in`.skip(n) }

    override fun close() {
        deadline.cancel(false)
        super.close()
    }

    private inline fun <T> beforeDeadline(read: () -> T): T {
        if (expired.get()) throw SocketTimeoutException("Calendar upstream body timed out")
        try {
            return read().also {
                if (expired.get()) throw SocketTimeoutException("Calendar upstream body timed out")
            }
        } catch (failure: IOException) {
            if (expired.get() && failure !is SocketTimeoutException) {
                throw SocketTimeoutException("Calendar upstream body timed out").apply { initCause(failure) }
            }
            throw failure
        }
    }
}

/** Caps bytes as they arrive, including chunked/error responses, before the string collector copies them. */
internal fun boundedStringBodyHandler(maxBytes: Int): HttpResponse.BodyHandler<String> {
    require(maxBytes > 0)
    val strings = HttpResponse.BodyHandlers.ofString()
    return HttpResponse.BodyHandler { info ->
        BoundedHttpBodySubscriber(strings.apply(info), maxBytes)
    }
}

/** Fails a read once more than [maxBytes] have come through, however the body is consumed. */
internal class BoundedInputStream(
    source: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(source) {
    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) count(read)
        return read
    }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count(it) }

    private fun count(bytes: Number) {
        consumed += bytes.toLong()
        if (consumed > maxBytes) throw IOException("Calendar upstream response exceeds $maxBytes bytes")
    }
}

internal class BoundedHttpBodySubscriber(
    private val delegate: HttpResponse.BodySubscriber<String>,
    private val maxBytes: Int,
) : HttpResponse.BodySubscriber<String> {
    private var subscription: Flow.Subscription? = null
    private var remaining = maxBytes.toLong()
    private var finished = false

    override fun getBody(): CompletionStage<String> = delegate.body

    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        if (finished) return
        val bytes = buffers.sumOf { it.remaining().toLong() }
        if (bytes > remaining) {
            finished = true
            subscription?.cancel()
            delegate.onError(IOException("Calendar upstream response exceeds $maxBytes bytes"))
            return
        }
        remaining -= bytes
        delegate.onNext(buffers)
    }

    override fun onError(failure: Throwable) {
        if (finished) return
        finished = true
        delegate.onError(failure)
    }

    override fun onComplete() {
        if (finished) return
        finished = true
        delegate.onComplete()
    }
}
