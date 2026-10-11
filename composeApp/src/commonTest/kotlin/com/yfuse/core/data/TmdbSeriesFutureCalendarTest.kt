package com.yfuse.core.data

import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbSeriesFutureCalendarTest {
    @Test
    fun next_episode_is_kept_when_the_season_response_only_contains_aired_episodes() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/3/tv/42" ->
                                json(
                                    """{"id":42,"name":"剧集","number_of_seasons":1,"origin_country":["CN"],
                        "next_episode_to_air":{"season_number":1,"episode_number":2,"air_date":"2026-10-12","name":"明日更新"},
                        "last_episode_to_air":{"season_number":1,"episode_number":1,"air_date":"2026-10-11"}}""",
                                )
                            "/3/tv/42/season/1" ->
                                json(
                                    """{"episodes":[{"season_number":1,"episode_number":1,"air_date":"2026-10-11"}]}""",
                                )
                            else -> error("Unexpected schedule path")
                        }
                    },
                ) {
                    install(
                        ContentNegotiation,
                    ) { json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) }
                }
            try {
                val rows = TmdbRepository(client).seriesAiringCalendar(42, "剧集").getOrThrow()
                assertEquals(listOf(1, 2), rows.map { it.episodeNumber })
                assertEquals("2026-10-12", rows.last().airDate)
            } finally {
                client.close()
            }
        }
}
