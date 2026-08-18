package com.yfuse.core.cast

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CastResponseBoundaryTest {
    @Test
    fun bounded_reader_accepts_the_limit_and_rejects_the_next_byte() {
        assertEquals(
            "1234",
            readCastResponseBounded(ByteArrayInputStream("1234".encodeToByteArray()), 4),
        )
        assertFailsWith<IOException> {
            readCastResponseBounded(ByteArrayInputStream("12345".encodeToByteArray()), 4)
        }
    }
}
