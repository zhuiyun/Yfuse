package com.yfuse.feature.home

import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetSourceContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetDetailRouteTest {
    @Test
    fun restoredComponentDetailRetainsItsSourceHeadersAndExplicitPlaybackChoices() {
        val source =
            WidgetSourceContext(
                "source-widget",
                WidgetMedia(
                    id = "source-film",
                    title = "复仇者联盟4",
                    link = "https://source.example/title/film",
                    headers = mapOf("Referer" to "https://source.example"),
                    children = listOf(WidgetMedia("line-1", "线路一", videoUrl = "https://video.example/film.m3u8")),
                ),
            )
        val route =
            HomeTabComponent.Config.Info(
                widgetDetailTestItem(),
                null,
                preferLibraryDetail = true,
                sourceContext = source,
            )
        val encoded = Json.encodeToString(HomeTabComponent.Config.serializer(), route)
        val restored =
            assertIs<HomeTabComponent.Config.Info>(Json.decodeFromString(HomeTabComponent.Config.serializer(), encoded))
        assertTrue(restored.preferLibraryDetail)
        assertEquals(source, restored.sourceContext)
    }

    @Test
    fun anOlderDiscoveryRouteStillRestoresWithoutChangingItsNavigationBehavior() {
        val route = HomeTabComponent.Config.Info(widgetDetailTestItem(), "library-film")
        val encoded = Json.encodeToString(HomeTabComponent.Config.serializer(), route)
        assertFalse("preferLibraryDetail" in encoded)
        assertFalse("sourceContext" in encoded)
        val restored =
            assertIs<HomeTabComponent.Config.Info>(Json.decodeFromString(HomeTabComponent.Config.serializer(), encoded))
        assertFalse(restored.preferLibraryDetail)
        assertNull(restored.sourceContext)
        assertEquals("library-film", restored.embyItemId)
    }
}
