package com.yfuse.core.playback

enum class PlaybackDiscKind(
    val label: String,
) {
    None("普通文件"),
    Iso("ISO 镜像"),
    Dvd("DVD"),
    BluRay("Blu-ray"),
    Bdmv("BDMV 目录"),
    Unknown("光盘源"),
}

enum class PlaybackDiscStrategy {
    NotRequired,
    ServerMainFeature,
    NativeLocalImage,
    NativeRemoteFallback,
}

data class PlaybackDiscDecision(
    val kind: PlaybackDiscKind,
    val strategy: PlaybackDiscStrategy,
    val reason: String? = null,
) {
    val requiresServerTranscode: Boolean
        get() = strategy == PlaybackDiscStrategy.ServerMainFeature

    val requiresNativeEngine: Boolean
        get() =
            strategy == PlaybackDiscStrategy.NativeLocalImage ||
                strategy == PlaybackDiscStrategy.NativeRemoteFallback
}

/** Pure classifier shared by queue preparation, planning and unit tests. */
fun detectPlaybackDiscKind(
    container: String?,
    labelHint: String? = null,
    declaredDiscSource: Boolean = false,
): PlaybackDiscKind {
    val normalizedContainer = container?.trim()?.uppercase().orEmpty()
    val normalizedLabel = labelHint?.trim()?.uppercase().orEmpty()
    return when {
        normalizedContainer == "ISO" || normalizedLabel.endsWith(".ISO") -> PlaybackDiscKind.Iso
        normalizedContainer == "BDMV" || "BDMV" in normalizedLabel -> PlaybackDiscKind.Bdmv
        normalizedContainer in setOf("BLURAY", "BLU-RAY", "BD") ||
            "BLU-RAY" in normalizedLabel ||
            "BLURAY" in normalizedLabel ->
            PlaybackDiscKind.BluRay
        normalizedContainer == "DVD" || "DVD" in normalizedLabel -> PlaybackDiscKind.Dvd
        declaredDiscSource -> PlaybackDiscKind.Unknown
        else -> PlaybackDiscKind.None
    }
}

fun planDiscPlayback(probe: PlaybackMediaProbe): PlaybackDiscDecision {
    if (!probe.discSource && probe.discKind == PlaybackDiscKind.None) {
        return PlaybackDiscDecision(PlaybackDiscKind.None, PlaybackDiscStrategy.NotRequired)
    }
    val kind = probe.discKind.takeUnless { it == PlaybackDiscKind.None } ?: PlaybackDiscKind.Unknown
    return when {
        probe.usingServerTranscode ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.ServerMainFeature,
                reason = "服务器正在解析${kind.label}主标题",
            )
        probe.hasServerTranscode ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.ServerMainFeature,
                reason = "${kind.label}由服务器解析主标题后交给平台硬解",
            )
        probe.localSource ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.NativeLocalImage,
                reason = "本地${kind.label}使用原生 FFmpeg 解封装",
            )
        else ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.NativeRemoteFallback,
                reason = "服务器未提供${kind.label}转码地址，尝试原生读取",
            )
    }
}

