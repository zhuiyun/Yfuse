package com.yfuse.feature.widgets

import com.yfuse.core2.android.toCore2MediaItems
import com.yfuse.feature.player.PlayerCustomHeaders
import com.yfuse.feature.player.WidgetPlaybackHeadersInterceptor
import com.yfuse.feature.player.WidgetPlaybackRequest
import com.yfuse.feature.player.externalPlaybackItem
import com.yfuse.feature.player.persistentPlaybackCacheUrl
import com.yfuse.feature.player.widgetPlaybackRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WidgetPlaybackTransportTest {
    @Test
    fun compatibility_engines_apply_headers_only_to_the_original_origin() {
        MockWebServer().use { origin ->
            MockWebServer().use { redirect ->
                redirect.enqueue(MockResponse().setBody("video"))
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirect.url("/video")))
                val item =
                    externalPlaybackItem(origin.url("/video").toString(), "Test").copy(
                        customHeaders =
                            PlayerCustomHeaders(
                                mapOf(
                                    "X-Source-Token" to "private",
                                    "Referer" to "https://site/",
                                ),
                            ),
                    )
                val client =
                    OkHttpClient
                        .Builder()
                        .addNetworkInterceptor(
                            WidgetPlaybackHeadersInterceptor(),
                        ).build()
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                item.url,
                            ).tag(WidgetPlaybackRequest::class.java, item.widgetPlaybackRequest())
                            .build(),
                    ).execute()
                    .use {
                        assertEquals(200, it.code)
                    }
                assertEquals("private", origin.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Source-Token"))
                val redirected = requireNotNull(redirect.takeRequest(3, TimeUnit.SECONDS))
                assertNull(redirected.getHeader("X-Source-Token"))
                assertNull(redirected.getHeader("Referer"))
                assertNull(item.persistentPlaybackCacheUrl())
            }
        }
    }

    @Test
    fun ycore_maps_component_headers_without_forwarding_them_to_loopback() {
        val item =
            externalPlaybackItem("https://source.example/movie.mp4", "Movie").copy(
                customHeaders = PlayerCustomHeaders(mapOf("Referer" to "https://source.example/")),
            )
        val direct = listOf(item).toCore2MediaItems("Yfuse", appVersion = { "test" }).single()
        assertEquals("https://source.example/", direct.headers["Referer"])
        val local =
            listOf(item)
                .toCore2MediaItems("Yfuse", appVersion = { "test" }, localize = { _, _ ->
                    "http://127.0.0.1:1234/video"
                })
                .single()
        assertFalse(local.headers.containsKey("Referer"))
    }
}
