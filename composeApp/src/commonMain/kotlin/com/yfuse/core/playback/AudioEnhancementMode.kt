package com.yfuse.core.playback

/** Engine-neutral audio post-processing. Unsupported backends must report false, not imitate it. */
enum class AudioEnhancementMode(
    val label: String,
) {
    Off("关闭"),
    VolumeBoost("音量增强"),
    LoudnessNormalize("响度均衡"),
    NightVoice("夜间人声"),
}
