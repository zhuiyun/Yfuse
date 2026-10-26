package com.yfuse.core.widget

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoofCutoutClientTest {
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

    @Test
    fun uploads_pixels_without_source_credentials_and_preserves_png_response() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
                val client = PoofCutoutClient(endpoint = server.url("/v1/remove"))
                assertContentEquals(image, client.remove("test-poof-key", image))
                val request = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("test-poof-key", request.getHeader("x-api-key"))
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"image_file\"; filename=\"poster.png\""))
                assertTrue(!body.contains("image_url") && !body.contains("api_key=") && !body.contains("test-poof-key"))
            }
        }

    @Test
    fun billable_post_is_not_replayed_on_503_retry_after_zero() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image)))
                val error =
                    assertFailsWith<PoofApiException> {
                        PoofCutoutClient(
                            endpoint = server.url("/remove"),
                        ).remove("test-key", image)
                    }
                assertEquals(503, error.status)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun redirects_never_forward_the_key_to_another_origin() =
        runBlocking {
            MockWebServer().use { target ->
                MockWebServer().use { origin ->
                    target.start()
                    origin.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/capture")))
                    assertFailsWith<PoofApiException> {
                        PoofCutoutClient(
                            endpoint = origin.url("/remove"),
                        ).remove("test-key", image)
                    }
                    assertEquals(1, origin.requestCount)
                    assertEquals(0, target.requestCount)
                }
            }
        }

    @Test
    fun rate_limit_and_quota_errors_are_typed_without_exposing_the_error_body() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("private details"),
                )
                val error =
                    assertFailsWith<PoofApiException> {
                        PoofCutoutClient(
                            endpoint = server.url("/remove"),
                        ).remove("test-key", image)
                    }
                assertEquals(429, error.status)
                assertEquals(120_000L, error.retryAfterMillis)
                assertTrue(!error.message.orEmpty().contains("private"))
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun malformed_or_oversize_success_cannot_enter_the_image_cache() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("not a PNG"))
                server.enqueue(
                    MockResponse().setHeader("Content-Type", "image/png").setHeader(
                        "Content-Length",
                        MAX_POOF_IMAGE_BYTES + 1,
                    ),
                )
                val client = PoofCutoutClient(endpoint = server.url("/remove"))
                assertFailsWith<IllegalStateException> { client.remove("test-key", image) }
                assertFailsWith<IllegalStateException> { client.remove("test-key", image) }
            }
        }

    @Test
    fun retry_after_accepts_seconds_and_http_date_with_a_bounded_pause() {
        assertEquals(120_000L, poofRetryAfterMillis("120"))
        assertEquals(86_400_000L, poofRetryAfterMillis("999999999"))
        assertEquals(60_000L, poofRetryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", now = 0))
        assertNull(poofRetryAfterMillis("invalid"))
    }
}
