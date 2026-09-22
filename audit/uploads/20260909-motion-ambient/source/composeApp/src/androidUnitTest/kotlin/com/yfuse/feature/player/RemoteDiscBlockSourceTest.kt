package com.yfuse.feature.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDiscBlockSourceTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun udf_blocks_are_read_with_exact_identity_byte_ranges_and_fresh_auth_headers() {
        val body = ByteArray(BLURAY_UDF_BLOCK_SIZE * 2) { index -> (index and 0xff).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4096-8191/100000")
                .setBody(Buffer().write(body)),
        )
        var token = "first"
        val source =
            HttpRangeDiscBlockSource(
                sourceUrl = server.url("/movie.iso").toString(),
                headerProvider =
                    RemoteDiscHeaderProvider {
                        mapOf(
                            "Authorization" to "Bearer $token",
                            // Caller cannot replace byte-range safety headers.
                            "Range" to "bytes=0-999999999",
                            "Accept-Encoding" to "gzip",
                        )
                    },
            )
        token = "refreshed"
        val target = ByteArray(body.size)

        assertEquals(2, source.readBlocks(lba = 2, blockCount = 2, target = target))
        assertContentEquals(body, target)
        assertEquals(100000L, source.contentLengthBytes)
        assertNull(source.lastFailure)

        val request = server.takeRequest()
        assertEquals("bytes=4096-8191", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("Bearer refreshed", request.getHeader("Authorization"))
        assertEquals("no-transform", request.getHeader("Cache-Control"))
    }

    @Test
    fun offsets_above_two_gigabytes_stay_64_bit() {
        val lba = 1_500_000
        val start = lba.toLong() * BLURAY_UDF_BLOCK_SIZE
        val end = start + BLURAY_UDF_BLOCK_SIZE - 1L
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${end + 10_000L}")
                .setBody(Buffer().write(ByteArray(BLURAY_UDF_BLOCK_SIZE))),
        )
        val source = HttpRangeDiscBlockSource(server.url("/large.iso").toString())

        assertEquals(1, source.readBlocks(lba, 1, ByteArray(BLURAY_UDF_BLOCK_SIZE)))
        assertEquals("bytes=$start-$end", server.takeRequest().getHeader("Range"))
        assertTrue(start > Int.MAX_VALUE)
    }

    @Test
    fun a_server_that_ignores_range_is_rejected_instead_of_downloading_the_whole_iso() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("whole file would start here"))
        val source = HttpRangeDiscBlockSource(server.url("/unsafe.iso").toString())

        assertEquals(-1, source.readBlocks(0, 1, ByteArray(BLURAY_UDF_BLOCK_SIZE)))
        assertTrue(source.lastFailure.orEmpty().contains("HTTP 200"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirects_are_not_followed_with_server_credentials() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://other.example.invalid/movie.iso"),
        )
        val source =
            HttpRangeDiscBlockSource(
                server.url("/redirect.iso").toString(),
                RemoteDiscHeaderProvider { mapOf("Authorization" to "Bearer private") },
            )

        assertEquals(-1, source.readBlocks(0, 1, ByteArray(BLURAY_UDF_BLOCK_SIZE)))
        assertTrue(source.lastFailure.orEmpty().contains("重定向"))
        assertEquals(1, server.requestCount)
        assertEquals("Bearer private", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun range_probe_requires_206_and_records_total_length() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/85899345920")
                .setBody(Buffer().write(byteArrayOf(0x42))),
        )
        val source = HttpRangeDiscBlockSource(server.url("/80gb.iso").toString())

        assertTrue(source.probeRangeSupport())
        assertEquals(85_899_345_920L, source.contentLengthBytes)
        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun content_range_and_block_header_validation_rejects_ambiguous_values() {
        assertEquals(HttpContentRange(0, 0, 1), parseContentRange("bytes 0-0/1"))
        assertEquals(HttpContentRange(2048, 4095, 10_000), parseContentRange("BYTES 2048-4095/10000"))
        assertNull(parseContentRange("bytes 0-1/*"))
        assertNull(parseContentRange("bytes 10-5/100"))
        assertNull(parseContentRange("bytes 0-100/100"))

        assertEquals("bytes=0-2047", remoteDiscRangeHeader(0, 1))
        assertEquals("bytes=4294967296-4294969343", remoteDiscRangeHeader(2_097_152, 1))
        assertNull(remoteDiscRangeHeader(-1, 1))
        assertNull(remoteDiscRangeHeader(0, 0))
    }

    @Test
    fun closed_reader_is_inert() {
        val source = HttpRangeDiscBlockSource(server.url("/movie.iso").toString())
        source.close()

        assertEquals(-1, source.readBlocks(0, 1, ByteArray(BLURAY_UDF_BLOCK_SIZE)))
        assertFalse(source.probeRangeSupport())
        assertEquals(0, server.requestCount)
    }
}
