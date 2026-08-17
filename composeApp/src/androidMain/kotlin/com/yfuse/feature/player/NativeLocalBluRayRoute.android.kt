package com.yfuse.feature.player

import android.content.Context
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.cachedLocalPlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind

/**
 * Gives known local Blu-ray sources the same libbluray session used for HDMV menus.
 *
 * Generic ISO images stay on the old path until the bounded image classifier has proved they are
 * Blu-ray; this avoids sending DVD images into libbluray solely because the extension is `.iso`.
 */
internal fun prepareNativeLocalBluRayRoute(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    context: Context,
): List<PlayerMediaItem> {
    if (!installedMpvNativeBuildCapabilities.remoteRawBluRay) return items
    val item = items.getOrNull(startIndex) ?: return items
    val prepared = item.prepareNativeLocalBluRayRoute(context) ?: return items
    return items.toMutableList().also { it[startIndex] = prepared }
}

private fun PlayerMediaItem.prepareNativeLocalBluRayRoute(context: Context): PlayerMediaItem? {
    val version = activeVersion ?: return null
    if (!version.discSource || playMethod == PlaybackMethod.DirectStream) return null
    if (!url.startsWith("file://", true) && !url.startsWith("content://", true)) return null

    val declared =
        detectPlaybackDiscKind(
            container = version.container,
            labelHint = version.label,
            declaredDiscSource = true,
        )
    val classified = cachedLocalPlaybackDiscKind(url) ?: declared
    val explicitBluRayLabel =
        version.label.uppercase().let { "BLU-RAY" in it || "BLURAY" in it || "BDMV" in it }
    val confirmedBluRay =
        classified == PlaybackDiscKind.BluRay ||
            classified == PlaybackDiscKind.Bdmv ||
            (classified == PlaybackDiscKind.Iso && explicitBluRayLabel)
    if (!confirmedBluRay) return null

    val source = NativeLocalBluRaySource.create(context, url) ?: return null
    val nativeId = NativeLocalBluRayRegistry.register(source)
    if (nativeId == null) {
        source.closeNativeSource()
        return null
    }
    val nativeUrl = "$YFUSE_REMOTE_BLURAY_PREFIX$nativeId"
    val nativeVersion = version.copy(url = nativeUrl, playMethod = PlaybackMethod.DirectPlay)
    return copy(
        url = nativeUrl,
        playMethod = PlaybackMethod.DirectPlay,
        forcedTranscodeReason = null,
        versions = versions.map { candidate -> if (candidate.id == version.id) nativeVersion else candidate },
    )
}
