package com.yfuse.core.cast

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaMediaRelayTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test
    fun renderer_range_read_reaches_the_server_with_this_phones_identity_and_dlna_headers() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Range", "bytes 4-7/10")
                    .setHeader("Accept-Ranges", "bytes")
                    .setBody("4567"),
            )
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/videos/42/original.mkv?api_key=secret").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))

                assertTrue(local.startsWith("http://${loopback.hostAddress}:${relay.port}/yfuse-cast/"), local)
                assertTrue(local.endsWith("/media.mkv"), local)
                assertTrue("secret" !in local, "The access token stays on the phone")

                val response = request(local, "GET", "Range: bytes=4-7\r\ngetcontentFeatures.dlna.org: 1\r\n")
                assertTrue(response.startsWith("HTTP/1.1 206"), response)
                assertTrue("Content-Type: video/x-matroska\r\n" in response, response)
                assertTrue("Content-Range: bytes 4-7/10\r\n" in response, response)
                assertTrue("Accept-Ranges: bytes\r\n" in response, response)
                assertTrue("transferMode.dlna.org: Streaming\r\n" in response, response)
                assertTrue("contentFeatures.dlna.org: DLNA.ORG_OP=01;" in response, response)
                assertTrue(response.endsWith("\r\n\r\n4567"), response)

                val forwarded = assertNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("bytes=4-7", forwarded.getHeader("Range"))
                assertEquals("Emby for Android Mobile", forwarded.getHeader("User-Agent"))
                assertEquals("/videos/42/original.mkv?api_key=secret", forwarded.path)

                val activity = relay.activity()
                assertEquals(1, activity.requests)
                assertEquals(4L, activity.bytesServed)
                assertNotNull(activity.lastActivityAtMs)
            }
        }
    }

    @Test
    fun head_is_forwarded_and_keeps_the_length_without_a_body() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Content-Length", "4655267216"))
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/Videos/42/stream?static=true").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl, "mkv"), loopback))

                val response = request(local, "HEAD")
                assertTrue(response.startsWith("HTTP/1.1 200"), response)
                assertTrue("Content-Length: 4655267216\r\n" in response, response)
                assertTrue(response.endsWith("\r\n\r\n"), response)
                assertEquals("HEAD", upstream.takeRequest(2, TimeUnit.SECONDS)?.method)
                assertEquals(0L, relay.activity().bytesServed)
            }
        }
    }

    @Test
    fun unknown_paths_and_other_methods_are_refused_without_touching_the_server() {
        MockWebServer().use { upstream ->
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/media.mkv").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))
                val unknown = local.replaceAfter("/yfuse-cast/", "0123456789abcdef0123456789abcdef/media.mkv")

                assertTrue(request(unknown, "GET").startsWith("HTTP/1.1 404"))
                assertTrue(request(local, "POST").startsWith("HTTP/1.1 405"))
                assertNull(upstream.takeRequest(300, TimeUnit.MILLISECONDS))
                assertEquals(0, relay.activity().requests)
            }
        }
    }

    @Test
    fun a_refused_read_reaches_the_renderer_as_the_servers_status() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(403).setBody("blocked client"))
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/media.mkv").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))

                val response = request(local, "GET")
                assertTrue(response.startsWith("HTTP/1.1 403"), response)
                assertTrue("video/x-matroska" !in response, "A refusal is not labelled as the media")
                assertEquals(1, relay.activity().upstreamFailures)
            }
        }
    }

    @Test
    fun a_renderer_closing_its_read_is_not_held_against_the_server() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody(Buffer().write(ByteArray(32 * 1024 * 1024))))
            upstream.enqueue(MockResponse().setBody("next"))
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/media.mkv").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))
                val uri = URI(local)
                // A renderer reads the start of the file, then drops the connection to seek elsewhere.
                Socket(uri.host, uri.port).use { client ->
                    client.soTimeout = 5_000
                    client.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
                    client.getInputStream().read(ByteArray(64 * 1024))
                }
                assertTrue(request(local, "GET").endsWith("next"))
                awaitQuiet(relay)
                assertEquals(0, relay.activity().upstreamFailures)
            }
        }
    }

    @Test
    fun a_server_breaking_off_mid_body_is_counted() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setBody(Buffer().write(ByteArray(4 * 1024 * 1024)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/media.mkv").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))

                val response = request(local, "GET")
                assertTrue(response.startsWith("HTTP/1.1 200"), response.take(64))
                awaitQuiet(relay)
                assertEquals(1, relay.activity().upstreamFailures)
            }
        }
    }

    @Test
    fun republishing_keeps_the_path_and_a_new_load_keeps_the_previous_one_briefly() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("first"))
            upstream.start()
            relay().use { relay ->
                val first = upstream.url("/first.mkv").toString()
                val second = upstream.url("/second.mp4").toString()
                val firstLocal = assertNotNull(relay.publish(first, dlnaMediaFormat(first), loopback))
                assertEquals(firstLocal, relay.publish(first, dlnaMediaFormat(first), loopback))

                val secondLocal = assertNotNull(relay.publish(second, dlnaMediaFormat(second), loopback))
                assertNotEquals(firstLocal, secondLocal)
                assertTrue(secondLocal.endsWith("/media.mp4"), secondLocal)
                assertTrue(request(firstLocal, "GET").endsWith("first"))
                assertEquals(firstLocal, relay.publish(first, dlnaMediaFormat(first), loopback))
            }
        }
    }

    @Test
    fun hls_children_are_served_through_the_relay_too() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("#EXTM3U\n#EXTINF:6,\nsegment0.ts?api_key=secret\n"),
            )
            upstream.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t").setBody("segment"))
            upstream.start()
            relay().use { relay ->
                val upstreamUrl = upstream.url("/Videos/42/main.m3u8?api_key=secret").toString()
                val local = assertNotNull(relay.publish(upstreamUrl, dlnaMediaFormat(upstreamUrl), loopback))

                val manifest = request(local, "GET").substringAfter("\r\n\r\n")
                val child = manifest.lines().single { it.startsWith("http://") }
                assertTrue(child.startsWith("http://${loopback.hostAddress}:${relay.port}/yfuse-cast/"), manifest)
                assertTrue("secret" !in manifest, manifest)
                assertTrue(request(child, "GET").endsWith("segment"))
                upstream.takeRequest(2, TimeUnit.SECONDS)
                assertEquals(
                    "/Videos/42/segment0.ts?api_key=secret",
                    upstream.takeRequest(2, TimeUnit.SECONDS)?.path,
                )
            }
        }
    }

    @Test
    fun only_remote_or_encrypted_sources_are_relayed() {
        val lan = { _: String -> listOf(InetAddress.getByName("192.168.1.20")) }
        val public = { _: String -> listOf(InetAddress.getByName("47.112.219.60")) }

        assertEquals("https", dlnaRelayReason("https://192.168.1.20:8920/Videos/1/stream", lan))
        assertEquals("public_host", dlnaRelayReason("http://emby.example:8096/Videos/1/stream", public))
        assertNull(dlnaRelayReason("http://nas.lan:8096/Videos/1/stream", lan))
        assertNull(dlnaRelayReason("http://192.168.1.20:8096/Videos/1/stream", lan))
        assertEquals("unresolved_host", dlnaRelayReason("http://gone.example/v", { emptyList() }))
    }

    @Test
    fun local_network_addresses_are_told_from_public_ones() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.31.8", "169.254.10.1", "fd12::1", "fe80::1")
            .forEach { assertTrue(InetAddress.getByName(it).isLocalNetworkAddress(), it) }
        listOf("47.112.219.60", "8.8.8.8", "100.64.0.1", "2001:db8::1")
            .forEach { assertTrue(!InetAddress.getByName(it).isLocalNetworkAddress(), it) }
    }

    /** Until the relay has written nothing more for a moment: its workers have finished. */
    private fun awaitQuiet(relay: DlnaMediaRelay) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var last = -1L
        while (System.nanoTime() < deadline) {
            val served = relay.activity().bytesServed
            if (served == last) return
            last = served
            Thread.sleep(300)
        }
    }

    private fun relay() =
        DlnaMediaRelay(
            bindAddress = loopback,
            baseClient = OkHttpClient(),
            userAgent = { "Emby for Android Mobile" },
        )

    private fun request(
        url: String,
        method: String,
        headers: String = "",
    ): String {
        val uri = URI(url)
        return Socket(uri.host, uri.port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "$method ${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()} HTTP/1.1\r\nHost: tv\r\n$headers\r\n"
                    .toByteArray(),
            )
            client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }
}
