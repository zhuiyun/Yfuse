package com.yfuse.watch

import java.io.IOException
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Caps bytes as they arrive, including chunked/error responses, before the string collector copies them. */
internal fun boundedStringBodyHandler(maxBytes: Int): HttpResponse.BodyHandler<String> {
    require(maxBytes > 0)
    val strings = HttpResponse.BodyHandlers.ofString()
    return HttpResponse.BodyHandler { info ->
        BoundedHttpBodySubscriber(strings.apply(info), maxBytes)
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
