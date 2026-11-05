package com.yfuse.core2.android

import com.russhwolf.settings.MapSettings
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourcePlaybackTarget
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCloudDriveMediaTransportTest {
    @Test fun stable_uri_resolves_on_open_preserves_range_and_retries_expired_link_once() =
        runTest {
            val source =
                FileSource(
                    "fs" + "c".repeat(24),
                    FileSourceKind.Baidu,
                    "Drive",
                    "https://pan.baidu.com",
                    username = "token",
                )
            val registry = FileSourceRegistry(MapSettings(), TestSecureStore(), Dispatchers.Unconfined)
            registry.save(source, "secret-json")
            var resolutions = 0
            val client =
                resolvingClient {
                    resolutions++
                    FileSourcePlaybackTarget(
                        "https://cdn.example/video?signature=$resolutions",
                        headers =
                            mapOf(
                                "User-Agent" to "pan.baidu.com",
                            ),
                        objectIdentity = "object-1",
                        expectedSizeBytes = 10,
                    )
                }
            val http = FakeHttp(mutableListOf(403, 206, 206))
            val transport = AndroidCloudDriveMediaTransport(registry, client, http)
            val request =
                YMediaTransportRequest(
                    source.cloudPlaybackUrl(listOf("电影.mkv")),
                    YSourceProtocol.CloudDrive,
                    YByteRange(2, 5),
                )
            val response = transport.open(request)
            assertEquals(206, response.statusCode)
            assertEquals(2, resolutions)
            assertEquals(YByteRange(2, 5), http.requests.last().range)
            assertEquals("pan.baidu.com", http.requests.last().headers["User-Agent"])
            assertNull(http.requests.last().credentials)
            assertTrue(response.entityTag!!.startsWith("\"yfuse-cloud-"))
            transport.open(request.copy(range = YByteRange(6, 9), headers = mapOf("If-Range" to response.entityTag!!)))
            assertEquals(2, resolutions, "short TTL avoids one API request per range")
            assertFalse(
                http.requests
                    .last()
                    .headers.keys
                    .any { it.equals("If-Range", true) },
            )
        }

    @Test fun replacement_or_source_revision_change_cannot_be_spliced_into_existing_reader() =
        runTest {
            val source =
                FileSource(
                    "fs" + "d".repeat(24),
                    FileSourceKind.Pan123,
                    "Drive",
                    "https://open-api.123pan.com",
                    username = "app",
                )
            val registry = FileSourceRegistry(MapSettings(), TestSecureStore(), Dispatchers.Unconfined)
            registry.save(source, "secret")
            var identity = "first"
            val client =
                resolvingClient {
                    FileSourcePlaybackTarget(
                        "http://cdn.example/video",
                        objectIdentity = identity,
                        expectedSizeBytes = 10,
                    )
                }
            val http = FakeHttp(mutableListOf(206, 403))
            val transport = AndroidCloudDriveMediaTransport(registry, client, http)
            val request =
                YMediaTransportRequest(
                    source.cloudPlaybackUrl(listOf("file.mkv")),
                    YSourceProtocol.CloudDrive,
                    YByteRange(0, 2),
                )
            transport.open(request)
            identity = "replacement"
            assertFailsWith<IOException> { transport.open(request.copy(range = YByteRange(3, 5))) }
            registry.save(source.copy(rootSegments = listOf("changed")), null)
            assertFailsWith<IOException> { transport.open(request) }
        }

    private fun resolvingClient(resolve: () -> FileSourcePlaybackTarget) =
        object : FileSourceClient {
            override suspend fun resolvePlayback(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
            ) = resolve()

            override suspend fun list(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
            ) = emptyList<FileSourceEntry>()

            override suspend fun cacheSubtitle(
                source: FileSource,
                credentials: FileSourceCredentials,
                path: List<String>,
                entry: FileSourceEntry,
            ): String = error("unused")
        }

    private class FakeHttp(
        private val responses: MutableList<Int>,
    ) : YMediaTransport {
        val requests = mutableListOf<YMediaTransportRequest>()
        override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
        override val features = setOf(YTransportFeature.ByteRange)

        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
            requests += request
            return YMediaTransportResponse(
                responses.removeAt(0),
                contentLength = 10,
                acceptedRange = request.range,
                entityTag = "\"etag\"",
            )
        }

        override suspend fun read(
            destination: ByteArray,
            offset: Int,
            length: Int,
        ) = -1

        override suspend fun close() = Unit
    }
}
