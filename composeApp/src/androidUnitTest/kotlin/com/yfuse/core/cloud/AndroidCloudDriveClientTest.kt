package com.yfuse.core.cloud

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceSubtitleCache
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCloudDriveClientTest {
    @Test fun cancellingAfterHeadersClosesTheBlockedResponseBody() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"errno\":0}").setBodyDelay(3, TimeUnit.SECONDS))
                val start = System.nanoTime()
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(300) { OkHttpClient().cloudJson(Request.Builder().url(server.url("/")).build()) }
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            }
        }

    private class Tokens : CloudTokenRepository {
        val values = mutableMapOf<String, CloudDriveCredentials>()

        override fun read(key: String) = values[key]

        override fun write(
            key: String,
            credentials: CloudDriveCredentials,
        ) {
            values[key] = credentials
        }
    }

    private fun source(kind: FileSourceKind) = FileSource("source", kind, "test", "https://unused.example")

    private fun credentials(value: CloudDriveCredentials) = FileSourceCredentials("app", value.encode())

    private fun MockWebServer.json(
        text: String,
        code: Int = 200,
    ) {
        enqueue(MockResponse().setResponseCode(code).setBody(text))
    }

    @Test fun paginated123ListingStopsAtBudgetAndPreservesCursor() =
        runBlocking {
            MockWebServer().use { server ->
                server.json(
                    """{"code":0,"data":{"lastFileId":9,"fileList":[{"fileId":8,"filename":"a.mkv","type":0,"size":42},{"fileId":9,"filename":"b","type":1}]}}""",
                )
                val client =
                    AndroidCloudDriveClient(
                        OkHttpClient(),
                        FileSourceSubtitleCache(directory = {
                            null
                        }),
                        Tokens(),
                        pan123Origin = server.url("/").toString().trimEnd('/'),
                    )
                val result =
                    client.listBounded(
                        source(FileSourceKind.Pan123),
                        credentials(CloudDriveCredentials(accessToken = "secret")),
                        emptyList(),
                        2,
                    )
                assertEquals(listOf("a.mkv", "b"), result.entries.map { it.name })
                assertTrue(result.truncated)
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("2", request.requestUrl?.queryParameter("limit"))
                assertEquals("Bearer secret", request.getHeader("Authorization"))
            }
        }

    @Test fun baiduExpiredTokenRotatesOnceAndPersistsRefreshTokenAcrossClients() =
        runBlocking {
            MockWebServer().use { server ->
                val tokens = Tokens()
                val origin = server.url("/").toString().trimEnd('/')
                val auth = credentials(CloudDriveCredentials("client", "private", "old", "refresh-old"))
                server.json("""{"errno":111}""")
                server.json("""{"access_token":"new","refresh_token":"refresh-new","expires_in":3600}""")
                server.json("""{"errno":0,"list":[]}""")
                AndroidCloudDriveClient(
                    OkHttpClient(),
                    FileSourceSubtitleCache(directory = {
                        null
                    }),
                    tokens,
                    baiduOrigin = origin,
                    oauthOrigin = origin,
                    now = {
                        1000
                    },
                ).list(source(FileSourceKind.Baidu), auth, emptyList())
                assertEquals(
                    "refresh-new",
                    tokens.values.values
                        .single()
                        .refreshToken,
                )
                assertEquals("old", server.takeRequest().requestUrl?.queryParameter("access_token"))
                assertEquals("refresh-old", server.takeRequest().requestUrl?.queryParameter("refresh_token"))
                assertEquals("new", server.takeRequest().requestUrl?.queryParameter("access_token"))
                server.json("""{"errno":0,"list":[]}""")
                AndroidCloudDriveClient(
                    OkHttpClient(),
                    FileSourceSubtitleCache(directory = {
                        null
                    }),
                    tokens,
                    baiduOrigin = origin,
                    oauthOrigin = origin,
                    now = { 2000 },
                ).list(
                    source(
                        FileSourceKind.Baidu,
                    ).copy(name = "renamed", rootSegments = listOf("new-root"), revision = 1),
                    auth,
                    emptyList(),
                )
                assertEquals("new", server.takeRequest().requestUrl?.queryParameter("access_token"))
            }
        }

    @Test fun oauthPending400IsNotAnAuthenticationFailure() =
        runBlocking {
            MockWebServer().use { server ->
                server.json("""{"error":"authorization_pending"}""", 400)
                val result =
                    AndroidCloudDriveAuthorization(
                        OkHttpClient(),
                        server.url("/").toString().trimEnd('/'),
                    ).pollBaiduAuthorization(CloudDriveCredentials("app", "secret"), "device")
                assertNull(result)
            }
        }

    @Test fun changedCredentialsDoNotReuseRotatedToken() =
        runBlocking {
            MockWebServer().use { server ->
                val tokens = Tokens()
                val origin = server.url("/").toString().trimEnd('/')
                val client =
                    AndroidCloudDriveClient(
                        OkHttpClient(),
                        FileSourceSubtitleCache(directory = { null }),
                        tokens,
                        baiduOrigin = origin,
                    )
                server.json("""{"errno":0,"list":[]}""")
                client.list(
                    source(FileSourceKind.Baidu),
                    credentials(CloudDriveCredentials(accessToken = "first")),
                    emptyList(),
                )
                server.takeRequest()
                server.json("""{"errno":0,"list":[]}""")
                client.list(
                    source(FileSourceKind.Baidu),
                    credentials(CloudDriveCredentials(accessToken = "second")),
                    emptyList(),
                )
                assertEquals("second", server.takeRequest().requestUrl?.queryParameter("access_token"))
            }
        }
}
