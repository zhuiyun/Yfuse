package com.yfuse.core.playback

/** Manifest-backed streams can change video tracks without rebuilding the playback engine. */
fun String.isAdaptivePlaybackManifest(): Boolean {
    val path = substringBefore('?').substringBefore('#').trim().lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mpd")
}
