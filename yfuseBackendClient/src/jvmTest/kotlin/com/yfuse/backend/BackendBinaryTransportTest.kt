package com.yfuse.backend

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BackendBinaryTransportTest {
    @Test
    fun disabledRequestsStopBeforeUrlParsingOrConnectionCreation() {
        var creations = 0
        val transport =
            BackendBinaryTransport(BackendAccess(false)) {
                creations++
                error("Disabled transports must not allocate a connection")
            }
        for (feature in listOf(BackendFeature.Updates, BackendFeature.PlaybackPolicy)) {
            val failure =
                assertFailsWith<BackendUnavailableException> {
                    transport.fetchDocument("invalid URL", feature, 32, 5_000, 5_000)
                }
            assertEquals(feature, failure.feature)
            assertFailsWith<BackendUnavailableException> {
                transport.openDownload("invalid URL", feature)
            }
        }
        assertEquals(0, creations)
    }

    @Test
    fun documentUsesBoundedIdentityBytesAndAlwaysClosesConnection() {
        val connection = StubConnection(bytes = "json".toByteArray(), declaredLength = 4)
        val response = transport(connection).fetchDocument(URL_TEXT, BackendFeature.Updates, 4, 8_000, 8_000)
        assertEquals(200, response.statusCode)
        assertContentEquals("json".toByteArray(), response.bytes)
        assertEquals(8_000, connection.connectTimeout)
        assertEquals(8_000, connection.readTimeout)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(1, connection.streamCloses)
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun documentRejectsAdvertisedOversizeBeforeOpeningBody() {
        val connection = StubConnection(bytes = byteArrayOf(), declaredLength = 33)
        assertFailsWith<IllegalStateException> {
            transport(connection).fetchDocument(URL_TEXT, BackendFeature.Updates, 32, 8_000, 8_000)
        }
        assertEquals(0, connection.streamOpens)
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun documentAlsoEnforcesLimitWithoutContentLength() {
        val connection = StubConnection(bytes = ByteArray(100), declaredLength = -1)
        assertFailsWith<IllegalStateException> {
            transport(connection).fetchDocument(URL_TEXT, BackendFeature.PlaybackPolicy, 32, 5_000, 5_000)
        }
        assertEquals(33, connection.bytesRead)
        assertEquals(1, connection.streamCloses)
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun redirectAndMissingDocumentsReturnStatusWithoutOpeningResponseBody() {
        for (status in listOf(302, 404, 410)) {
            val connection = StubConnection(status = status)
            val response =
                transport(
                    connection,
                ).fetchDocument(URL_TEXT, BackendFeature.PlaybackPolicy, 32, 5_000, 5_000)
            assertEquals(status, response.statusCode)
            assertContentEquals(byteArrayOf(), response.bytes)
            assertEquals(0, connection.streamOpens)
            assertEquals(1, connection.disconnects)
            assertFalse(connection.instanceFollowRedirects)
        }
    }

    @Test
    fun rangeDownloadOwnsHeadersMetadataAndClosesBodyOnlyOnce() {
        val connection =
            StubConnection(
                bytes = "remaining".toByteArray(),
                status = 206,
                declaredLength = 9,
                responseHeaders = mapOf("ETag" to "  \"revision-1\"  ", "Content-Range" to "bytes 100-108/109"),
            )
        val response =
            transport(connection).openDownload(
                URL_TEXT,
                BackendFeature.Updates,
                startBytes = 100,
                validator = "etag:\"revision-1\"",
            )
        assertEquals("bytes=100-", connection.getRequestProperty("Range"))
        assertEquals("\"revision-1\"", connection.getRequestProperty("If-Range"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(206, response.statusCode)
        assertEquals(9L, response.contentLength)
        assertEquals("bytes 100-108/109", response.contentRange)
        assertEquals("etag:\"revision-1\"", response.validator)
        response.inputStream.use { assertContentEquals("remaining".toByteArray(), it.readBytes()) }
        response.close()
        response.close()
        assertEquals(1, connection.streamCloses)
        assertEquals(1, connection.disconnects)
        assertFailsWith<IllegalStateException> { response.inputStream }
    }

    @Test
    fun freshDownloadOmitsResumeHeadersAndWeakEtagFallsBackToLastModified() {
        val modified = "Wed, 07 Oct 2026 02:00:00 GMT"
        val connection = StubConnection(responseHeaders = mapOf("ETag" to "W/\"weak\"", "Last-Modified" to modified))
        val response = transport(connection).openDownload(URL_TEXT, BackendFeature.Updates, validator = "etag:old")
        assertNull(connection.getRequestProperty("Range"))
        assertNull(connection.getRequestProperty("If-Range"))
        assertEquals("last-modified:$modified", response.validator)
        response.close()
        assertEquals(0, connection.streamOpens)
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun handshakeFailureDisconnectsWithoutReturningAnOwnedResponse() {
        val connection = StubConnection(responseFailure = IOException("network failure"))
        assertFailsWith<IOException> { transport(connection).openDownload(URL_TEXT, BackendFeature.Updates) }
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun closeFailureStillDisconnectsTheDownload() {
        val connection = StubConnection(closeFailure = IOException("close failure"))
        val response = transport(connection).openDownload(URL_TEXT, BackendFeature.Updates)
        response.inputStream
        assertFailsWith<IOException> { response.close() }
        assertEquals(1, connection.disconnects)
        response.close()
        assertEquals(1, connection.disconnects)
    }

    private fun transport(connection: StubConnection) = BackendBinaryTransport(BackendAccess(true)) { connection }

    private class StubConnection(
        bytes: ByteArray = byteArrayOf(),
        private val status: Int = 200,
        private val declaredLength: Long = -1,
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val responseFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : HttpURLConnection(URL(URL_TEXT)) {
        var disconnects = 0
        var streamOpens = 0
        var streamCloses = 0
        var bytesRead = 0
        private val body =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }

                override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

                override fun close() {
                    streamCloses++
                    closeFailure?.let { throw it }
                    super.close()
                }
            }

        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getContentLengthLong(): Long = declaredLength

        override fun getHeaderField(name: String): String? = responseHeaders[name]

        override fun getInputStream(): InputStream {
            streamOpens++
            return body
        }

        override fun disconnect() {
            disconnects++
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }

    private companion object {
        const val URL_TEXT = "https://backend.example/yfuse/update-v2.json"
    }
}
