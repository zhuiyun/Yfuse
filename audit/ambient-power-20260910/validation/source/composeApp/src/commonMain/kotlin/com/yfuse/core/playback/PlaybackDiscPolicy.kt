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

    /** The server already selected a title and exposed it as a linear media stream. */
    ServerResolvedLinear,

    /** The server must still select/parse the feature, which normally starts an ffmpeg job. */
    ServerMainFeature,

    /** A local ISO/folder can be handed to libbluray/libdvdread through the native engine. */
    NativeLocalImage,

    /** Last-resort native attempt when no server-resolved stream exists. */
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

    /** True when YCore can keep the title's encoded video/audio/subtitle elementary streams. */
    val preservesOriginalStreams: Boolean
        get() =
            strategy == PlaybackDiscStrategy.ServerResolvedLinear ||
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

/**
 * Requires positive Blu-ray evidence before an ISO can enter a libbluray-only route.
 *
 * Container metadata commonly reports both DVD and Blu-ray images as plain `ISO`. A trusted
 * Blu-ray/BDMV kind is sufficient; a generic ISO additionally needs an explicit Blu-ray label until
 * the bounded image inspector has classified its contents.
 */
internal fun isConfirmedBluRaySource(
    kind: PlaybackDiscKind,
    labelHint: String?,
): Boolean {
    val normalizedLabel = labelHint?.trim()?.uppercase().orEmpty()
    val explicitlyBluRay =
        "BLU-RAY" in normalizedLabel ||
            "BLURAY" in normalizedLabel ||
            "BDMV" in normalizedLabel
    return kind == PlaybackDiscKind.BluRay ||
        kind == PlaybackDiscKind.Bdmv ||
        (kind == PlaybackDiscKind.Iso && explicitlyBluRay)
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
        probe.discMainFeatureResolved ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.ServerResolvedLinear,
                reason = "服务器已解析${kind.label}主标题，保留原始音视频流直放",
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
                reason = "本地${kind.label}使用原生 FFmpeg/libbluray 解封装",
            )
        else ->
            PlaybackDiscDecision(
                kind = kind,
                strategy = PlaybackDiscStrategy.NativeRemoteFallback,
                reason = "服务器未提供${kind.label}可播放主标题，尝试原生读取",
            )
    }
}
