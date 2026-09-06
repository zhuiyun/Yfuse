package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.demux.YDemuxSource

/** Probe and playback must use the same redirect, range and credential boundary. */
internal fun enhancedDemuxSource(
    item: YMediaItem,
    probeOnly: Boolean = false,
    localize: ((YMediaItem) -> String)? = null,
): YDemuxSource {
    val uri =
        if (localize != null && shouldProxyEnhancedSourceUri(item.uri)) localize(item) else item.uri
    val proxied = uri != item.uri
    return YDemuxSource(
        uri = uri,
        headers = if (proxied) emptyMap() else item.headers,
        cacheIdentity = item.cacheIdentity,
        cacheMaximumBytes = item.cacheMaximumBytes,
        transportCredentials = item.transportCredentials.takeUnless { proxied },
        probeOnly = probeOnly,
    )
}

internal fun AndroidYCoreHttpProxy.enhancedSource(
    item: YMediaItem,
    probeOnly: Boolean = false,
): YDemuxSource =
    enhancedDemuxSource(item, probeOnly) { upstream ->
        localUrl(
            upstreamUri = upstream.uri,
            upstreamHeaders = upstream.headers,
            credentials = upstream.transportCredentials,
            cacheable = upstream.cacheIdentity != null && upstream.cacheMaximumBytes > 0L,
            cacheIdentity = upstream.cacheIdentity,
        )
    }
