package com.yfuse.core.stremio

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioProtocolTest {
    @Test
    fun configured_paths_survive_stremio_scheme_normalization() {
        assertEquals(
            "https://addon.example/user%2Fsecret/manifest.json",
            normalizeStremioManifestUrl(" stremio://addon.example/user%2Fsecret/manifest.json "),
        )
        assertEquals(
            "https://addon.example/user%2Fsecret/configure",
            stremioConfigurationUrl("https://addon.example/user%2Fsecret/manifest.json"),
        )
    }

    @Test
    fun invalid_install_addresses_never_become_requests() {
        listOf(
            "file:///manifest.json",
            "https://host/",
            "https://user:password@host/manifest.json",
            "https://host/manifest.json?token=secret",
            "https://host/manifest.json#fragment",
            "https://ho\nst/manifest.json",
            "https://host/a\u200Bb/manifest.json",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException> { normalizeStremioManifestUrl(url) }
        }
    }

    @Test
    fun resource_and_extra_values_are_encoded_once_without_losing_configured_path() {
        val url =
            stremioResourceUrl(
                "https://addon.example/token%2Fpath/manifest.json",
                "catalog",
                "movie",
                "a/b?c",
                linkedMapOf("search" to "星球 & a/b + c", "skip" to "100"),
            )
        assertEquals(
            "https://addon.example/token%2Fpath/catalog/movie/a%2Fb%3Fc/search=%E6%98%9F%E7%90%83%20%26%20a%2Fb%20%2B%20c&skip=100.json",
            url,
        )
    }

    @Test
    fun string_resources_inherit_filters_but_object_resources_use_their_own() {
        val manifest =
            stremioJson.decodeFromString<StremioManifest>(
                """{"id":"test","name":"Test","version":"1.0.0","types":["movie"],"idPrefixes":["tt"],"resources":["meta",{"name":"stream","types":["series"]}]}""",
            )
        assertTrue(manifest.supports("meta", "movie", "tt123"))
        assertFalse(manifest.supports("meta", "movie", "other:1"))
        assertFalse(manifest.supports("meta", "series", "tt123"))
        assertTrue(manifest.supports("stream", "series", "other:1"))
        assertFalse(manifest.supports("stream", "movie", "tt123"))
    }

    @Test
    fun required_and_legacy_catalog_extras_are_respected() {
        val catalog =
            StremioCatalog(
                "movie",
                "search",
                extraSupported = listOf("search", "skip"),
                extraRequired = listOf("search"),
            )
        assertFalse(catalog.accepts(emptyMap()))
        assertFalse(catalog.accepts(mapOf("search" to " ")))
        assertTrue(catalog.accepts(mapOf("search" to "Bunny")))
        assertEquals(2, catalog.extras.size)
    }

    @Test
    fun only_supported_direct_streams_can_launch() {
        listOf(
            "https://cdn.example/video.m3u8?sig=a%2Bb",
            "http://192.168.1.2/movie.mpd",
            "https://cdn.example/movie.mkv",
        ).forEach {
            assertNull(StremioStream(url = it).unsupportedReason)
        }
        listOf(
            StremioStream(infoHash = "abc"),
            StremioStream(url = "magnet:?xt=urn:btih:abc"),
            StremioStream(ytId = "123"),
            StremioStream(externalUrl = "https://example.com/watch"),
            StremioStream(url = "file:///private/file"),
            StremioStream(
                url = "https://cdn.example/movie",
                behaviorHints =
                    StremioStreamHints(
                        proxyHeaders =
                            mapOf(
                                "request" to buildJsonObject { put("Authorization", "secret") },
                            ),
                    ),
            ),
        ).forEach {
            assertNotNull(it.unsupportedReason)
        }
    }
}
