package com.yfuse.core2.android

/** Connected devices outside the AudioTrack's actual route do not participate in this identity. */
internal fun activeAudioOutputFingerprint(
    routed: String,
    connectedDevices: List<String>,
    spatial: String,
): String {
    if (routed.isBlank()) return ""
    val id = routed.substringBefore(':')
    val current = connectedDevices.firstOrNull { it.substringBefore(':') == id }
    return "${current ?: "removed:$routed"}|$spatial"
}
