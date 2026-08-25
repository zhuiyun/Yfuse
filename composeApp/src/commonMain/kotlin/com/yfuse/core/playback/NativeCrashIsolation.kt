package com.yfuse.core.playback

/** Native ownership used by crash accounting. Keep these separate even when libraries share FFmpeg. */
enum class NativePlaybackComponent {
    Unknown,
    Mpv,
    Mdk,
    YCoreDemux,
}

/** Classifies a bounded tombstone in descending order of specificity. */
fun classifyNativePlaybackCrash(tombstone: String): NativePlaybackComponent {
    val text = tombstone.lowercase()
    return when {
        "libycore_demux" in text || "libycore-demux" in text || "ycore demux" in text ->
            NativePlaybackComponent.YCoreDemux
        "libyfuse-mdk-jni" in text || "libmdk" in text || "mdkplayer" in text ->
            NativePlaybackComponent.Mdk
        "libmpv" in text || "mpv_render" in text || "mpv_render_context" in text ->
            NativePlaybackComponent.Mpv
        else -> NativePlaybackComponent.Unknown
    }
}

/**
 * Defense-in-depth redaction for any small tombstone excerpt shown to a developer. Production
 * persistence stores only the classification, never this text.
 */
fun redactNativeCrashText(text: String): String =
    text
        .replace(URL_WITH_AUTH_OR_QUERY, "[redacted-url]")
        .replace(CREDENTIAL_ASSIGNMENT, "$1=[redacted]")
        .replace(ANDROID_MEDIA_PATH, "[redacted-media-path]")

private val URL_WITH_AUTH_OR_QUERY =
    Regex("(?i)\\b(?:https?|rtsp|smb|ftp)://[^\\s]+")
private val CREDENTIAL_ASSIGNMENT =
    Regex("(?i)\\b(api[_-]?key|access[_-]?token|token|authorization|password|passwd)=([^\\s&]+)")
private val ANDROID_MEDIA_PATH =
    Regex("(?i)(?:/storage/|/sdcard/|content://)[^\\s]+")
