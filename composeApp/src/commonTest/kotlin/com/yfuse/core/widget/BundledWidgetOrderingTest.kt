package com.yfuse.core.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class BundledWidgetOrderingTest {
    @Test fun bundled_pages_are_first_without_reordering_personal_pages_or_changing_the_default() {
        val pages =
            listOf(
                WidgetPage("personal-b", "B"),
                WidgetPage("page-$BUNDLED_RECOMMENDATIONS_ID", "影视推荐"),
                WidgetPage("personal-a", "A"),
                WidgetPage("page-$BUNDLED_TMDB_ID", "TMDB"),
            )
        val db = WidgetDatabase(pages = pages, defaultPageId = "personal-b")
        assertEquals(listOf("TMDB", "影视推荐", "B", "A"), db.pages.withBundledPagesFirst().map { it.title })
        assertEquals(pages, db.pages)
        assertEquals("personal-b", db.defaultPageId)
    }

    @Test fun bundled_widgets_are_first_even_when_disabled_and_custom_order_is_stable() {
        val widgets =
            listOf("custom-b", BUNDLED_RECOMMENDATIONS_ID, "custom-a", BUNDLED_TMDB_ID).map { id ->
                InstalledWidget(
                    metadata = WidgetMetadata(id, id, modules = listOf(WidgetModule("list", "List", "load"))),
                    source = "source",
                    origin = "file:$id",
                    digest = id,
                    enabled = false,
                )
            }
        assertEquals(
            listOf(BUNDLED_TMDB_ID, BUNDLED_RECOMMENDATIONS_ID, "custom-b", "custom-a"),
            widgets.withBundledWidgetsFirst().map { it.metadata.id },
        )
        assertEquals(listOf(false, false, false, false), widgets.withBundledWidgetsFirst().map { it.enabled })
    }
}
