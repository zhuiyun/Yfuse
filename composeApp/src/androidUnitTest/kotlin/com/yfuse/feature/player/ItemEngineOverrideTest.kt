package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemEngineOverrideTest {
    private fun build() =
        PlayerEngineBuild(PlayerEngine.Exo, DecoderMode.Hardware, PlaybackHandoverSnapshot(0, 0L, true, 1f))

    @Test
    fun leaving_ycore_for_one_entry_comes_back_to_it_for_the_next() {
        val build = build()
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)
        var handovers = 0
        val strategies = mutableListOf<PlaybackEngineSelection>()

        val replaced =
            switchToCompatibilityForItem("e1", leaveCore2 = true, build, choices, { handovers++ }, strategies::add)

        assertEquals(PlayerEngine.Mpv, build.kind)
        assertTrue(build.core2DisabledForSession)
        assertEquals(PlaybackEngineSelection.LockMpv, choices.sessionEngineSelection)

        restoreItemEngine(replaced, build, choices, { handovers++ }, strategies::add)

        assertEquals(PlayerEngine.Exo, build.kind)
        assertFalse(build.core2DisabledForSession)
        assertEquals(PlaybackEngineSelection.Auto, choices.sessionEngineSelection)
        assertEquals(2, build.engineGeneration)
        assertEquals(2, handovers)
        assertTrue(strategies.isEmpty())
    }

    @Test
    fun a_legacy_engine_switches_strategy_and_back() {
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)
        val strategies = mutableListOf<PlaybackEngineSelection>()

        val replaced = switchToCompatibilityForItem("e1", leaveCore2 = false, build(), choices, {}, strategies::add)
        restoreItemEngine(replaced, build(), choices, {}, strategies::add)

        assertEquals(listOf(PlaybackEngineSelection.LockMpv, PlaybackEngineSelection.Auto), strategies)
    }

    @Test
    fun keeping_the_engine_leaves_no_unapplied_value_in_the_panel() {
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)
        choices.subtitleControls = choices.subtitleControls.copy(offsetMs = 1_500L, position = 0.7f)
        choices.audioControls =
            choices.audioControls.copy(delayMs = 200L, enhancement = AudioEnhancementMode.NightVoice)

        UnsupportedPlaybackSetting.SubtitleOffset.reset(choices)
        UnsupportedPlaybackSetting.AudioDelay.reset(choices)
        UnsupportedPlaybackSetting.AudioEnhancement.reset(choices)

        assertEquals(0L, choices.subtitleControls.offsetMs)
        assertEquals(0.7f, choices.subtitleControls.position)
        assertEquals(0L, choices.audioControls.delayMs)
        assertEquals(AudioEnhancementMode.Off, choices.audioControls.enhancement)
    }
}
