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
 *
 * A one-byte HTTP Range probe runs before native registration. If the exact origin cannot prove 206 +
 * stable Content-Range semantics, the item stays on its existing server main-feature/transcode route
 * instead of waiting for libbluray to discover the transport limitation during startup.
 */
internal suspend fun prepareNativeRemoteBluRayRoutes(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    serverRegistry: ServerRegistry?,
): List<PlayerMediaItem> {
    if (serverRegistry == null || !installedMpvNativeBuildCapabilities.remoteRawBluRay) return items
    val item = items.getOrNull(startIndex) ?: return items
    val request = item.nativeRemoteBluRayRequest(serverRegistry) ?: return items
    if (!probeNativeRemoteBluRayRangeSupport(request, serverRegistry)) return items

    val prepared = item.prepareNativeRemoteBluRayRoute(request, serverRegistry)
    if (prepared === item) return items
    return items.toMutableList().also { it[startIndex] = prepared }
}

private fun PlayerMediaItem.nativeRemoteBluRayRequest(
    serverRegistry: ServerRegistry,
): NativeRemoteBluRayPreflightRequest? {
    val version = activeVersion ?: return null
    if (!version.discSource || playMethod == PlaybackMethod.DirectStream) return null
    val serverId = serverId ?: return null
    if (url.startsWith("file://", true) || url.startsWith("content://", true)) return null
    val kind =
        detectPlaybackDiscKind(
            container = version.container,
            labelHint = version.label,
            declaredDiscSource = true,
        )
    if (kind !in setOf(PlaybackDiscKind.Iso, PlaybackDiscKind.BluRay, PlaybackDiscKind.Bdmv)) return null
    if (serverRegistry.serverById(serverId) == null) return null
    return NativeRemoteBluRayPreflightRequest(
        serverId = serverId,
        itemId = id,
        mediaSourceId = version.id,
        playSessionId = version.playSessionId.ifBlank { playSessionId },
    )
}

private fun PlayerMediaItem.prepareNativeRemoteBluRayRoute(
    request: NativeRemoteBluRayPreflightRequest,
    serverRegistry: ServerRegistry,
): PlayerMediaItem {
    val version = activeVersion ?: return this
    val source =
        NativeRemoteBluRayBlockSource(
            serverRegistry = serverRegistry,
            serverId = request.serverId,
            itemId = request.itemId,
            mediaSourceId = request.mediaSourceId,
            playSessionId = request.playSessionId,
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
