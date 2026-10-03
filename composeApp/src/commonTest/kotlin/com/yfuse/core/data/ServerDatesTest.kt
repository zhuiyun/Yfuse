package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerDatesTest {
    @Test
    fun parses_server_dates() {
        assertEquals(1_790_769_600_123L, serverDateEpochMs("2026-09-30T12:00:00.1234567Z"))
        assertEquals(1_790_769_600_000L, serverDateEpochMs("2026-09-30T12:00:00"))
        assertEquals(1_790_769_600_000L, serverDateEpochMs("2026-09-30T20:00:00+08:00"))
        assertNull(serverDateEpochMs("0001-01-01T00:00:00.0000000Z"))
        assertNull(serverDateEpochMs("garbage"))
        assertNull(serverDateEpochMs(""))
    }
}
