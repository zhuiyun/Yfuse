package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentHandoffTest {
    @Test
    fun refresh_and_background_errors_keep_existing_content_visible() {
        for (loading in listOf(false, true)) {
            for (error in listOf(false, true)) {
                assertEquals(ContentPhase.Content, contentPhase(loading, true, error))
            }
        }
        assertEquals(ContentPhase.Loading, contentPhase(true, false, true))
        assertEquals(ContentPhase.Error, contentPhase(false, false, true))
        assertEquals(ContentPhase.Empty, contentPhase(false, false, false))
    }

    @Test
    fun dock_arrival_requires_an_explicit_click_and_can_only_be_consumed_once() {
        SearchDockOrigin.consume()
        val source = Rect(10f, 100f, 60f, 150f)
        SearchDockOrigin.bounds = source
        assertNull(SearchDockOrigin.consume())
        SearchDockOrigin.begin()
        SearchDockOrigin.bounds = Rect.Zero
        assertEquals(source, SearchDockOrigin.consume())
        assertNull(SearchDockOrigin.consume())
        SearchDockOrigin.bounds = null
    }
}
