package com.yfuse.core2.release

enum class Core2NativeBaselineBlock {
    MissingMetadata,
    UnsupportedScheme,
    ServerTranscode,
    AdaptiveManifest,
    UnsupportedContainer,
    UnsupportedVideoCodec,
    Disc,
    Drm,
    DolbyVision,
    ExternalSubtitle,
}

data class Core2NativeBaselineSource(
    val hasMetadata: Boolean,
    val scheme: String,
    val container: String?,
    val videoCodec: String?,
    val serverTranscode: Boolean,
    val adaptiveManifest: Boolean,
    val adaptiveManifestSupported: Boolean = false,
    val disc: Boolean,
    val drm: Boolean,
    val drmSupported: Boolean = false,
    val dolbyVision: Boolean,
    val dolbyVisionSupported: Boolean = false,
    val externalSubtitleSupported: Boolean,
)

/**
 * Fail-closed preflight for the first independently executable YCore lane. Audio is deliberately
 * absent: it is verified from demuxed tracks at runtime because queue metadata only carries a
 * display string and must not be treated as codec evidence.
 */
fun evaluateCore2NativeBaseline(source: Core2NativeBaselineSource): Core2NativeBaselineBlock? =
    when {
        !source.hasMetadata -> Core2NativeBaselineBlock.MissingMetadata
        source.scheme.lowercase() !in CORE2_NATIVE_BASELINE_SCHEMES ->
            Core2NativeBaselineBlock.UnsupportedScheme
        source.serverTranscode -> Core2NativeBaselineBlock.ServerTranscode
        source.adaptiveManifest && !source.adaptiveManifestSupported -> Core2NativeBaselineBlock.AdaptiveManifest
        source.disc -> Core2NativeBaselineBlock.Disc
        source.drm && !source.drmSupported -> Core2NativeBaselineBlock.Drm
        source.dolbyVision && !source.dolbyVisionSupported -> Core2NativeBaselineBlock.DolbyVision
        !source.externalSubtitleSupported -> Core2NativeBaselineBlock.ExternalSubtitle
        !source.adaptiveManifest &&
            source.container.normalizedContainer() !in CORE2_NATIVE_BASELINE_CONTAINERS ->
            Core2NativeBaselineBlock.UnsupportedContainer
        source.videoCodec.normalizedVideoCodec() !in CORE2_NATIVE_BASELINE_VIDEO_CODECS ->
            Core2NativeBaselineBlock.UnsupportedVideoCodec
        else -> null
    }

private fun String?.normalizedContainer(): String =
    orEmpty()
        .trim()
        .lowercase()
        .removePrefix("video/")
        .removePrefix(".")

private fun String?.normalizedVideoCodec(): String {
    val value = orEmpty().trim().lowercase()
    return when {
        value.startsWith("avc1.") -> "avc1"
        value.startsWith("hvc1.") -> "hvc1"
        value.startsWith("hev1.") -> "hev1"
        value.startsWith("av01.") -> "av01"
        value.startsWith("vp09.") -> "vp09"
        value.startsWith("prores") -> "prores"
        else -> value.filter(Char::isLetterOrDigit)
    }
}

private val CORE2_NATIVE_BASELINE_SCHEMES =
    setOf("http", "https", "file", "content", "android.resource")
private val CORE2_NATIVE_BASELINE_CONTAINERS =
    setOf("mp4", "m4v", "mkv", "matroska", "webm", "mov", "quicktime")
private val CORE2_NATIVE_BASELINE_VIDEO_CODECS =
    setOf(
        "h264",
        "avc",
        "avc1",
        "hevc",
        "h265",
        "hvc1",
        "hev1",
        "av1",
        "av01",
        "vp9",
        "vp09",
        "vc1",
        "wmv3",
        "mpeg2",
        "mpeg2video",
        "prores",
    )
