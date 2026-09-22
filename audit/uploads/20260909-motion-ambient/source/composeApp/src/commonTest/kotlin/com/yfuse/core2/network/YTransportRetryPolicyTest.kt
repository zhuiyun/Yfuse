package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YTransportRetryPolicyTest {
    @Test
    fun `transient range reads receive two bounded retries`() {
        assertEquals(100L, mediaRangeRetryDelayMs(0, YTransportFailureKind.TransientIo))
        assertEquals(300L, mediaRangeRetryDelayMs(1, YTransportFailureKind.PrematureEof))
        assertNull(mediaRangeRetryDelayMs(2, YTransportFailureKind.ServerBusy))
    }

    @Test
    fun `authorization and invalid ranges are never replayed`() {
        assertNull(mediaRangeRetryDelayMs(0, YTransportFailureKind.Authorization))
        assertNull(mediaRangeRetryDelayMs(0, YTransportFailureKind.InvalidRange))
    }
}
