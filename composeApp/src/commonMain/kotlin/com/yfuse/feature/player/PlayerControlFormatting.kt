package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.SEN_PLAYER_DISPLAY_NAME

/** Converts a scrubber fraction to a clamped media position. */
internal fun scrubPositionMs(
    fraction: Float,
    durationMs: Long,
): Long {
    val duration = durationMs.coerceAtLeast(0L)
    return (fraction.coerceIn(0f, 1f).toDouble() * duration).toLong().coerceIn(0L, duration)
}

/** Unknown duration is an indeterminate timeline, never a completed one. */
internal fun playbackProgressFraction(
    positionMs: Long,
    durationMs: Long,
): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

/** "片头结束 · 90 秒" / "片头结束 · 未设置". */
internal fun skipBoundaryLabel(
    name: String,
    seconds: Long,
): String = if (seconds > 0L) "$name · $seconds 秒" else "$name · 未设置"

/** "片尾开始 · 距结束 120 秒" / "片尾开始 · 未设置". */
internal fun skipCreditsLabel(seconds: Long): String = if (seconds > 0L) "片尾开始 · 距结束 $seconds 秒" else "片尾开始 · 未设置"

internal fun Long.asBitrate(): String {
    if (this <= 0L) return "等待数据"
    val tenths = this / 100_000L
    return "${tenths / 10}.${tenths % 10} Mbps"
}

internal fun Long.asSeconds(): String {
    val tenths = coerceAtLeast(0L) / 100L
    return "${tenths / 10}.${tenths % 10} 秒"
}

internal fun Float.asFrameRate(): String {
    val tenths = (this * 10f).toInt()
    return "${tenths / 10}.${tenths % 10} fps"
}

internal fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

internal fun Long.asClock(): String {
    val seconds = (this / 1_000L).coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/**
 * A short, evidence-based Dolby Vision grade for the always-visible playback readout.
 *
 * The order matters: FEL/RPU are stronger output claims than a generic native-DV signal. A source
 * badge by itself never authorizes either claim. Only current rendered output evidence may
 * authorize the label; choosing YCore or SenPlayer supplies no such evidence.
 */
internal fun PlaybackState.dolbyVisionReadoutLabel(): String? {
    val evidence = diagnostics.outputEvidence
    val outputRendering = diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
    if (!outputRendering) return null
    val rpuRendered = evidence.dolbyVisionRpuRendered
    val felComposed = evidence.dolbyVisionFelComposed

    return when {
        felComposed -> "P7 FEL 已合成"
        rpuRendered -> "P7 RPU 已处理"
        evidence.dynamicRangeOutputMode == PlaybackDynamicRangeOutputMode.DolbyVisionMediaCodec ->
            "Dolby Vision MediaCodec 原生输出"
        evidence.dynamicRangeOutputMode == PlaybackDynamicRangeOutputMode.Hdr10BaseLayer ->
            "HDR10 基础层输出"
        evidence.dynamicRangeOutputMode == PlaybackDynamicRangeOutputMode.HdrToSdrToneMapped ->
            "HDR→SDR 色调映射"
        else -> null
    }
}

/**
 * The route flag is the runtime source of truth. Diagnostics are descriptive and can briefly carry
 * the server's original PlayMethod while Yfuse has deliberately kept the original file for local
 * Dolby decoding. Never surface "服务器转码" unless the engine is actually on a transcode URL.
 */
internal fun PlaybackState.playbackMethodReadoutLabel(): String? {
    val diagnostic = diagnostics.playMethod.takeIf { it.isNotBlank() }
    return when {
        transcoding -> PlaybackMethod.Transcode.label
        diagnostic == PlaybackMethod.Transcode.label -> PlaybackMethod.DirectPlay.label
        diagnostic == "NativeTunnel" || diagnostic == "NativeDirect" || diagnostic == "NativeEnhanced" -> "硬件解码"
        diagnostic == "GpuEnhanced" -> "增强渲染"
        diagnostic == "SoftwareFallback" -> "软件解码"
        diagnostic == "Unknown" -> null
        else -> diagnostic
    }
}

/** Short context under the title. Per-stream diagnostics live in 媒体信息. */
internal fun PlaybackState.readoutLine(sourceLabel: String?): String =
    listOfNotNull(
        resolutionLabel(videoHeight),
        diagnostics.engine.takeIf { it.isNotBlank() }?.let(::engineShortLabel),
        PlaybackMethod.Transcode.label.takeIf { transcoding },
        sourceLabel?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

/**
 * The readout uses a short engine label.
 *
 * Unmatched names pass through as their first word rather than being dropped — a future
 * engine should show up here without anyone remembering to add it to a list.
 */
private fun engineShortLabel(engine: String): String =
    when {
        engine.contains("ycore", ignoreCase = true) -> "YCORE"
        engine.substringBefore('（').substringBefore(' ').equals(SEN_PLAYER_DISPLAY_NAME, ignoreCase = true) ||
            engine.contains(PlayerEngine.SenPlayer.name, ignoreCase = true) -> SEN_PLAYER_DISPLAY_NAME
        else -> engine.substringBefore(' ').uppercase()
    }

internal fun codecReadoutLabel(codec: String): String =
    when (codec.lowercase()) {
        "video/avc", "avc", "h264" -> "H.264"
        "video/hevc", "hevc", "h265" -> "HEVC"
        "video/av01", "av1" -> "AV1"
        "video/x-vnd.on2.vp8", "vp8" -> "VP8"
        "video/x-vnd.on2.vp9", "vp9" -> "VP9"
        else -> codec.uppercase()
    }

private fun resolutionLabel(height: Int): String? =
    when {
        height <= 0 -> null
        height >= 2000 -> "4K"
        height >= 1400 -> "2K"
        height >= 1000 -> "1080P"
        height >= 700 -> "720P"
        else -> "${height}P"
    }

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}
