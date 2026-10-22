package com.yfuse.feature.widgets

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetDocumentTest {
    @Test fun accepts_js_files_even_when_the_sender_uses_generic_types() {
        assertTrue(isWidgetDocument("我的组件.JS", "application/octet-stream"))
        assertTrue(isWidgetDocument("component.js", "text/plain"))
        assertTrue(isWidgetDocument(null, "application/javascript"))
    }

    @Test fun generic_mime_types_do_not_make_unrelated_files_installable() {
        assertFalse(isWidgetDocument("notes.txt", "text/plain"))
        assertFalse(isWidgetDocument("movie.mp4", "application/octet-stream"))
        assertFalse(isWidgetDocument("script.js.zip", null))
    }
}
