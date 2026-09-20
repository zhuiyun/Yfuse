package com.yfuse.core.playback

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackProxyLimitsTest {
    @Test
    fun instances_share_the_process_limit_and_cleanup_returns_a_permit_once() {
        val process = PlaybackProxyConnections(2)
        val first = PlaybackProxyAdmission(1, process)
        val second = PlaybackProxyAdmission(2, process)
        val a = assertNotNull(first.tryAcquire())
        val b = assertNotNull(second.tryAcquire())
        assertNull(first.tryAcquire())
        assertNull(second.tryAcquire())
        a.close()
        a.close()
        val c = assertNotNull(second.tryAcquire())
        assertNull(first.tryAcquire(), "Double close must not bypass the process limit")
        b.close()
        c.close()
        assertNotNull(first.tryAcquire()).close()
    }

    @Test
    fun workers_reject_excess_work_without_retaining_a_shutdown_queue() {
        val workers = PlaybackProxyAdmission(1, PlaybackProxyConnections(1)).workers("proxy-limit-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            workers.execute {
                entered.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFailsWith<RejectedExecutionException> { workers.execute { error("Unexpected queued worker") } }
            assertTrue(workers.shutdownNow().isEmpty())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun slow_header_bytes_cannot_restart_the_total_deadline() {
        var now = 0L
        val content = "GET /movie HTTP/1.1\r\nRange: bytes=0-\r\n\r\n".encodeToByteArray()
        val source = ByteArrayInputStream(content)
        val trickle =
            object : InputStream() {
                override fun read(): Int {
                    now += 4_000_000L
                    return source.read()
                }

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val next = read()
                    if (next < 0) return -1
                    buffer[offset] = next.toByte()
                    return 1
                }
            }
        val reader = PlaybackProxyHeaderReader(TestSocket(trickle), 10L, 0L) { now }
        assertFailsWith<SocketTimeoutException> { reader.readLine() }
        assertTrue(source.available() > 0)
    }

    @Test
    fun request_line_and_header_lines_use_one_deadline() {
        var now = 0L
        val reader =
            PlaybackProxyHeaderReader(
                TestSocket(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: local\r\n\r\n".encodeToByteArray())),
                10L,
                0L,
            ) { now }
        assertEquals("GET / HTTP/1.1", reader.readLine())
        now = 10_000_000L
        assertFailsWith<SocketTimeoutException> { reader.readHeaders() }
    }

    @Test
    fun headers_are_bounded_before_truncation_and_incomplete_requests_are_rejected() {
        fun reader(content: String) =
            PlaybackProxyHeaderReader(TestSocket(ByteArrayInputStream(content.encodeToByteArray())))
        assertFailsWith<IOException> { reader("x".repeat(9_000)).readLine() }
        assertFailsWith<IOException> { reader("Range: bytes=0-\r\n").readHeaders() }
        assertFailsWith<IOException> { reader("X: value\r\n".repeat(65) + "\r\n").readHeaders() }
        assertFailsWith<IOException> { reader(("X: " + "v".repeat(4_000) + "\r\n").repeat(17) + "\r\n").readHeaders() }
        assertEquals("bytes=1099511627776-", reader("Range: bytes=1099511627776-\r\n\r\n").readHeaders()["range"])
    }

    private class TestSocket(
        private val input: InputStream,
    ) : Socket() {
        override fun getInputStream(): InputStream = input

        override fun setSoTimeout(timeout: Int) = Unit
    }
}
