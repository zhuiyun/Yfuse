package com.yfuse.core.sync.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCloudSyncPolicyTest {
    @Test
    fun retry_backoff_grows_exponentially_and_stops_at_fifteen_minutes() {
        assertEquals(30_000L, playbackCloudRetryBackoffMs(1))
        assertEquals(60_000L, playbackCloudRetryBackoffMs(2))
        assertEquals(120_000L, playbackCloudRetryBackoffMs(3))
        assertEquals(15 * 60_000L, playbackCloudRetryBackoffMs(6))
        assertEquals(15 * 60_000L, playbackCloudRetryBackoffMs(100))
    }
}
