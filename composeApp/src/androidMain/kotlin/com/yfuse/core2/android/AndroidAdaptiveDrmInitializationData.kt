package com.yfuse.core2.android

import com.yfuse.core.playback.extractWidevinePsshFromDash
import com.yfuse.core.playback.extractWidevinePsshFromHls
import com.yfuse.core2.adaptive.YHlsPlaylist
import com.yfuse.core2.adaptive.parseYHlsPlaylist

/**
 * Resolves Widevine initialization data from an adaptive manifest without involving a player
 * runtime. Android's MediaExtractor does not consistently expose psshInfo for HLS, especially when
 * the EXT-X-KEY is present only in a selected media playlist.
 */
internal fun resolveWidevineAdaptiveInitializationData(
    rootManifest: String,
    rootUri: String,
    loadChildManifest: (String) -> String?,
): ByteArray? {
    runCatching { extractWidevinePsshFromDash(rootManifest, rootUri) }
        .getOrNull()
        ?.let { return it }
    runCatching { extractWidevinePsshFromHls(rootManifest) }
        .getOrNull()
        ?.let { return it }

    val pending = ArrayDeque<String>()
    val visited = linkedSetOf(rootUri)
    enqueueHlsChildren(rootManifest, rootUri, pending)
    var loaded = 0
    while (pending.isNotEmpty() && loaded < MAX_DRM_CHILD_MANIFESTS) {
        val uri = pending.removeFirst()
        if (!visited.add(uri)) continue
        val manifest = loadChildManifest(uri) ?: continue
        loaded += 1
        runCatching { extractWidevinePsshFromHls(manifest) }
            .getOrNull()
            ?.let { return it }
        if (loaded < MAX_DRM_CHILD_MANIFESTS) enqueueHlsChildren(manifest, uri, pending)
    }
    return null
}

private fun enqueueHlsChildren(
    manifest: String,
    baseUri: String,
    pending: ArrayDeque<String>,
) {
    val master = runCatching { parseYHlsPlaylist(manifest, baseUri) }.getOrNull() as? YHlsPlaylist.Master
        ?: return
    master.variants.asSequence().map { it.uri }.forEach(pending::addLast)
    master.renditions.asSequence().mapNotNull { it.uri }.forEach(pending::addLast)
}

private const val MAX_DRM_CHILD_MANIFESTS = 16
