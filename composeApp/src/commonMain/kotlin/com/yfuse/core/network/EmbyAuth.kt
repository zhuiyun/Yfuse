package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel
import io.ktor.http.encodeURLParameter

const val DEFAULT_EMBY_CLIENT_NAME = "Emby for Android Mobile"

/** Client identity used when tokens were issued before the 0.2.60 compatibility change. */
internal const val LEGACY_EMBY_CLIENT_NAME = "Yfuse"

/**
 * Builds the `X-Emby-Authorization` header value Emby requires on the
 * authentication request (and accepts on all requests).
 */
fun buildAuthHeader(appVersion: String): String = buildAuthHeader(appVersion, DEFAULT_EMBY_CLIENT_NAME)

internal fun buildAuthHeader(
    appVersion: String,
    clientName: String,
): String =
    "MediaBrowser Client=\"$clientName\", " +
        "Device=\"${deviceModel()}\", " +
        "DeviceId=\"${deviceId()}\", " +
        "Version=\"$appVersion\""

/** Jellyfin 12 accepts the standard Authorization scheme; legacy Emby headers stay compatible. */
fun mediaBrowserAuthorization(
    accessToken: String,
    identity: String? = null,
): String {
    val prefix = identity?.takeIf { it.startsWith("MediaBrowser ") } ?: "MediaBrowser"
    val escaped = accessToken.replace("\\", "\\\\").replace("\"", "\\\"")
    require('\r' !in escaped && '\n' !in escaped) { "Invalid access token" }
    return prefix + (if (prefix == "MediaBrowser") " " else ", ") + "Token=\"$escaped\""
}

/** URL-only consumers (Coil, Cast, native players) need the modern ApiKey spelling too.
 * Keep Emby's spelling with the identical token for older Emby installations.
 */
fun mediaBrowserTokenQuery(token: String): String {
    val encoded = token.encodeURLParameter()
    return "api_key=$encoded&ApiKey=$encoded"
}
