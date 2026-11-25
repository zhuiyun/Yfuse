package com.yfuse.feature.player

import com.yfuse.core.playback.AudioEnhancementMode

/** A playback setting the engine on screen cannot honour, named the way the panel names it. */
internal enum class UnsupportedPlaybackSetting(
    val label: String,
) {
    SubtitleOffset("字幕偏移"),
    AudioDelay("音频延迟"),
    AudioEnhancement("音频增强"),
    SubtitleScale("字幕字号"),
    SubtitleBrightness("字幕亮度"),
    SubtitlePosition("字幕位置"),
    SubtitleAppearance("字幕样式"),
}

/** The setting's neutral value: what is left when the viewer keeps the engine instead. */
internal fun UnsupportedPlaybackSetting.reset(choices: PlayerViewerChoices) {
    when (this) {
        UnsupportedPlaybackSetting.SubtitleOffset ->
            choices.subtitleControls = choices.subtitleControls.copy(offsetMs = 0L)
        UnsupportedPlaybackSetting.AudioDelay -> choices.audioControls = choices.audioControls.copy(delayMs = 0L)
        UnsupportedPlaybackSetting.AudioEnhancement ->
            choices.audioControls = choices.audioControls.copy(enhancement = AudioEnhancementMode.Off)
        UnsupportedPlaybackSetting.SubtitleScale -> choices.subtitleControls = choices.subtitleControls.copy(scale = 1f)
        UnsupportedPlaybackSetting.SubtitleBrightness ->
            choices.subtitleControls = choices.subtitleControls.copy(brightness = 1f)
        UnsupportedPlaybackSetting.SubtitlePosition ->
            choices.subtitleControls = choices.subtitleControls.copy(position = DEFAULT_SUBTITLE_POSITION)
        UnsupportedPlaybackSetting.SubtitleAppearance ->
            choices.subtitleControls = choices.subtitleControls.copy(appearance = SubtitleAppearance())
    }
}
