package com.yfuse.core.stremio

import com.yfuse.core.security.TestSecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StremioRepositoryTest {
    @Test
    fun install_updates_exact_url_but_keeps_different_configurations_and_profiles_separate() =
        runTest {
            HttpClient(MockEngine { error("No network expected") }).use { http ->
                val secure = TestSecureStore()
                val first = StremioRepository(StremioClient(http), secure, "adult-a")
                first.load()
                first.install("https://plugin.example/private-a/manifest.json", fixtureManifest())
                val key =
                    first.addons.value
                        .single()
                        .key
                first.install(
                    "https://plugin.example/private-a/manifest.json",
                    fixtureManifest().copy(version = "2.0.0"),
                )
                assertEquals(
                    key,
                    first.addons.value
                        .single()
                        .key,
                )
                first.install("https://plugin.example/private-b/manifest.json", fixtureManifest())
                val second = StremioRepository(StremioClient(http), secure, "adult-b")
                second.load()
                assertTrue(second.addons.value.isEmpty())
                val restored = StremioRepository(StremioClient(http), secure, "adult-a")
                restored.load()
                assertEquals(2, restored.addons.value.size)
                assertFalse(
                    restored.addons.value
                        .toString()
                        .contains("private-a"),
                )
                assertEquals(setOf("stremio.addons.adult-a"), secure.storedKeys())
            }
        }

    @Test
    fun failed_persistence_keeps_existing_state_and_corruption_does_not_reset_it() =
        runTest {
            HttpClient(MockEngine { error("No network expected") }).use { http ->
                val secure = TestSecureStore()
                val repository = StremioRepository(StremioClient(http), secure, "adult")
                repository.load()
                repository.install("https://plugin.example/manifest.json", fixtureManifest())
                secure.failWrites = true
                assertFailsWith<IllegalStateException> {
                    repository.remove(
                        repository.addons.value
                            .single()
                            .key,
                    )
                }
                assertEquals(1, repository.addons.value.size)
                secure.corruptedKeys += "stremio.addons.adult"
                val restored = StremioRepository(StremioClient(http), secure, "adult")
                assertFailsWith<IllegalStateException> { restored.load() }
                assertFailsWith<IllegalStateException> {
                    restored.install(
                        "https://other.example/manifest.json",
                        fixtureManifest(),
                    )
                }
                assertTrue(secure.storedKeys().isNotEmpty())
            }
        }

    @Test
    fun disabled_plugins_are_not_requested_and_one_failure_keeps_other_streams() =
        runTest {
            // MockEngine handlers run concurrently, just like the plugin requests they record.
            val hosts = ConcurrentLinkedQueue<String>()
            HttpClient(
                MockEngine { request ->
                    hosts.add(request.url.host)
                    when (request.url.host) {
                        "bad.example" -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                        else -> respond("""{"streams":[{"url":"https://cdn.example/movie.m3u8"}]}""")
                    }
                },
            ).use { http ->
                val repository = StremioRepository(StremioClient(http), TestSecureStore(), "adult")
                repository.load()
                listOf("good", "bad", "disabled").forEach { name ->
                    repository.install("https://$name.example/manifest.json", fixtureManifest().copy(name = name))
                }
                repository.setEnabled(
                    repository.addons.value
                        .last()
                        .key,
                    false,
                )
                val result = repository.streams("movie", "tt1")
                assertEquals(1, result.values.size)
                assertEquals("good", result.values.single().addonName)
                assertEquals(1, result.failures.size)
                assertEquals(setOf("good.example", "bad.example"), hosts.toSet())
            }
        }

    @Test
    fun resource_filters_prevent_unrelated_network_requests() =
        runTest {
            HttpClient(MockEngine { error("Filtered plugins must not be contacted") }).use { http ->
                val repository = StremioRepository(StremioClient(http), TestSecureStore(), "adult")
                repository.load()
                repository.install(
                    "https://plugin.example/manifest.json",
                    fixtureManifest().copy(idPrefixes = listOf("custom:")),
                )
                assertTrue(repository.streams("movie", "tt1").values.isEmpty())
                assertTrue(repository.streams("series", "custom:1").values.isEmpty())
            }
        }

    @Test
    fun cancellation_is_not_reported_as_plugin_failure() =
        runTest {
            HttpClient(MockEngine { throw CancellationException("cancelled") }).use { http ->
                val repository = StremioRepository(StremioClient(http), TestSecureStore(), "adult")
                repository.load()
                repository.install("https://plugin.example/manifest.json", fixtureManifest())
                assertFailsWith<CancellationException> { repository.streams("movie", "tt1") }
            }
        }

    @Test
    fun configuration_required_plugin_is_not_silently_installed() =
        runTest {
            HttpClient(MockEngine { error("No network expected") }).use { http ->
                val repository = StremioRepository(StremioClient(http), TestSecureStore(), "adult")
                repository.load()
                assertFailsWith<IllegalArgumentException> {
                    repository.install(
                        "https://plugin.example/manifest.json",
                        fixtureManifest().copy(behaviorHints = StremioManifestHints(configurationRequired = true)),
                    )
                }
                assertTrue(repository.addons.value.isEmpty())
            }
        }
}

internal fun fixtureManifest() =
    StremioManifest(
        id = "test.addon",
        name = "Test",
        version = "1.0.0",
        types = listOf("movie"),
        resources = listOf("catalog", "meta", "stream", "subtitles").map(::JsonPrimitive),
        catalogs = listOf(StremioCatalog("movie", "all", extra = listOf(StremioExtra("search"), StremioExtra("skip")))),
    )
