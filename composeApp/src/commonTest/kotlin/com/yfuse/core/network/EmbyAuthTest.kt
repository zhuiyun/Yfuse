package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertTrue

class EmbyAuthTest {

    @Test
    fun header_contains_required_fields() {
        val h = buildAuthHeader("1.2.3")
        assertTrue(h.startsWith("MediaBrowser "), "prefix: $h")
        assertTrue(h.contains("Client=\"Yfuse\""), h)
        assertTrue(h.contains("DeviceId="), h)
        assertTrue(h.contains("Version=\"1.2.3\""), h)
    }
}
