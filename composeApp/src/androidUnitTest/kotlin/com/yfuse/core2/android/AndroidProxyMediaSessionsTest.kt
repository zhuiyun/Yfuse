package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidProxyMediaSessionsTest {
    private val runtimeHeap = AndroidPlaybackMemoryBudget.heapSample

    // These tests count origin reads, which assumes the startup slice a range validated survives
    // to serve that range. Judged from this JVM's own heap, memory pressure can drop it first and
    // send the read back to the origin, so the count depended on the run: both origin-count
    // assertions here have failed on CI while passing on other runs of the same code.
    @BeforeTest
    fun pinHeapWithoutPressure() {
        AndroidPlaybackMemoryBudget.heapSample = {
            PlaybackHeapSample(freeBytes = AMPLE_HEAP, maximumBytes = AMPLE_HEAP)
        }
        AndroidPlaybackMemoryBudget.refreshPressure()
    }

    @AfterTest
    fun restoreRuntimeHeap() {
        AndroidPlaybackMemoryBudget.heapSample = runtimeHeap
        AndroidPlaybackMemoryBudget.refreshPressure()
    }

    @Test
    fun sequential_and_concurrent_proxy_ranges_share_one_initial_origin_read() {
        val origin = Origin(ByteArray(4096) { it.toByte() }, "\"first\"")
        val release = CountDownLatch(1)
        origin.blockFirstRead = release
        withProxy(origin) { proxy ->
            val uri = proxy.localUrl("https://media.test/movie.mkv", cacheable = false, cacheIdentity = null)
            val workers = Executors.newFixedThreadPool(4)
            try {
                val first = workers.submit<ByteArray> { readRange(uri, 17, 8) }
                assertTrue(origin.readStarted.await(2, TimeUnit.SECONDS))
                val others = (1..3).map { index -> workers.submit<ByteArray> { readRange(uri, index * 64, 8) } }
                release.countDown()
                assertContentEquals(origin.bytes.copyOfRange(17, 25), first.get(3, TimeUnit.SECONDS))
                others.forEachIndexed { index, result ->
                    assertContentEquals(
                        origin.bytes.copyOfRange((index + 1) * 64, (index + 1) * 64 + 8),
                        result.get(3, TimeUnit.SECONDS),
                    )
                }
                assertContentEquals(origin.bytes.copyOfRange(512, 520), readRange(uri, 512, 8))
                assertEquals(
                    1,
                    origin.requests.size,
                    "Each local Range must reuse the initial representation, length and bytes",
                )
            } finally {
                release.countDown()
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun proxy_media_identity_isolates_authorization_and_media_versions() {
        val origin = Origin(ByteArray(4096) { it.toByte() }, "\"first\"")
        withProxy(origin) { proxy ->
            val first =
                proxy.localUrl(
                    "https://media.test/movie.mkv",
                    upstreamHeaders =
                        mapOf(
                            "Authorization" to "first",
                        ),
                    cacheable = true,
                    cacheIdentity = CACHE_ID,
                )
            val otherAccount =
                proxy.localUrl(
                    "https://media.test/movie.mkv",
                    upstreamHeaders =
                        mapOf(
                            "Authorization" to "second",
                        ),
                    cacheable = true,
                    cacheIdentity = CACHE_ID,
                )
            val otherVersion =
                proxy.localUrl(
                    "https://media.test/movie.mkv",
                    upstreamHeaders =
                        mapOf(
                            "Authorization" to "first",
                        ),
                    cacheable = true,
                    cacheIdentity = CACHE_ID.copy(version = "second"),
                )
            val otherUrl =
                proxy.localUrl(
                    "https://media.test/movie.mkv?signature=new",
                    upstreamHeaders =
                        mapOf(
                            "Authorization" to "first",
                        ),
                    cacheable = true,
                    cacheIdentity = CACHE_ID,
                )
            val firstCredentials = YTransportCredentials.UsernamePassword("account", "first")
            val secondCredentials = YTransportCredentials.UsernamePassword("account", "second")
            val credentialRoute =
                proxy.localUrl(
                    "https://media.test/movie.mkv",
                    credentials = firstCredentials,
                    cacheable = false,
                    cacheIdentity = null,
                )
            val otherCredentials =
                proxy.localUrl(
                    "https://media.test/movie.mkv",
                    credentials = secondCredentials,
                    cacheable = false,
                    cacheIdentity = null,
                )
            listOf(first, first, otherAccount, otherVersion, otherUrl, credentialRoute, otherCredentials).forEach {
                readRange(it, 0, 8)
            }
            assertEquals(6, origin.requests.size)
            assertEquals(
                listOf("first", "second", "first", "first"),
                origin.requests.take(4).map { it.headers["Authorization"] },
            )
            assertEquals(
                listOf(firstCredentials, secondCredentials),
                origin.requests.takeLast(2).map { it.credentials },
            )
        }
    }

    @Test
    fun expired_validation_rechecks_origin_and_retires_old_cached_readers() {
        val clock = AtomicLong()
        val session = AndroidMediaRepresentationSession(clock::get, validationTtlNs = 100L)
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"")
        reader(origin, session).use { old ->
            assertEquals(1, readByte(old, 0))
            reader(origin, session).use { assertEquals(1, readByte(it, 0)) }
            assertEquals(1, origin.requests.size)
            clock.set(101L)
            reader(origin, session).use { assertEquals(1, readByte(it, 0)) }
            assertEquals(2, origin.requests.size)
            // This reader deliberately disables speculative retention. Exercise its next real
            // data block separately from the header ranges used to validate the representation.
            assertEquals(1, readByte(old, 64), "Refreshing the same entity must not interrupt active readers")
            clock.set(202L)
            origin.bytes = ByteArray(320) { 2 }
            origin.tag = "\"second\""
            reader(origin, session).use { refreshed ->
                assertEquals(2, readByte(refreshed, 0))
                assertEquals(320L, refreshed.size)
            }
            assertEquals(
                listOf(YByteRange(0, 63), YByteRange(0, 63), YByteRange(64, 127), YByteRange(0, 63)),
                origin.requests.map { it.range },
            )
            assertFailsWith<IOException> { readByte(old, 0) }
        }
        session.close()
    }

    @Test
    fun changed_entity_on_later_range_invalidates_all_old_readers_and_persistent_blocks() {
        val directory = Files.createTempDirectory("proxy-session-entity").toFile()
        val session = AndroidMediaRepresentationSession()
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"")
        try {
            reader(origin, session, directory).use { old ->
                assertEquals(1, readByte(old, 0))
                assertEquals(1, readByte(old, 64))
                assertTrue(old.awaitCacheWrites(2_000L))
                origin.tag = "\"second\""
                origin.bytes = ByteArray(256) { 2 }
                reader(origin, session, directory).use { current ->
                    assertFailsWith<IOException> { readByte(current, 128) }
                }
                assertEquals("\"first\"", origin.requests.last().headers["If-Range"])
                assertFailsWith<IOException> { readByte(old, 0) }
                reader(origin, session, directory).use { reopened ->
                    assertEquals(2, readByte(reopened, 0))
                    assertEquals(2, readByte(reopened, 64))
                    assertTrue(reopened.awaitCacheWrites(2_000L))
                }
            }
        } finally {
            session.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun missing_strong_validator_never_reuses_previous_persisted_bytes_or_validation() {
        listOf(null, "W/\"unchanged-weak\"").forEach(::assertUntrustedValidator)
    }

    private fun assertUntrustedValidator(validator: String?) {
        val directory = Files.createTempDirectory("proxy-session-unvalidated").toFile()
        val session = AndroidMediaRepresentationSession()
        val origin = Origin(ByteArray(256) { 2 }, validator)
        try {
            val oldCache = AndroidYCoreBlockCache(directory, CACHE_ID, 64, 4096L)
            oldCache.validateRepresentation(256L, validator ?: "\"obsolete\"")
            oldCache.writeBlock(1L, ByteArray(64) { 1 }, 256L)
            reader(origin, session, directory).use {
                assertEquals(2, readByte(it, 64))
                assertTrue(it.awaitCacheWrites(2_000L))
            }
            origin.bytes = ByteArray(256) { 3 }
            reader(origin, session, directory).use {
                assertEquals(3, readByte(it, 64))
                assertTrue(it.awaitCacheWrites(2_000L))
            }
            assertEquals(2, origin.requests.count { it.range?.startInclusive == 0L })
            assertTrue(origin.requests.none { it.headers.containsKey("If-Range") })
        } finally {
            session.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun late_failure_from_an_old_reader_cannot_clear_the_new_representation_cache() {
        val directory = Files.createTempDirectory("proxy-session-late-failure").toFile()
        val clock = AtomicLong()
        val session = AndroidMediaRepresentationSession(clock::get, validationTtlNs = 100L)
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"")
        val oldReadStarted = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val old = reader(origin, session, directory)
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, readByte(old, 0))
            origin.bytes = ByteArray(256) { 2 }
            origin.tag = "\"second\""
            origin.beforeOpenResponse = { request ->
                if (request.range?.startInclusive == 128L) {
                    oldReadStarted.countDown()
                    check(releaseOldRead.await(3, TimeUnit.SECONDS))
                }
            }
            val delayed = worker.submit<Int> { readByte(old, 128L) }
            assertTrue(oldReadStarted.await(2, TimeUnit.SECONDS))
            clock.set(101L)
            reader(origin, session, directory).use { fresh ->
                assertEquals(2, readByte(fresh, 64L))
                assertTrue(fresh.awaitCacheWrites(2_000L))
            }
            assertEquals(1, origin.requests.count { it.range?.startInclusive == 64L })
            releaseOldRead.countDown()
            assertFailsWith<ExecutionException> { delayed.get(3, TimeUnit.SECONDS) }
            reader(origin, session, directory).use { latest ->
                assertEquals(2, readByte(latest, 64L))
            }
            assertEquals(
                1,
                origin.requests.count {
                    it.range?.startInclusive == 64L
                },
                "The new block must still be served from disk after the old reader fails",
            )
        } finally {
            releaseOldRead.countDown()
            old.close()
            session.close()
            worker.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun waiting_for_initial_validation_respects_the_request_budget_without_cancelling_its_owner() {
        val release = CountDownLatch(1)
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"").apply { blockFirstRead = release }
        val session = AndroidMediaRepresentationSession()
        val clock = AtomicLong()
        val first = reader(origin, session)
        val waiterClockRead = CountDownLatch(1)
        val second =
            reader(origin, session, rangeReadBudgetMs = 100L, rangeReadClock = {
                val now = clock.get()
                waiterClockRead.countDown()
                now
            })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initial = workers.submit<Int> { readByte(first, 0) }
            assertTrue(origin.readStarted.await(2, TimeUnit.SECONDS))
            val following = workers.submit<Int> { readByte(second, 0) }
            assertTrue(waiterClockRead.await(2, TimeUnit.SECONDS))
            clock.set(TimeUnit.MILLISECONDS.toNanos(101L))
            val failure = assertFailsWith<ExecutionException> { following.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is SocketTimeoutException)
            assertFalse(initial.isDone)
            assertFalse(origin.firstTransportClosed.get())
            release.countDown()
            assertEquals(1, initial.get(2, TimeUnit.SECONDS))
            assertEquals(1, origin.requests.size)
        } finally {
            release.countDown()
            first.close()
            second.close()
            session.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun taking_over_initial_validation_keeps_the_time_already_spent_waiting() {
        val release = CountDownLatch(1)
        val releaseFollowing = CountDownLatch(1)
        val origin =
            Origin(ByteArray(256) { 1 }, "\"first\"").apply {
                blockFirstRead = release
                blockFollowingReads = releaseFollowing
            }
        val session = AndroidMediaRepresentationSession()
        val clock = AtomicLong()
        val first = reader(origin, session)
        val waiterClockRead = CountDownLatch(1)
        val second =
            reader(origin, session, rangeReadBudgetMs = 100L, rangeReadClock = {
                val now = clock.get()
                waiterClockRead.countDown()
                now
            })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initial = workers.submit<Int> { readByte(first, 0) }
            assertTrue(origin.readStarted.await(2, TimeUnit.SECONDS))
            val following = workers.submit<Int> { readByte(second, 0) }
            assertTrue(waiterClockRead.await(2, TimeUnit.SECONDS))
            clock.set(TimeUnit.MILLISECONDS.toNanos(60L))
            first.cancelPendingRead()
            assertFailsWith<ExecutionException> { initial.get(2, TimeUnit.SECONDS) }
            assertTrue(origin.followingReadStarted.await(2, TimeUnit.SECONDS))
            clock.set(TimeUnit.MILLISECONDS.toNanos(101L))
            val failure = assertFailsWith<ExecutionException> { following.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is SocketTimeoutException)
            assertEquals(2, origin.requests.size)
        } finally {
            release.countDown()
            releaseFollowing.countDown()
            first.close()
            second.close()
            session.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun cancelling_initial_owner_allows_another_reader_to_take_over_validation() {
        val release = CountDownLatch(1)
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"").apply { blockFirstRead = release }
        val session = AndroidMediaRepresentationSession()
        val first = reader(origin, session)
        val second = reader(origin, session)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initial = workers.submit<Int> { readByte(first, 0) }
            assertTrue(origin.readStarted.await(2, TimeUnit.SECONDS))
            val following = workers.submit<Int> { readByte(second, 0) }
            first.cancelPendingRead()
            assertFailsWith<ExecutionException> { initial.get(3, TimeUnit.SECONDS) }
            first.close()
            assertEquals(1, following.get(3, TimeUnit.SECONDS))
            assertEquals(2, origin.requests.size)
            assertEquals(1, readByte(second, 17))
        } finally {
            release.countDown()
            first.close()
            second.close()
            session.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun cancelling_a_waiter_does_not_cancel_or_close_the_initial_owner() {
        val release = CountDownLatch(1)
        val origin = Origin(ByteArray(256) { 1 }, "\"first\"").apply { blockFirstRead = release }
        val session = AndroidMediaRepresentationSession()
        val first = reader(origin, session)
        val second = reader(origin, session)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initial = workers.submit<Int> { readByte(first, 0) }
            assertTrue(origin.readStarted.await(2, TimeUnit.SECONDS))
            val waiterStarted = CountDownLatch(1)
            val following =
                workers.submit<Int> {
                    waiterStarted.countDown()
                    readByte(second, 0)
                }
            assertTrue(waiterStarted.await(2, TimeUnit.SECONDS))
            // cancelReads also prevents admission if the worker has not yet entered readAt.
            second.cancelReads()
            assertFailsWith<ExecutionException> { following.get(3, TimeUnit.SECONDS) }
            second.close()
            assertFalse(initial.isDone)
            assertFalse(origin.firstTransportClosed.get())
            release.countDown()
            assertEquals(1, initial.get(3, TimeUnit.SECONDS))
            reader(origin, session).use { assertEquals(1, readByte(it, 17)) }
            assertEquals(1, origin.requests.size)
        } finally {
            release.countDown()
            first.close()
            second.close()
            session.close()
            workers.shutdownNow()
        }
    }

    private fun reader(
        origin: Origin,
        session: AndroidMediaRepresentationSession,
        directory: File? = null,
        rangeReadBudgetMs: Long = 30_000L,
        rangeReadClock: () -> Long = System::nanoTime,
    ) = AndroidTransportMediaDataSource(
        uri = "https://media.test/movie.mkv",
        protocol = YSourceProtocol.Https,
        headers = emptyMap(),
        createTransport = origin::transport,
        blockSizeOverride = 64,
        cacheDirectory = directory,
        cacheIdentity = CACHE_ID,
        cacheMaximumBytes = 4096L,
        allowsSpeculativeWork = { false },
        representationSession = session,
        rangeReadBudgetMs = rangeReadBudgetMs,
        rangeReadClock = rangeReadClock,
    )

    private fun readByte(
        source: AndroidTransportMediaDataSource,
        offset: Long,
    ): Int {
        val value = ByteArray(1)
        assertEquals(1, source.readAt(offset, value, 0, 1))
        return value[0].toInt()
    }

    private fun withProxy(
        origin: Origin,
        block: (AndroidYCoreHttpProxy) -> Unit,
    ) {
        val directory = Files.createTempDirectory("proxy-session").toFile()
        try {
            AndroidYCoreHttpProxy(
                userAgent = "session-test",
                cacheMaximumBytes = 0L,
                cacheDirectory = directory,
                createTransport = origin::transport,
                isMeteredNetwork = { false },
            ).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readRange(
        url: String,
        offset: Int,
        count: Int,
    ): ByteArray {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\nRange: bytes=$offset-${offset + count - 1}\r\n\r\n"
                    .encodeToByteArray(),
            )
            val response = socket.getInputStream().readBytes()
            val headerEnd = response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            assertTrue(headerEnd >= 0)
            assertTrue(response.toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 206"))
            response.copyOfRange(headerEnd + 4, response.size)
        }
    }

    private class Origin(
        @Volatile var bytes: ByteArray,
        @Volatile var tag: String?,
    ) {
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val readStarted = CountDownLatch(1)
        val followingReadStarted = CountDownLatch(1)
        val firstTransportClosed = AtomicBoolean()
        var blockFirstRead: CountDownLatch? = null
        var blockFollowingReads: CountDownLatch? = null
        var beforeOpenResponse: ((YMediaTransportRequest) -> Unit)? = null
        private val first = AtomicBoolean(true)

        fun transport(): YMediaTransport =
            object : YMediaTransport {
                override val supportedProtocols = setOf(YSourceProtocol.Https)
                override val features = setOf(YTransportFeature.ByteRange)
                private val initial = first.compareAndSet(true, false)
                private val closed = AtomicBoolean()
                private var data = ByteArray(0)
                private var position = 0
                private var end = 0

                override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                    requests += request
                    data = bytes
                    val range = checkNotNull(request.range)
                    position = range.startInclusive.toInt()
                    end = minOf(data.size, checkNotNull(range.endInclusive).toInt() + 1)
                    val responseTag = tag
                    beforeOpenResponse?.invoke(request)
                    return YMediaTransportResponse(
                        206,
                        data.size.toLong(),
                        YByteRange(
                            position.toLong(),
                            end.toLong() - 1,
                        ),
                        entityTag = responseTag,
                    )
                }

                override suspend fun read(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (initial) {
                        readStarted.countDown()
                        blockFirstRead?.let { check(it.await(3, TimeUnit.SECONDS)) }
                    } else {
                        followingReadStarted.countDown()
                        blockFollowingReads?.let { check(it.await(3, TimeUnit.SECONDS)) }
                    }
                    if (closed.get()) throw IOException("Read cancelled")
                    if (position >= end) return -1
                    val count = minOf(length, end - position)
                    data.copyInto(destination, offset, position, position + count)
                    position += count
                    return count
                }

                override suspend fun close() {
                    closed.set(true)
                    if (initial) {
                        firstTransportClosed.set(true)
                        blockFirstRead?.countDown()
                    } else {
                        blockFollowingReads?.countDown()
                    }
                }
            }
    }
}

private val CACHE_ID = YCacheIdentity("scope", "movie", "first")

/** Far more free heap than any pressure threshold asks for. */
private const val AMPLE_HEAP = 1L shl 30
