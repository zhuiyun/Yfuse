package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerIconCatalogTest {
    @Test
    fun readsNamedEntriesAndEscapesSpacesWithoutDiscardingOtherIcons() {
        val json =
            """
            {"name":"良友媒体图标库","icons":[
              {"name":"DC Studios","url":"https://example.com/DC Studios.png"},
              null,{"name":"坏图","url":"file:///tmp/icon.png"},
              {"name":"凭证","url":"https://u:p@example.com/i"},
              {"url":"https://example.com/i"},{"name":"重复","url":"https://example.com/DC%20Studios.png"},
              {"name":"猫","url":"https://example.com/cat.jpeg"}]}
            """.trimIndent()
        assertEquals(
            listOf(
                ServerCatalogIcon("DC Studios", "https://example.com/DC%20Studios.png"),
                ServerCatalogIcon("猫", "https://example.com/cat.jpeg"),
            ),
            parseServerIconCatalog(json),
        )
    }

    @Test
    fun failedLoadCanBeRetriedAndSuccessfulIndexIsReused() =
        runTest {
            var requests = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(LIANGYOU_ICON_CATALOG, request.url.toString())
                        requests++
                        if (requests == 1) {
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond("""{"icons":[{"name":"影院","url":"https://example.com/icon.png"}]}""")
                        }
                    },
                )
            try {
                val catalog = ServerIconCatalog(http, MapSettings())
                try {
                    assertEquals(listOf(LIANGYOU_ICON_CATALOG), catalog.state.first { !it.loading }.failedSources)
                    catalog.refresh()
                    val ready = catalog.state.first { !it.loading && it.failedSources.isEmpty() }
                    assertEquals("影院", ready.icons.single().name)
                    assertEquals(ready, catalog.state.first())
                    assertEquals(2, requests)
                } finally {
                    catalog.close()
                }
            } finally {
                http.close()
            }
        }

    @Test
    fun multipleSourcesPersistInOrderAndOneUnavailableSourceDoesNotHideTheOthers() =
        runTest {
            val settings = MapSettings()
            val first = "https://first.example/catalog.json"
            val second = "https://second.example/catalog.json"
            val bad = "https://unavailable.example/catalog.json"
            val http =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == "unavailable.example") {
                            respond("", HttpStatusCode.NotFound)
                        } else {
                            respond("""{"icons":[{"name":"海棠","url":"https://${request.url.host}/icon.png"}]}""")
                        }
                    },
                )
            val catalog = ServerIconCatalog(http, settings)
            try {
                catalog.saveSources("$second\n$bad\n$first\n$second")
                assertFailsWith<IllegalArgumentException> { catalog.saveSources("$first\nfile:///icon.json") }
                val ready = catalog.state.first { !it.loading && it.sources.first() == second }
                assertEquals(listOf(second, bad, first), ready.sources)
                assertEquals(listOf(bad), ready.failedSources)
                assertEquals(2, ready.icons.size)
                assertEquals("https://second.example/icon.png", matchServerIcon(ready.icons, "海棠", "")?.url)
                val reopened = ServerIconCatalog(http, settings)
                try {
                    assertEquals(ready.sources, reopened.sources)
                } finally {
                    reopened.close()
                }
                catalog.saveSources(first)
                assertEquals(
                    "https://first.example/icon.png",
                    catalog.state
                        .first {
                            !it.loading &&
                                it.sources == listOf(first)
                        }.icons
                        .single()
                        .url,
                )
                catalog.saveSources("")
                assertEquals(emptyList(), catalog.state.first { it.sources.isEmpty() }.icons)
            } finally {
                catalog.close()
                http.close()
            }
        }

    @Test
    fun matchesNamesBeforeHostAndKeepsMoreSpecificBrands() {
        val icons =
            listOf("海棠", "海棠一号", "Nebula", "DC Studios").map {
                ServerCatalogIcon(it, "https://example.com/${it.hashCode()}.png")
            }
        assertEquals(icons[1], matchServerIcon(icons, "我的海棠一号（主线）", "https://nebula.example"))
        assertEquals(icons[3], matchServerIcon(icons, "ＤＣ-Studios 影院", ""))
        assertEquals(icons[2], matchServerIcon(icons, "家用媒体库", "https://emby.nebula.example:8920"))
        assertNull(matchServerIcon(icons, "Nebularity", "https://192.168.1.12"))
        assertEquals(icons[3], matchServerIcon(icons, "My DC Studios US", ""))
        val generic = ServerCatalogIcon("Emby", "https://example.com/default.gif")
        assertEquals(icons[2], matchServerIcon(icons + generic, "Emby", "https://nebula.example"))
        assertEquals(generic, matchServerIcon(icons + generic, "Emby", "https://192.168.1.12"))
    }
}
