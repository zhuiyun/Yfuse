package com.yfuse.feature.player

import android.content.Context
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.cachedLocalPlaybackDiscKind
import com.yfuse.core.playback.detectPlaybackDiscKind

/**
 * Gives known local Blu-ray sources the same libbluray session used for HDMV menus.
 *
 * ISO images use the 2048-byte block bridge; extracted BDMV trees use the independent
 * `bd_open_files()` VFS. Generic ISO images stay on the old path until the bounded image classifier
 * has proved they are Blu-ray, so a DVD image is never sent to libbluray just because it ends in ISO.
 */
internal fun prepareNativeLocalBluRayRoute(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    context: Context,
): List<PlayerMediaItem> {
    val capabilities = installedMpvNativeBuildCapabilities
    if (!capabilities.remoteRawBluRay && !capabilities.bdmvVfs) return items
    val item = items.getOrNull(startIndex) ?: return items
    val prepared = item.prepareNativeLocalBluRayRoute(context, capabilities) ?: return items
    return items.toMutableList().also { it[startIndex] = prepared }
}

private fun PlayerMediaItem.prepareNativeLocalBluRayRoute(
    context: Context,
    capabilities: MpvNativeBuildCapabilities,
): PlayerMediaItem? {
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

    // Prefer the filesystem bridge. It recognizes a directory containing BDMV/, the BDMV directory
    // itself, index.bdmv/MovieObject.bdmv, and persisted SAF tree URIs. An ISO file simply fails this
    // probe and falls through to the block bridge without doing any large I/O.
    if (capabilities.bdmvVfs) {
        NativeLocalBdmvSource.create(context, url)?.let { source ->
            val nativeId = NativeLocalBdmvProxyRegistry.register(source)
            if (nativeId != null) {
                return withNativeLocalDiscUrl(
                    nativeUrl = "$YFUSE_BDMV_PREFIX$nativeId",
                    versionId = version.id,
                )
            }
            source.closeNativeSource()
        }
    }

    if (!capabilities.remoteRawBluRay) return null
    val source = NativeLocalBluRaySource.create(context, url) ?: return null
    val nativeId = NativeLocalBluRayRegistry.register(source)
    if (nativeId == null) {
        source.closeNativeSource()
        return null
    }
    return withNativeLocalDiscUrl(
        nativeUrl = "$YFUSE_REMOTE_BLURAY_PREFIX$nativeId",
        versionId = version.id,
    )
}

private fun PlayerMediaItem.withNativeLocalDiscUrl(
    nativeUrl: String,
    versionId: String,
): PlayerMediaItem {
    val nativeVersions =
        versions.map { candidate ->
            if (candidate.id == versionId) {
                candidate.copy(url = nativeUrl, playMethod = PlaybackMethod.DirectPlay)
            } else {
                candidate
            }
        }
    return copy(
        url = nativeUrl,
        playMethod = PlaybackMethod.DirectPlay,
        forcedTranscodeReason = null,
        versions = nativeVersions,
    )
}
