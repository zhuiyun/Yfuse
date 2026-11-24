package com.yfuse.core.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
                val catalog = ServerIconCatalog(http)
                assertFailsWith<IllegalStateException> { catalog.load() }
                val icons = catalog.load()
                assertEquals("影院", icons.single().name)
                assertSame(icons, catalog.load())
                assertEquals(2, requests)
            } finally {
                http.close()
            }
        }
}
