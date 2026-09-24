package com.yfuse.core2.android

import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.SourcePreheatMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentItemNetworkPolicyTest {
    @Test
    fun `wifi and mobile prepares on both networks`() {
        val mode = SourcePreheatMode.WifiAndMobile
        assertTrue(currentItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, dataSaverEnabled = false, mode))
        assertTrue(currentItemNetworkClassAllowed(PlaybackNetworkClass.Metered, dataSaverEnabled = false, mode))
    }

    @Test
    fun `wifi only never spends mobile data`() {
        val mode = SourcePreheatMode.WifiOnly
        assertTrue(currentItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, dataSaverEnabled = false, mode))
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Metered, dataSaverEnabled = false, mode))
    }

    @Test
    fun `off prepares nowhere`() {
        val mode = SourcePreheatMode.Off
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, dataSaverEnabled = false, mode))
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Metered, dataSaverEnabled = false, mode))
    }

    @Test
    fun `data saver stops speculative reads on every network`() {
        val mode = SourcePreheatMode.WifiAndMobile
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Metered, dataSaverEnabled = true, mode))
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Unmetered, dataSaverEnabled = true, mode))
    }

    @Test
    fun `no preparation without a known connected network`() {
        val mode = SourcePreheatMode.WifiAndMobile
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Offline, dataSaverEnabled = false, mode))
        assertFalse(currentItemNetworkClassAllowed(PlaybackNetworkClass.Unknown, dataSaverEnabled = false, mode))
    }
}
