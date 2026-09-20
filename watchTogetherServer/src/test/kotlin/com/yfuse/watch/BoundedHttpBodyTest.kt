package com.yfuse.watch

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedHttpBodyTest {
    @Test
    fun exactLimitPreservesUtf8AcrossChunks() {
        val subscription = TestSubscription()
        val body = BoundedHttpBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 6)
        body.onSubscribe(subscription)
        val bytes = "日历".toByteArray(StandardCharsets.UTF_8)
        body.onNext(listOf(ByteBuffer.wrap(bytes, 0, 2)))
        body.onNext(listOf(ByteBuffer.wrap(bytes, 2, 4)))
        body.onComplete()
        assertEquals("日历", body.getBody().toCompletableFuture().join())
        assertFalse(subscription.cancelled)
    }

    @Test
    fun unknownLengthResponseIsCancelledAsSoonAsCumulativeBytesExceedLimit() {
        val subscription = TestSubscription()
        val body = BoundedHttpBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 5)
        body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        val oversized = ByteBuffer.wrap(byteArrayOf(4, 5, 6))
        body.onNext(listOf(oversized))
        assertTrue(subscription.cancelled)
        // The offending chunk never reaches the string collector.
        assertEquals(0, oversized.position())
        assertFailsWith<CompletionException> { body.getBody().toCompletableFuture().join() }
        body.onComplete()
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun singleDeliveryWithMultipleBuffersCannotBypassLimit() {
        val subscription = TestSubscription()
        val body = BoundedHttpBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        body.onSubscribe(subscription)
        body.onNext(listOf(ByteBuffer.allocate(3), ByteBuffer.allocate(2)))
        assertTrue(subscription.cancelled)
        assertFailsWith<CompletionException> { body.getBody().toCompletableFuture().join() }
    }

    private class TestSubscription : Flow.Subscription {
        var cancelled = false

        override fun request(n: Long) = Unit

        override fun cancel() {
            cancelled = true
        }
    }
}
