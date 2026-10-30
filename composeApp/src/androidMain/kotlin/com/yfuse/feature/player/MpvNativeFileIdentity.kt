package com.yfuse.feature.player

import dev.jdtech.mpv.MPVLib
import java.net.URI

internal data class MpvNativeFileIdentity(
    val entryId: Long?,
    val path: String?,
    val playlistPath: String? = null,
)

internal fun MPVLib?.currentMpvFileIdentity(): MpvNativeFileIdentity? =
    runCatching {
        val player = this ?: return@runCatching null
        val index = player.getPropertyInt("playlist-playing-pos")?.takeIf { it >= 0 }
        MpvNativeFileIdentity(
            index?.let { player.getPropertyString("playlist/$it/id")?.toLongOrNull() },
            player.getPropertyString("path"),
            player.getPropertyString("playlist-path"),
        )
    }.getOrNull()

internal fun mpvMediaPathsMatch(
    expected: String?,
    actual: String?,
): Boolean {
    if (expected == actual) return expected != null

    fun local(value: String?): String? =
        when {
            value?.startsWith("file:", true) == true -> runCatching { URI(value).normalize().path }.getOrNull()
            value?.startsWith('/') == true -> value
            else -> null
        }
    val path = local(expected) ?: return false
    return path == local(actual)
}

internal fun MPVLib?.nextMpvFileIdentity(): MpvNativeFileIdentity? =
    runCatching {
        val player = this ?: return@runCatching null
        MpvNativeFileIdentity(
            player.getPropertyString("playlist/0/id")?.toLongOrNull(),
            player.getPropertyString("playlist/0/filename"),
            player.getPropertyString("playlist/0/playlist-path"),
        )
    }.getOrNull()
