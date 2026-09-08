package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidHttpRangeHeadersTest {
    @Test
    fun `partial response body length is never mistaken for unknown resource length`() {
        assertNull(mediaResponseContentLength(206, "bytes 0-131071/*", 131072L))
        assertNull(mediaResponseContentLength(206, null, 131072L))
        assertEquals(8_000_000L, mediaResponseContentLength(206, "bytes 0-131071/8000000", 131072L))
        assertEquals(8_000_000L, mediaResponseContentLength(416, "bytes */8000000", 32L))
        assertEquals(8_000_000L, mediaResponseContentLength(200, null, 8_000_000L))
    }
}
