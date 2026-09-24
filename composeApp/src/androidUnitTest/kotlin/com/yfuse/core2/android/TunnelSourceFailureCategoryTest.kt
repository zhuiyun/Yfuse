package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.network.YTransportFailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelSourceFailureCategoryTest {
    @Test
    fun an_extractor_rejection_over_http_is_a_container_failure() {
        val rejected = IllegalStateException("Failed to read sample")
        assertEquals(YPlaybackFailureCategory.Container, tunnelSourceFailureCategory(remote = true, rejected))
        assertEquals(YPlaybackFailureCategory.Container, tunnelSourceFailureCategory(remote = false, rejected))
        assertEquals(
            YPlaybackFailureCategory.Container,
            tunnelSourceFailureCategory(remote = true, IllegalArgumentException("sample exceeds buffer")),
        )
    }

    @Test
    fun transport_evidence_on_a_remote_source_stays_network_and_is_recoverable_when_transient() {
        val stalled = YRangeReadException(YTransportFailureKind.TransientIo, "range stalled")
        assertEquals(YPlaybackFailureCategory.Network, tunnelSourceFailureCategory(remote = true, stalled))
        assertTrue(isRecoverableMediaReadFailure(stalled))

        val timeout = IllegalStateException("extractor", SocketTimeoutException("private host"))
        assertEquals(YPlaybackFailureCategory.Network, tunnelSourceFailureCategory(remote = true, timeout))

        // An untyped read failure the data source recorded is rethrown as a plain IOException.
        val reset = IOException("unexpected end of stream")
        assertEquals(YPlaybackFailureCategory.Network, tunnelSourceFailureCategory(remote = true, reset))
        assertTrue(isRecoverableMediaReadFailure(reset))
    }

    @Test
    fun a_missing_or_denied_remote_source_is_not_a_transient_read() {
        val missing = YUpstreamHttpException(404)
        assertEquals(YPlaybackFailureCategory.Network, tunnelSourceFailureCategory(remote = true, missing))
        assertFalse(isRecoverableMediaReadFailure(missing))

        val denied = YRangeReadException(YTransportFailureKind.Authorization, "denied", statusCode = 403)
        assertEquals(YPlaybackFailureCategory.Authorization, tunnelSourceFailureCategory(remote = true, denied))
    }
}
