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
