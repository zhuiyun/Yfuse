package com.yfuse.core2.legacy

import com.yfuse.core2.api.YAudioEffect
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.AudioEnhancementMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YAudioEffectMappingTest {
    @Test
    fun every_enhancement_mode_has_its_ycore_effect_and_back() {
        assertEquals(AudioEnhancementMode.entries.size, YAudioEffect.entries.size)
        AudioEnhancementMode.entries.forEach { mode ->
            assertEquals(mode, mode.toYAudioEffect().toAudioEnhancementMode())
        }
    }

    @Test
    fun ycore_applies_the_mode_itself_instead_of_asking_for_mpv() {
        val player = EffectPlayer(supportsEffects = true)
        val engine = YPlayerVideoEngineAdapter(player)

        assertTrue(engine.supportsAudioEnhancement)
        assertTrue(engine.setAudioEnhancement(AudioEnhancementMode.NightVoice))
        assertEquals(YAudioEffect.NightVoice, player.effect)
    }

    @Test
    fun a_player_without_effects_only_accepts_off() {
        val player = EffectPlayer(supportsEffects = false)
        val engine = YPlayerVideoEngineAdapter(player)

        assertFalse(engine.supportsAudioEnhancement)
        assertFalse(engine.setAudioEnhancement(AudioEnhancementMode.VolumeBoost))
        assertTrue(engine.setAudioEnhancement(AudioEnhancementMode.Off))
        assertNull(player.effect)
    }

    private class EffectPlayer(
        private val supportsEffects: Boolean,
    ) : YPlayer {
        override val state = MutableStateFlow(YPlayerState())
        var effect: YAudioEffect? = null

        override val supportsAudioEffects: Boolean get() = supportsEffects

        override fun setAudioEffect(effect: YAudioEffect): Boolean {
            if (!supportsEffects) return super.setAudioEffect(effect)
            this.effect = effect
            return true
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) = Unit

        override fun selectItem(index: Int) = Unit

        override fun retry() = Unit

        override fun release() = Unit
    }
}
