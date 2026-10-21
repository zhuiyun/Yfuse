package com.yfuse.feature.player

internal data class MpvSubtitleText(
    val primary: String = "",
    val secondary: String = "",
    val stacked: Boolean = false,
)

/** mpv exposes text, but not the decoded bitmap subtitle planes. Keep those native. */
internal fun mpvCanStackSubtitles(
    tracks: List<EngineTrack>,
    primary: String?,
    secondary: String?,
): Boolean {
    if (primary == null || secondary == null || primary == secondary) return false
    return listOf(primary, secondary).all { id ->
        tracks.firstOrNull { it.id == id }?.codec?.lowercase() in MPV_TEXT_SUBTITLE_CODECS
    }
}

private val MPV_TEXT_SUBTITLE_CODECS =
    setOf("ass", "ssa", "srt", "subrip", "text", "mov_text", "webvtt", "ttml", "microdvd")

internal fun mpvShouldOverlaySubtitles(
    tracks: List<EngineTrack>,
    primary: String?,
    secondary: String?,
    preferBlackBars: Boolean,
): Boolean {
    if (mpvCanStackSubtitles(tracks, primary, secondary)) return true
    if (!preferBlackBars || primary == null || secondary != null) return false
    // Keep ASS effects, positioned WebVTT/TTML and image captions in their native renderer.
    return tracks.firstOrNull { it.id == primary }?.codec?.lowercase() in
        setOf("srt", "subrip", "text", "mov_text", "microdvd")
}
