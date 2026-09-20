package com.yfuse.feature.player

import kotlin.math.roundToInt

/** Keep absent/stale output evidence separate from both a measured zero and source metadata. */
internal fun PlaybackState.outputFrameRateLabel(nowElapsedMs: Long): String {
    val status =
        when {
            error != null -> "播放错误"
            ended -> "已结束"
            buffering -> "缓冲中"
            !playing -> "已暂停"
            else -> null
        }
    if (status != null) return "实时输出 — FPS · $status"
    val sample = diagnostics.renderedFrameRate
    val fresh =
        sample != null &&
            sample.sampledAtElapsedMs <= nowElapsedMs &&
            nowElapsedMs - sample.sampledAtElapsedMs <= 3_000L
    val rate = sample?.framesPerSecond?.takeIf { fresh && it.isFinite() && it >= 0f }
    return "实时输出 ${rate?.fpsNumber() ?: "—"} FPS" + if (rate == null) " · 暂无数据" else ""
}

internal fun PlaybackState.sourceFrameRateLabel(): String {
    val rate = diagnostics.frameRate.takeIf { it.isFinite() && it > 0f }
    return "片源 ${rate?.fpsNumber() ?: "—"} FPS"
}

private fun Float.fpsNumber(): String {
    val hundredths = (toDouble() * 100.0).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
