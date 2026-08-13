package com.yfuse.core.model

/** 解码内核 — the decoder preference shown in 我的 · 播放. */
enum class DecoderMode(
    val label: String,
) {
    Hardware("硬件优先"),
    Software("软件解码(FFmpeg)"),
    Auto("自动"),
}
