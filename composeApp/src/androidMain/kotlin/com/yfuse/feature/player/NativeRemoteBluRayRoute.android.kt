package com.yfuse.feature.player

import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind

/**
 * Replaces only the active raw remote Blu-ray image with an opaque process-local native route.
 *
 * Registering every queued disc would keep JNI global references for titles that may never be opened.
 * Optical-disc sessions are effectively single-title queues, so preparation is intentionally scoped to
 * [startIndex]. The original HLS/progressive URLs remain on the item as the recovery chain.
 */
internal fun prepareNativeRemoteBluRayRoutes(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    serverRegistry: ServerRegistry?,
): List<PlayerMediaItem> {
    if (serverRegistry == null || !installedMpvNativeBuildCapabilities.remoteRawBluRay) return items
    val item = items.getOrNull(startIndex) ?: return items
    val prepared = item.prepareNativeRemoteBluRayRoute(serverRegistry)
    if (prepared === item) return items
    return items.toMutableList().also { it[startIndex] = prepared }
}

private fun PlayerMediaItem.prepareNativeRemoteBluRayRoute(serverRegistry: ServerRegistry): PlayerMediaItem {
    val version = activeVersion ?: return this
    if (!version.discSource || playMethod == PlaybackMethod.DirectStream) return this
    val serverId = serverId ?: return this
    if (url.startsWith("file://", true) || url.startsWith("content://", true)) return this
    val kind =
        detectPlaybackDiscKind(
            container = version.container,
            labelHint = version.label,
            declaredDiscSource = true,
        )
    if (kind !in setOf(PlaybackDiscKind.Iso, PlaybackDiscKind.BluRay, PlaybackDiscKind.Bdmv)) return this
    if (serverRegistry.serverById(serverId) == null) return this

    val source =
        NativeRemoteBluRayBlockSource(
            serverRegistry = serverRegistry,
            serverId = serverId,
            itemId = id,
            mediaSourceId = version.id,
            playSessionId = version.playSessionId.ifBlank { playSessionId },
        )
    val nativeId = NativeRemoteBluRayRegistry.register(source)
    if (nativeId == null) {
        source.closeNativeSource()
        return this
    }
    val nativeUrl = "$YFUSE_REMOTE_BLURAY_PREFIX$nativeId"
    val nativeVersion =
        version.copy(
            url = nativeUrl,
            playMethod = PlaybackMethod.DirectPlay,
        )
    return copy(
        url = nativeUrl,
        playMethod = PlaybackMethod.DirectPlay,
        forcedTranscodeReason = null,
        versions = versions.map { candidate -> if (candidate.id == version.id) nativeVersion else candidate },
    )
}
