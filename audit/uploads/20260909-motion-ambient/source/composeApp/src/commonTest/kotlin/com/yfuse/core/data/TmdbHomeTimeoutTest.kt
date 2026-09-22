package com.yfuse.core.data

import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbHomeTimeoutTest {
    @Test
    fun unreachable_feeds_share_one_timeout_window_including_permit_wait() =
        runTest {
            val client = client { awaitCancellation() }
            try {
                val result = TmdbRepository(client).refreshHome()

                assertTrue(result.isFailure)
                assertEquals(
                    TmdbRecommendationFailure.TIMEOUT,
                    (result.exceptionOrNull() as TmdbRecommendationException).failure,
                )
                assertEquals(20_000L, currentTime)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_successful_shelf_and_carousel_survive_other_feeds_and_runtime_enrichment_timing_out() =
        runTest {
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/movie/popular")) {
                        json(
                            """{"results":[{"id":42,"title":"保留的轮播电影","poster_path":"/poster.jpg",
                                "backdrop_path":"/backdrop.jpg","genre_ids":[18]}]}""",
                        )
                    } else {
                        awaitCancellation()
                    }
                }
            try {
                val refresh = TmdbRepository(client).refreshHome().getOrThrow()
                val result = refresh.content

                assertEquals(
                    42,
                    result.rows
                        .single()
                        .items
                        .single()
                        .id,
                )
                assertEquals(42, result.featured.single().id)
                assertEquals(setOf("热门", "最新上线", "正在上映", "即将上映"), refresh.incompleteRows)
                assertEquals(TmdbRecommendationFailure.TIMEOUT, refresh.failure)
                assertEquals(22_000L, currentTime)
            } finally {
                client.close()
            }
        }

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                },
            ),
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
