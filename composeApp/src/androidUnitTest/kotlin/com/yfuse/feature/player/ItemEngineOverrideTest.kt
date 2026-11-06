package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.AudioEnhancementMode
import com.yfuse.core.playback.PlaybackEngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemEngineOverrideTest {
    private fun build(engine: PlayerEngine = PlayerEngine.SenPlayer) =
        PlayerEngineBuild(engine, DecoderMode.Hardware, PlaybackHandoverSnapshot(0, 0L, true, 1f))

    @Test
    fun explicit_sen_to_ycore_override_is_restored_after_the_current_entry() {
        val build = build()
        val choices = PlayerViewerChoices(PlaybackEngineSelection.LockSenPlayer)
        var handovers = 0
        val replaced = switchToYCoreForItem("e1", build, choices) { handovers++ }
        assertEquals(PlayerEngine.YCore, build.kind)
        assertEquals(PlaybackEngineSelection.LockYCore, choices.sessionEngineSelection)
        restoreItemEngine(replaced, build, choices) { handovers++ }
        assertEquals(PlayerEngine.SenPlayer, build.kind)
        assertEquals(PlaybackEngineSelection.LockSenPlayer, choices.sessionEngineSelection)
        assertEquals(2, build.engineGeneration)
        assertEquals(2, handovers)
    }

    @Test
    fun ycore_never_switches_itself_to_sen_for_an_unsupported_setting() {
        kotlin.test.assertFailsWith<IllegalStateException> {
            switchToYCoreForItem("e1", build(PlayerEngine.YCore), PlayerViewerChoices(PlaybackEngineSelection.Auto)) {}
        }
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
