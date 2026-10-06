package com.yfuse.core2.api

/**
 * Post-processing of decoded audio: YCore's own 音频增强 modes, matching mpv's for the same name.
 *
 * Effects work on PCM. A route that bitstreams Dolby or DTS to the receiver decodes it to PCM while
 * an effect is on and hands back to passthrough once it is off again.
 */
enum class YAudioEffect {
    Off,

    /** 1.5x gain, mpv's `volume=1.5`, held under full scale by a peak limiter. */
    VolumeBoost,

    /** Rides the gain toward -16 LUFS short-term loudness (mpv: `loudnorm=I=-16`), peaks at -1.5 dBFS. */
    LoudnessNormalize,

    /** mpv's night compressor (-20 dB, 4:1, 5/200 ms, +5 dB makeup) with the centre channel lifted. */
    NightVoice,
}
