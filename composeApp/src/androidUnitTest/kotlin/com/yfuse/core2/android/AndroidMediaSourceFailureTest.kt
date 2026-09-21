package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.network.YTransportFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AndroidMediaSourceFailureTest {
    @Test
    fun wrapped_range_denial_survives_proxy_and_probe_and_cannot_trigger_codec_fallback() {
        for (status in listOf(401, 403)) {
            val raw = YRangeReadException(YTransportFailureKind.Authorization, "denied", status)
            val wrapped = IllegalStateException("extractor failed", raw)
            assertEquals(status, proxyFailureStatus(wrapped).first)
            val typed = wrapped.mediaSourceFailure()
            assertEquals(YPlaybackFailureCategory.Authorization, typed?.category)
            val probe = YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable, typed)
            assertEquals(typed, assertFailsWith<YPlaybackException> { probe.sourceSuccessOrThrow() })
            assertFalse(isRecoverableMediaReadFailure(raw))
        }
    }

    @Test
    fun unknown_format_still_allows_probe_fallback() {
        assertNull(YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec).sourceSuccessOrThrow())
        assertNull(IllegalStateException("unsupported").mediaSourceFailure())
    }

    @Test
    fun status_mapping_is_bounded_and_preserves_missing_sources() {
        assertEquals(404, proxyFailureStatus(YUpstreamHttpException(404)).first)
        assertEquals(410, proxyFailureStatus(YUpstreamHttpException(410)).first)
        assertEquals(503, proxyFailureStatus(YUpstreamHttpException(503)).first)
        assertEquals(502, proxyFailureStatus(IllegalStateException("untrusted text 403")).first)
    }
}
