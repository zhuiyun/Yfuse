package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind

internal data class MpvScaleModeProperties(
    val panscan: Double,
    val keepAspect: Boolean,
)

internal fun mpvScaleModeProperties(mode: VideoScaleMode): MpvScaleModeProperties =
    MpvScaleModeProperties(
        panscan = if (mode == VideoScaleMode.Fill) 1.0 else 0.0,
        keepAspect = mode != VideoScaleMode.Stretch,
    )

internal fun externalSubtitleMpvCommand(item: PlayerMediaItem): Array<String>? =
    externalSubtitleMpvCommands(item).firstOrNull()

internal fun externalSubtitleMpvCommands(item: PlayerMediaItem): List<Array<String>> =
    item.playbackExternalSubtitles().map { subtitle ->
        val language = subtitle.language?.takeIf(String::isNotBlank)
        val selection = if (subtitle.default) "select" else "auto"
        val title =
            if (subtitle.uri == item.externalSubtitleUri) {
                "外挂字幕"
            } else {
                subtitle.label
            }
        if (language == null) {
            arrayOf("sub-add", subtitle.uri, selection, title)
        } else {
            arrayOf("sub-add", subtitle.uri, selection, title, language)
        }
    }

internal fun mpvAudioOutputReadiness(
    outputDriver: String?,
    outputFormat: String?,
    clockAdvancing: Boolean,
): PlaybackOutputReadiness {
    val driver = outputDriver?.trim().orEmpty()
    val format = outputFormat?.trim().orEmpty()
    return if (
        clockAdvancing &&
        driver.isNotEmpty() &&
        !driver.equals("null", ignoreCase = true) &&
        format.isNotEmpty()
    ) {
        PlaybackOutputReadiness.Rendering
    } else {
        PlaybackOutputReadiness.Waiting
    }
}

internal fun mpvDecoderDiagnostic(hwdecCurrent: String?): String =
    hwdecCurrent
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("no", ignoreCase = true) }
        ?.let { "硬件解码 · $it" }
        ?: "FFmpeg 软件解码"

internal fun mpvDolbyVisionVideoFilter(stripToBaseLayer: Boolean): String =
    if (stripToBaseLayer) {
        "format=dolbyvision=no:enhancement-layer=no"
    } else {
        ""
    }

/**
 * Native optical-disc URLs are explicit so Blu-ray always starts on the main feature instead of
 * relying on whichever playlist libbluray happens to expose first. mpv documents `bd://longest`
 * as the longest Blu-ray playlist and uses the same `edition` property for later title changes.
 */
internal fun mpvDiscPlaybackUrl(kind: PlaybackDiscKind): String? =
    when (kind) {
        PlaybackDiscKind.Dvd -> "dvd://"
        PlaybackDiscKind.BluRay,
        PlaybackDiscKind.Bdmv,
        -> "bd://longest"
        else -> null
    }
