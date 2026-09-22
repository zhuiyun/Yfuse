package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel
import io.ktor.http.Url

/** Query signatures bind the original URL bytes, including on a same-origin reverse proxy. */
internal fun String.hasSignedPlaybackQuery(): Boolean =
    runCatching {
        Url(this).parameters.names().any {
            it.lowercase() in setOf("signature", "sig", "x-amz-signature", "x-goog-signature", "auth_key")
        }
    }.getOrDefault(false)

/**
 * Complete the identity of an explicitly authenticated media URL, not the default server.
 * Both user and token must already belong to this request. Anonymous/signed CDN URLs and Plex
 * URLs are left alone; no server registry or global token is consulted at the network boundary.
 * The caller must scope these headers to the original HTTP origin on every redirect.
 */
internal fun embyPlaybackHeaders(
    url: String,
    appVersion: () -> String,
): Map<String, String> {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return emptyMap()
    if (
        parsed.protocol.name !in setOf("http", "https") ||
        parsed.user != null || parsed.password != null || url.hasSignedPlaybackQuery()
    ) {
        return emptyMap()
    }
    val tokens = parsed.playbackParameterValues(setOf("api_key", "apikey", "x-emby-token"))
    val users = parsed.playbackParameterValues(setOf("userid"))
    // Conflicting aliases must not silently switch accounts. Do not echo header-injection bytes.
    val token = tokens.singleOrNull()?.takeIf(::safePlaybackHeaderValue) ?: return emptyMap()
    val user = users.singleOrNull()?.takeIf(::safePlaybackHeaderValue) ?: return emptyMap()
    val version = appVersion()
    val identity = buildAuthHeader(version) + ", UserId=\"${user.escapePlaybackHeader()}\""
    val authorization = mediaBrowserAuthorization(token, identity)
    return mapOf(
        "Authorization" to authorization,
        "X-Emby-Authorization" to authorization,
        "X-Emby-Token" to token,
        "X-Emby-Client" to DEFAULT_EMBY_CLIENT_NAME,
        "X-Emby-Client-Version" to version,
        "X-Emby-Device-Id" to deviceId(),
        "X-Emby-Device-Name" to deviceModel(),
    )
}

private fun Url.playbackParameterValues(names: Set<String>): List<String> =
    parameters.entries().filter { it.key.lowercase() in names }.flatMap { it.value }.distinct()

private fun safePlaybackHeaderValue(value: String): Boolean =
    value.isNotBlank() && value.none { it < ' ' || it == '\u007f' }

private fun String.escapePlaybackHeader(): String = replace("\\", "\\\\").replace("\"", "\\\"")

/** A declared DirectPlay must not silently become an HLS/DASH or codec-converting request. */
internal fun originalNegotiatedPlaybackUrl(url: String): String? {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (parsed.protocol.name !in setOf("http", "https") || parsed.user != null || parsed.password != null) return null
    val path = parsed.encodedPath.lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null
    val transformed =
        parsed.parameters.entries().any { (name, values) ->
            when (name.lowercase()) {
                "static" -> values.any { it.equals("false", ignoreCase = true) }
                "videocodec", "audiocodec" -> values.any { !it.equals("copy", ignoreCase = true) }
                "transcodingprotocol" -> true
                else -> false
            }
        }
    return url.takeUnless { transformed }
}
