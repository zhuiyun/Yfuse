package com.yfuse.core.playback

import java.net.URI

/** Native players proxy only plain HTTP(S) URLs; local files and other schemes bypass the proxy. */
internal fun shouldProxyMpvNetworkUrl(url: String): Boolean =
    runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

/** Rewrites an HLS manifest's child URLs (segments and `URI=` attributes) through the local proxy. */
internal fun rewriteMpvHlsManifest(
    manifest: String,
    upstreamUrl: String,
    localize: (String) -> String,
): String {
    val base = runCatching { URI(upstreamUrl) }.getOrNull() ?: return manifest
    return manifest.lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            !line.startsWith('#') ->
                runCatching { localize(base.resolve(line.trim()).toString()) }.getOrDefault(line)
            "URI=" in line ->
                HLS_URI_ATTRIBUTE.replace(line) { match ->
                    val quote = match.groupValues[1]
                    val value = match.groupValues[2]
                    val localized =
                        runCatching { localize(base.resolve(value).toString()) }.getOrDefault(value)
                    "URI=$quote$localized$quote"
                }
            else -> line
        }
    }
}

private val HLS_URI_ATTRIBUTE = Regex("URI=([\\\"'])(.*?)(?:\\1)")
