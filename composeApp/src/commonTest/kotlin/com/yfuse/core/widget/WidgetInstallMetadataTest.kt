package com.yfuse.core.widget

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetInstallMetadataTest {
    @Test
    fun legacy_metadata_still_loads_without_author_assets() {
        val metadata = widgetJson.decodeFromString<WidgetMetadata>(
            """{"id":"demo","title":"Demo","modules":[]}""",
        )
        assertEquals("", metadata.icon)
        assertEquals("", metadata.website)
    }

    @Test
    fun installation_reads_optional_publisher_details_without_allowing_external_schemes() {
        val metadata = parseWidgetMetadata(
            """{"id":"demo","title":"Demo","icon":"https://example.com/icon.png",
                "site":"https://example.com","author":"Author",
                "modules":[{"id":"list","title":"List","functionName":"list"}]}""",
            "fallback",
        )
        assertEquals("https://example.com/icon.png", metadata.icon)
        assertEquals("https://example.com", metadata.website)
        assertEquals("Author", metadata.author)
        listOf("javascript:alert(1)", "file:///private", "intent://open", "not a website").forEach {
            assertEquals("", widgetWebLink(it))
        }
    }
}
