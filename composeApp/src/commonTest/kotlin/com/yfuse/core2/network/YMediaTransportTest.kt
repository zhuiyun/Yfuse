package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YMediaTransportTest {
    @Test
    fun `diagnostics omit query fragments and authorization headers`() {
        val request =
            YMediaTransportRequest(
                uri = "https://media.example/video.mkv?api_key=secret#fragment",
                protocol = YSourceProtocol.Https,
                range = YByteRange(128L, 255L),
                headers = mapOf("Authorization" to "Bearer secret"),
            )

        val summary = request.diagnosticSummary()

        assertEquals("Https https://media.example/video.mkv bytes=128-255", summary)
        assertFalse("secret" in summary)
    }

    @Test
    fun `cache identity never depends on signed source URI`() {
        assertEquals("account-a\titem-42\tv3", YCacheIdentity("account-a", "item-42", "v3").key())
    }

    @Test
    fun `remote seekable remux gets bounded bitrate aware cache`() {
        val plan =
            YCachePlanner.plan(
                YCacheConditions(
                    remote = true,
                    live = false,
                    seekable = true,
                    mediaBitRateBitsPerSecond = 160_000_000L,
                    availableBytes = 512L * 1024L * 1024L,
                ),
            )

        assertTrue(plan.enabled)
        assertEquals(16L * 1024L * 1024L, plan.readAheadBytes)
        assertEquals(512L * 1024L * 1024L, plan.maximumBytes)
    }

    @Test
    fun `live and local sources bypass persistent cache`() {
        val local =
            YCachePlanner.plan(
                YCacheConditions(false, live = false, seekable = true, availableBytes = 1_000L),
            )
        val live =
            YCachePlanner.plan(
                YCacheConditions(true, live = true, seekable = true, availableBytes = 1_000L),
            )

        assertFalse(local.enabled)
        assertFalse(live.enabled)
    }
}
