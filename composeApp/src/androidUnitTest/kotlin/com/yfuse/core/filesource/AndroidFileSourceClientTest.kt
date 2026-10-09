package com.yfuse.core.filesource

import jcifs.smb.NtStatus
import jcifs.smb.SmbException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidFileSourceClientTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheRoot: File
    private lateinit var client: AndroidFileSourceClient
    private val credentials = FileSourceCredentials("爱丽丝", "密码 pw")

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheRoot = Files.createTempDirectory("filesource-test").toFile()
        client =
            AndroidFileSourceClient(
                http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
                subtitles = FileSourceSubtitleCache({ cacheRoot }),
                smb = UnusedSmb,
            )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        cacheRoot.deleteRecursively()
    }

    private fun source() =
        FileSource(
            id = "fs0123456789abcdef01234567",
            kind = FileSourceKind.Alist,
            name = "Alist",
            origin = server.url("/").toString().trimEnd('/'),
            rootSegments = listOf("dav"),
            username = credentials.username,
        )

    @Test
    fun propfind_asks_for_one_level_with_utf8_basic_auth_and_returns_the_children() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                    .setBody(
                        """
                        <D:multistatus xmlns:D="DAV:">
                          <D:response><D:href>/dav/%E7%94%B5%E5%BD%B1/</D:href>
                            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                          <D:response><D:href>/dav/%E7%94%B5%E5%BD%B1/Dune%20(2021).mkv</D:href>
                            <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>9</D:getcontentlength></D:prop>
                            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                        </D:multistatus>
                        """.trimIndent(),
                    ),
            )

            val entries = client.list(source(), credentials, listOf("电影"))

            assertEquals(listOf(FileSourceEntry("Dune (2021).mkv", directory = false, sizeBytes = 9L)), entries)
            val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PROPFIND", request.method)
            assertEquals("/dav/%E7%94%B5%E5%BD%B1/", request.path)
            assertEquals("1", request.getHeader("Depth"))
            val expectedAuth = Base64.getEncoder().encodeToString("爱丽丝:密码 pw".toByteArray(Charsets.UTF_8))
            assertEquals("Basic $expectedAuth", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("getcontentlength"))
        }

    @Test
    fun a_redirected_propfind_is_followed_as_a_propfind() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/dav/moved/"))
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """
                    <D:multistatus xmlns:D="DAV:">
                      <D:response><D:href>/dav/moved/</D:href>
                        <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                      <D:response><D:href>/dav/moved/a.mp4</D:href>
                        <D:propstat><D:prop><D:resourcetype/></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                    </D:multistatus>
                    """.trimIndent(),
                ),
            )

            val entries = client.list(source(), credentials, listOf("old"))

            assertEquals(listOf("a.mp4"), entries.map { it.name })
            server.takeRequest()
            val redirected = server.takeRequest()
            assertEquals("PROPFIND", redirected.method)
            assertEquals("1", redirected.getHeader("Depth"))
        }

    @Test
    fun http_statuses_become_failures_a_person_can_act_on() =
        runBlocking {
            val cases =
                listOf(
                    MockResponse().setResponseCode(401) to FileSourceFailure.Unauthorized,
                    MockResponse().setResponseCode(403) to FileSourceFailure.Forbidden,
                    MockResponse().setResponseCode(404) to FileSourceFailure.NotFound,
                    MockResponse().setResponseCode(405) to FileSourceFailure.NotWebDav,
                    MockResponse().setResponseCode(502) to FileSourceFailure.Server,
                    MockResponse().setResponseCode(200).setBody("<html>login</html>") to FileSourceFailure.NotWebDav,
                )
            cases.forEach { (response, expected) ->
                server.enqueue(response)
                val failure = assertFailsWith<FileSourceException> { client.list(source(), credentials, emptyList()) }
                assertEquals(expected, failure.failure, "HTTP ${response.status}")
            }
        }

    @Test
    fun an_unreachable_host_is_reported_as_such() =
        runBlocking {
            val port = server.port
            server.shutdown()
            val offline = source().copy(origin = "http://127.0.0.1:$port")

            val failure = assertFailsWith<FileSourceException> { client.list(offline, credentials, emptyList()) }

            assertEquals(FileSourceFailure.Unreachable, failure.failure)
        }

    @Test
    fun a_gbk_subtitle_is_cached_once_as_utf8_behind_a_redirect() =
        runBlocking {
            val text = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界。这是简体中文字幕。\n"
            val gbk = okio.Buffer().write(text.toByteArray(Charset.forName("GBK")))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/sub.srt"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(gbk))
            val entry = FileSourceEntry("Movie.chs.srt", directory = false, sizeBytes = 64L, modifiedEpochMs = 7L)

            val first = client.cacheSubtitle(source(), credentials, listOf("电影", "Movie.chs.srt"), entry)
            val second = client.cacheSubtitle(source(), credentials, listOf("电影", "Movie.chs.srt"), entry)

            assertEquals(first, second)
            val uri = URI(first)
            assertEquals("file", uri.scheme)
            assertEquals(text, File(uri).readText(Charsets.UTF_8))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun an_oversized_sidecar_is_refused_before_it_is_read() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Length", (MAX_SUBTITLE_BYTES + 1).toString()),
            )
            val entry = FileSourceEntry("Huge.ass", directory = false)

            val failure =
                assertFailsWith<FileSourceException> {
                    client.cacheSubtitle(source(), credentials, listOf("Huge.ass"), entry)
                }

            assertEquals(FileSourceFailure.TooLarge, failure.failure)
        }

    @Test
    fun subtitle_text_is_reencoded_only_when_it_is_not_already_unicode() {
        val simplified = "这是一段用于检测编码的简体中文字幕文本，包含常见汉字。"
        val traditional = "這是一段用於檢測編碼的繁體中文字幕文本，包含常見漢字與標點。"
        val utf8 = simplified.toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a".toByteArray(Charsets.UTF_16LE)

        assertContentEquals(utf8, utf8SubtitleBytes(utf8, "a.srt"))
        assertContentEquals(bom, utf8SubtitleBytes(bom, "a.srt"))
        assertEquals(
            simplified,
            utf8SubtitleBytes(simplified.toByteArray(Charset.forName("GBK")), "a.srt").decodeToString(),
        )
        assertEquals(
            traditional,
            utf8SubtitleBytes(traditional.toByteArray(Charset.forName("Big5")), "a.srt").decodeToString(),
        )
        assertEquals(
            traditional,
            utf8SubtitleBytes(traditional.toByteArray(Charset.forName("Big5")), "a.cht.srt").decodeToString(),
        )
    }

    @Test
    fun smb_errors_map_by_status_and_by_cause() {
        assertEquals(
            FileSourceFailure.Unauthorized,
            smbFailureFor(SmbException(NtStatus.NT_STATUS_LOGON_FAILURE, true)),
        )
        assertEquals(FileSourceFailure.Forbidden, smbFailureFor(SmbException(NtStatus.NT_STATUS_ACCESS_DENIED, true)))
        assertEquals(FileSourceFailure.NotFound, smbFailureFor(SmbException(NtStatus.NT_STATUS_BAD_NETWORK_NAME, true)))
        assertEquals(
            FileSourceFailure.Unreachable,
            smbFailureFor(SmbException("Failed to connect", ConnectException("refused"))),
        )
        assertEquals(FileSourceFailure.Timeout, smbFailureFor(SmbException("slow", SocketTimeoutException())))
        assertEquals(FileSourceFailure.Tls, httpFailureFor(SSLHandshakeException("bad certificate")))
    }

    private object UnusedSmb : SmbShareReader {
        override suspend fun list(
            url: String,
            credentials: FileSourceCredentials,
            sharesOnly: Boolean,
        ): List<FileSourceEntry> = error("SMB is not used by these tests")

        override suspend fun read(
            url: String,
            credentials: FileSourceCredentials,
            maxBytes: Int,
        ): ByteArray = error("SMB is not used by these tests")
    }
}
