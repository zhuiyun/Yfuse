package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.isPersistentPlaybackManifestUrl
import com.yfuse.core.playback.shouldProxyMpvNetworkUrl

/**
 * Returns the original remote file that may enter the persistent sparse cache.
 *
 * Runtime fallbacks intentionally do not mutate this policy: server HLS/progressive transcodes,
 * adaptive manifests, DRM, optical-disc sources and local files always bypass the disk cache.
 */
internal fun PlayerMediaItem.persistentPlaybackCacheUrl(
    usingServerTranscode: Boolean = startsWithServerTranscode(),
): String? {
    val version = activeVersion
    val source = url.trim()
    if (
        customHeaders?.values?.isNotEmpty() == true ||
        usingServerTranscode ||
        playMethod == PlaybackMethod.Transcode ||
        version?.playMethod == PlaybackMethod.Transcode ||
        drmConfiguration != null ||
        version?.drmConfiguration != null ||
        version?.discSource == true ||
        !shouldProxyMpvNetworkUrl(source) ||
        source.isPersistentPlaybackManifestUrl()
    ) {
        return null
    }
    val container = version?.container?.trim()?.lowercase()
    if (container in PERSISTENT_CACHE_ADAPTIVE_CONTAINERS) return null
    return source
}

private val PERSISTENT_CACHE_ADAPTIVE_CONTAINERS = setOf("hls", "m3u8", "dash", "mpd")
