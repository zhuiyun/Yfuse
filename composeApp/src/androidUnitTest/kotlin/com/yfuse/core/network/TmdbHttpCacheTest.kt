package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbHttpCacheTest {
    @Test
    fun a_repeated_cacheable_tmdb_read_is_answered_from_the_response_cache() =
        runBlocking {
            val directory = Files.createTempDirectory("tmdb-cache").toFile()
            val cache = Cache(directory, 1024L * 1024L)
            val server =
                MockWebServer().apply {
                    enqueue(
                        MockResponse()
                            .setHeader("Cache-Control", "max-age=600")
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"id":1}"""),
                    )
                    start()
                }
            val client = HttpClient(tmdbHttpEngine { cache })

            try {
                repeat(2) {
                    assertEquals("""{"id":1}""", client.get(server.url("/3/tv/1").toString()).bodyAsText())
                }
                assertEquals(1, server.requestCount)
                assertEquals(1, cache.hitCount())
            } finally {
                client.close()
                server.shutdown()
                cache.close()
                directory.deleteRecursively()
            }
        }
}
