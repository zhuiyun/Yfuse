package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidHttpEnginePolicyTest {
    @Test
    fun android_actual_uses_the_websocket_capable_okhttp_engine() {
        val engine = embyHttpEngine()

        try {
            assertIs<OkHttpEngine>(engine)
            assertTrue(WebSocketCapability in engine.supportedCapabilities)
        } finally {
            engine.close()
        }
    }

    @Test
    fun android_actual_keeps_okhttp_platform_tls_defaults() {
        val source = androidHttpEngineSource().readText()

        assertTrue(
            "actual fun embyHttpEngine(): HttpClientEngine = OkHttp.create()" in source,
            "The Android actual must return a fresh, unconfigured OkHttp engine",
        )
        val forbiddenExecutableConfiguration =
            Regex(
                pattern =
                    """import\s+io\.ktor\.client\.engine\.cio|""" +
                        """\bCIO\s*\.\s*create\s*\(|""" +
                        """\bpreconfigured\s*=|""" +
                        """\b(?:sslSocketFactory|trustManager|hostnameVerifier|checkServerTrusted)\s*(?:\(|=)""",
            )
        assertFalse(
            forbiddenExecutableConfiguration.containsMatchIn(source),
            "Android HTTP engine must use OkHttp's platform TLS defaults",
        )
    }

    @Test
    fun android_actual_rejects_an_untrusted_https_certificate() =
        runBlocking {
            val heldCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("localhost")
                    .build()
            val serverCertificates =
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(heldCertificate)
                    .build()
            val server =
                MockWebServer().apply {
                    useHttps(serverCertificates.sslSocketFactory(), false)
                    enqueue(MockResponse().setBody("must not be trusted"))
                    start()
                }
            val client = HttpClient(embyHttpEngine())

            try {
                val failure =
                    assertFails {
                        client.get(server.url("/private").toString()).bodyAsText()
                    }
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any { it is SSLHandshakeException },
                    "Expected a certificate handshake failure, got $failure",
                )
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun okhttp_rejects_a_trusted_certificate_for_the_wrong_hostname() =
        runBlocking {
            val heldCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("expected.example")
                    .build()
            val serverCertificates =
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(heldCertificate)
                    .build()
            val clientCertificates =
                HandshakeCertificates
                    .Builder()
                    .addTrustedCertificate(heldCertificate.certificate)
                    .build()
            val server =
                MockWebServer().apply {
                    useHttps(serverCertificates.sslSocketFactory(), false)
                    enqueue(MockResponse().setBody("wrong host must be rejected"))
                    start()
                }
            val client =
                HttpClient(
                    OkHttp.create {
                        config {
                            sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                        }
                    },
                )

            try {
                val failure =
                    assertFails {
                        client.get(server.url("/wrong-host").toString()).bodyAsText()
                    }
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any { it is SSLPeerUnverifiedException },
                    "Expected a hostname verification failure, got $failure",
                )
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun android_actual_completes_a_real_websocket_handshake() =
        runBlocking {
            val server =
                MockWebServer().apply {
                    enqueue(
                        MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onMessage(
                                    webSocket: WebSocket,
                                    text: String,
                                ) {
                                    webSocket.send("echo:$text")
                                    webSocket.close(1000, "test complete")
                                }
                            },
                        ),
                    )
                    start()
                }
            val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
            val url = server.url("/echo").toString().replaceFirst("http://", "ws://")

            try {
                withTimeout(5_000L) {
                    client.webSocket(urlString = url) {
                        send(Frame.Text("ping"))
                        assertEquals("echo:ping", (incoming.receive() as Frame.Text).readText())
                    }
                }
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun non_shared_public_hosts_remain_https_only_in_network_security_config() {
        val config = projectFile("src/androidMain/res/xml/network_security_config.xml").readText()
        val httpsOnlyDomains = httpsOnlyDomains(config)

        listOf("yfuse.zhuiyun.site", "themoviedb.org").forEach { domain ->
            assertEquals(
                1,
                httpsOnlyDomains.count { it == domain },
                "$domain must appear exactly once inside an HTTPS-only domain config",
            )
        }
    }

    @Test
    fun shared_ip_keeps_cleartext_available_for_user_managed_emby_ports() {
        val config = projectFile("src/androidMain/res/xml/network_security_config.xml").readText()

        assertTrue(
            Regex("""<base-config\s+cleartextTrafficPermitted="true"\s*/>""")
                .containsMatchIn(config),
            "User-managed HTTP Emby servers must remain reachable",
        )
        assertFalse(
            "47.112.219.60" in httpsOnlyDomains(config),
            "Network security config is host-only; blocking this IP would also block " +
                "the user's http://47.112.219.60:19001 Emby server",
        )
    }

    @Test
    fun official_services_on_the_shared_ip_are_hard_coded_to_https() {
        val updateSource =
            projectFile(
                "src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt",
            ).readText()
        val accountModelsSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/core/account/AccountModels.kt",
            ).readText()
        val accountApiSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/core/account/AccountApi.kt",
            ).readText()
        val watchPreferencesSource =
            projectFile(
                "src/commonMain/kotlin/com/yfuse/core/data/WatchTogetherPreferences.kt",
            ).readText()

        assertTrue(
            Regex("""UPDATE_MANIFEST\s*=\s*"https://47\.112\.219\.60/""")
                .containsMatchIn(updateSource),
            "The update manifest must stay on HTTPS",
        )
        assertTrue(
            Regex("""ACCOUNT_BASE_URL[^=]*=\s*"https://47\.112\.219\.60"""")
                .containsMatchIn(accountModelsSource),
            "The account service must stay on HTTPS",
        )
        assertTrue(
            "require(it.startsWith(\"https://\"))" in accountApiSource,
            "The account client must reject cleartext origins at runtime",
        )
        assertTrue(
            Regex("""DEFAULT_ENDPOINT\s*=\s*"https://47\.112\.219\.60"""")
                .containsMatchIn(watchPreferencesSource),
            "The built-in watch-together endpoint must stay on HTTPS",
        )
    }

    private fun httpsOnlyDomains(config: String): List<String> =
        Regex(
            pattern = """<domain-config\s+cleartextTrafficPermitted="false"[^>]*>(.*?)</domain-config>""",
            option = RegexOption.DOT_MATCHES_ALL,
        ).findAll(config)
            .flatMap { block ->
                Regex("""<domain(?:\s+[^>]*)?>([^<]+)</domain>""")
                    .findAll(block.groupValues[1])
                    .map { it.groupValues[1].trim() }
            }.toList()

    private fun androidHttpEngineSource(): File =
        projectFile(
            "src/androidMain/kotlin/com/yfuse/core/network/HttpClientFactory.android.kt",
        )

    private fun projectFile(moduleRelativePath: String): File =
        sequenceOf(
            File(moduleRelativePath),
            File("composeApp", moduleRelativePath),
        ).firstOrNull(File::isFile)
            ?: error("Cannot locate $moduleRelativePath from ${File(".").absolutePath}")
}
