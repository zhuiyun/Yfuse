package com.yfuse.feature.player

import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.data.SourcePreheatMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPreloaderPolicyTest {
    @Test
    fun senplayer_never_starts_the_ycore_source_preparation_worker() {
        assertFalse(
            shouldPrepareYCoreSource(
                PlayerEngine.SenPlayer,
                PlaybackNetworkClass.Unmetered,
                SourcePreheatMode.WifiAndMobile,
                dataSaver = false,
                powerSaveMode = false,
            ),
        )
    }

    @Test
    fun ycore_preparation_respects_network_opt_out_and_power_policy() {
        assertTrue(
            shouldPrepareYCoreSource(
                PlayerEngine.YCore,
                PlaybackNetworkClass.Unmetered,
                SourcePreheatMode.WifiAndMobile,
                dataSaver = false,
                powerSaveMode = false,
            ),
        )
        assertFalse(
            shouldPrepareYCoreSource(
                PlayerEngine.YCore,
                PlaybackNetworkClass.Unmetered,
                SourcePreheatMode.Off,
                dataSaver = false,
                powerSaveMode = false,
            ),
        )
        assertFalse(
            shouldPrepareYCoreSource(
                PlayerEngine.YCore,
                PlaybackNetworkClass.Unknown,
                SourcePreheatMode.WifiAndMobile,
                dataSaver = false,
                powerSaveMode = false,
            ),
        )
        assertFalse(
            shouldPrepareYCoreSource(
                PlayerEngine.YCore,
                PlaybackNetworkClass.Unmetered,
                SourcePreheatMode.WifiAndMobile,
                dataSaver = false,
                powerSaveMode = true,
            ),
        )
    }
}
