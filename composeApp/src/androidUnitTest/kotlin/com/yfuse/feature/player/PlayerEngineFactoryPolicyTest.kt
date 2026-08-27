package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackEngineSelection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerEngineFactoryPolicyTest {
    @Test
    fun core2_trial_is_only_available_in_automatic_engine_mode() {
        assertTrue(
            shouldUseCore2Trial(
                enabled = true,
                engineSelection = PlaybackEngineSelection.Auto,
                crashBlocked = false,
            ),
        )
        PlaybackEngineSelection.entries
            .filterNot { it == PlaybackEngineSelection.Auto }
            .forEach { locked ->
                assertFalse(
                    shouldUseCore2Trial(
                        enabled = true,
                        engineSelection = locked,
                        crashBlocked = false,
                    ),
                    locked.name,
                )
            }
    }

    @Test
    fun disabled_or_crash_blocked_trial_stays_on_the_selected_legacy_engine() {
        assertFalse(
            shouldUseCore2Trial(
                enabled = false,
                engineSelection = PlaybackEngineSelection.Auto,
                crashBlocked = false,
            ),
        )
        assertFalse(
            shouldUseCore2Trial(
                enabled = true,
                engineSelection = PlaybackEngineSelection.Auto,
                crashBlocked = true,
            ),
        )
    }
}
