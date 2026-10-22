package com.yfuse.core.widget

import com.yfuse.feature.player.PlayerCustomHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WidgetPlaybackHeadersTest {
    @Test
    fun playback_headers_are_copied_and_redacted_in_diagnostics() {
        val original = mutableMapOf("Authorization" to "Bearer widget-secret", "Referer" to "https://video.example/")
        val headers = PlayerCustomHeaders(original)
        original.clear()
        assertEquals(2, headers.values.size)
        assertFalse(headers.toString().contains("widget-secret"))
    }

    @Test
    fun invalid_transport_and_injected_headers_are_rejected() {
        assertFailsWith<IllegalArgumentException> { PlayerCustomHeaders(mapOf("Host" to "other.example")) }
        assertFailsWith<IllegalArgumentException> {
            PlayerCustomHeaders(
                mapOf("X-Test" to "one\r\nAuthorization: stolen"),
            )
        }
        assertFailsWith<IllegalArgumentException> { PlayerCustomHeaders(mapOf("Bad Name" to "value")) }
    }
}
