package com.yfuse.core.data

import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkAwarePlaybackTest {
    @Test
    fun cellularCapBoundsAutoOriginalAndHigherManualChoices() {
        val resolve: (PlaybackQuality) -> PlaybackQuality = { preferred ->
            resolveNetworkAwareQuality(
                preferred = preferred,
                networkType = PlaybackNetworkClass.Metered,
                wifiCap = PlaybackQuality.Original,
                cellularCap = PlaybackQuality.Hd,
                qualityLocked = false,
            )
        }

        assertEquals(PlaybackQuality.Hd, resolve(PlaybackQuality.Auto))
        assertEquals(PlaybackQuality.Hd, resolve(PlaybackQuality.Original))
        assertEquals(PlaybackQuality.Hd, resolve(PlaybackQuality.UltraHd))
        assertEquals(PlaybackQuality.Sd, resolve(PlaybackQuality.Sd))
    }

    @Test
    fun lockedQualityIgnoresNetworkCap() {
        assertEquals(
            PlaybackQuality.UltraHd,
            resolveNetworkAwareQuality(
                preferred = PlaybackQuality.UltraHd,
                networkType = PlaybackNetworkClass.Metered,
                wifiCap = PlaybackQuality.Original,
                cellularCap = PlaybackQuality.Sd,
                qualityLocked = true,
            ),
        )
    }

    @Test
    fun downgradeChainIsBounded() {
        assertEquals(PlaybackQuality.FullHd, lowerPlaybackQuality(PlaybackQuality.Original))
        assertEquals(PlaybackQuality.Hd, lowerPlaybackQuality(PlaybackQuality.FullHd))
        assertEquals(PlaybackQuality.Sd, lowerPlaybackQuality(PlaybackQuality.Hd))
        assertNull(lowerPlaybackQuality(PlaybackQuality.Sd))
    }

    @Test
    fun recovery_chain_never_exceeds_the_session_ceiling() {
        assertEquals(
            PlaybackQuality.Hd,
            raisePlaybackQuality(PlaybackQuality.Sd, PlaybackQuality.FullHd),
        )
        assertEquals(
            PlaybackQuality.FullHd,
            raisePlaybackQuality(PlaybackQuality.Hd, PlaybackQuality.FullHd),
        )
        assertNull(raisePlaybackQuality(PlaybackQuality.FullHd, PlaybackQuality.FullHd))
        assertEquals(
            PlaybackQuality.UltraHd,
            raisePlaybackQuality(PlaybackQuality.FullHd, PlaybackQuality.Original),
        )
        assertEquals(
            PlaybackQuality.Original,
            raisePlaybackQuality(PlaybackQuality.UltraHd, PlaybackQuality.Original),
        )
    }

    @Test
    fun estimatesUseTheSelectedBitrate() {
        assertEquals(3_600_000_000L, estimateStreamingBytes(PlaybackQuality.FullHd, 3_600_000L))
        assertNull(estimateStreamingBytes(PlaybackQuality.Original, 3_600_000L))
        assertTrue(PlaybackQuality.Hd.dataEstimateLabel().contains("1.8 GB/小时"))
    }
}
