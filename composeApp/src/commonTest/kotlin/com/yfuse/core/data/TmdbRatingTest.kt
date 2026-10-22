package com.yfuse.core.data

import com.yfuse.core.model.MediaRating
import com.yfuse.core.model.RatingSource
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TmdbRatingTest {
    @Test
    fun exact_id_score_fetch_uses_the_existing_client_without_credits_or_search() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("/3/movie/123", request.url.encodedPath)
                        assertNull(request.url.parameters["append_to_response"])
                        json("""{"id":123,"vote_average":8.4}""")
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                assertEquals(
                    MediaRating(RatingSource.Tmdb, 8.4),
                    TmdbRepository(client).rating("movie", 123).getOrThrow(),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun cancellation_is_not_converted_to_an_empty_success() =
        runTest {
            val client = HttpClient(MockEngine { throw CancellationException("left detail") })
            try {
                assertFailsWith<CancellationException> { TmdbRepository(client).rating("tv", 123) }
            } finally {
                client.close()
            }
        }
}
