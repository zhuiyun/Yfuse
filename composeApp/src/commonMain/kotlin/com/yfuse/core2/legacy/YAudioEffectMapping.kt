package com.yfuse.core2.legacy

import com.yfuse.core2.api.YAudioEffect
import com.yfuse.feature.player.AudioEnhancementMode

/** The player's 音频增强 choice as YCore's effect. */
internal fun AudioEnhancementMode.toYAudioEffect(): YAudioEffect =
    when (this) {
        AudioEnhancementMode.Off -> YAudioEffect.Off
        AudioEnhancementMode.VolumeBoost -> YAudioEffect.VolumeBoost
        AudioEnhancementMode.LoudnessNormalize -> YAudioEffect.LoudnessNormalize
        AudioEnhancementMode.NightVoice -> YAudioEffect.NightVoice
    }

/** YCore's effect as the legacy engines' 音频增强 mode. */
internal fun YAudioEffect.toAudioEnhancementMode(): AudioEnhancementMode =
    when (this) {
        YAudioEffect.Off -> AudioEnhancementMode.Off
        YAudioEffect.VolumeBoost -> AudioEnhancementMode.VolumeBoost
        YAudioEffect.LoudnessNormalize -> AudioEnhancementMode.LoudnessNormalize
        YAudioEffect.NightVoice -> AudioEnhancementMode.NightVoice
    }
