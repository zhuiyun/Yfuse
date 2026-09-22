package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel

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
