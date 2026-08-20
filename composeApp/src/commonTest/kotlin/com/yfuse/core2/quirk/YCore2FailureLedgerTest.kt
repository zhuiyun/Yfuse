package com.yfuse.core2.quirk

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YCore2FailureLedgerTest {
    private var now = 1_000L
    private val store = InMemoryYCore2FailureStore()
    private val ledger =
        YCore2FailureLedger(
            store = store,
            nowEpochMs = { now },
            cooldownMs = 10_000L,
        )

    @Test
    fun `three decoder failures block only the exact Core2 route`() {
        val p8 = key(profile = 8)
        val p7 = key(profile = 7)

        ledger.recordFailure(p8, YPlaybackFailureCategory.Decoder)
        assertFalse(ledger.isBlocked(p8))
        ledger.recordFailure(p8, YPlaybackFailureCategory.Decoder)
        assertFalse(ledger.isBlocked(p8))
        ledger.recordFailure(p8, YPlaybackFailureCategory.Decoder)

        assertTrue(ledger.isBlocked(p8))
        assertFalse(ledger.isBlocked(p7))
    }

    @Test
    fun `network authorization drm and unknown never poison route memory`() {
        val key = key(profile = 8)
        assertNull(ledger.recordFailure(key, YPlaybackFailureCategory.Network))
        assertNull(ledger.recordFailure(key, YPlaybackFailureCategory.Authorization))
        assertNull(ledger.recordFailure(key, YPlaybackFailureCategory.Drm))
        assertNull(ledger.recordFailure(key, YPlaybackFailureCategory.Unknown))
        assertFalse(ledger.isBlocked(key))
    }

    @Test
    fun `repeated deterministic failure extends cooldown and increments count`() {
        val key = key(profile = 8)
        val first = requireNotNull(ledger.recordFailure(key, YPlaybackFailureCategory.Renderer))
        now += 5_000L
        val second = requireNotNull(ledger.recordFailure(key, YPlaybackFailureCategory.Renderer))

        assertEquals(1, first.failureCount)
        assertEquals(2, second.failureCount)
        assertTrue(second.blockedUntilEpochMs > first.blockedUntilEpochMs)
        assertFalse(ledger.isBlocked(key))
    }

    @Test
    fun `deterministic categories contribute to one exact route threshold`() {
        val key = key(profile = 8)

        ledger.recordFailure(key, YPlaybackFailureCategory.Container)
        ledger.recordFailure(key, YPlaybackFailureCategory.Decoder)
        assertTrue(ledger.activeFailures(key).isEmpty())
        ledger.recordFailure(key, YPlaybackFailureCategory.AudioSink)

        assertTrue(ledger.isBlocked(key))
        assertEquals(3, ledger.activeFailures(key).sumOf { it.failureCount })
    }

    @Test
    fun `expired records are pruned`() {
        val key = key(profile = 8)
        ledger.recordFailure(key, YPlaybackFailureCategory.Container)
        now += 10_001L

        assertFalse(ledger.isBlocked(key))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `failure after observation window starts a new count without requiring a read`() {
        val key = key(profile = 8)
        ledger.recordFailure(key, YPlaybackFailureCategory.Decoder)
        now += 10_001L

        val restarted = requireNotNull(ledger.recordFailure(key, YPlaybackFailureCategory.Decoder))

        assertEquals(1, restarted.failureCount)
        assertFalse(ledger.isBlocked(key))
    }

    @Test
    fun `verified success clears stale failures for the exact route`() {
        val successful = key(profile = 8)
        val unrelated = key(profile = 7)
        ledger.recordFailure(successful, YPlaybackFailureCategory.Decoder)
        ledger.recordFailure(successful, YPlaybackFailureCategory.Decoder)
        ledger.recordFailure(unrelated, YPlaybackFailureCategory.Decoder)

        ledger.recordSuccess(successful)

        assertTrue(ledger.activeFailures(successful).isEmpty())
        assertEquals(1, store.load().single { it.key == unrelated }.failureCount)
    }

    private fun key(profile: Int): YCore2FailureKey =
        YCore2FailureKey(
            route = YPlaybackRoute.NativeEnhanced,
            container = YContainer.Matroska,
            videoCodec = YVideoCodec.H265,
            hdrType = YHdrType.DolbyVision,
            dolbyVisionProfile = profile,
            decoderName = "c2.vendor.dv.decoder",
        )
}
