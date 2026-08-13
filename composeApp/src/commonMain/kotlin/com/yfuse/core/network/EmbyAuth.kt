package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel

const val DEFAULT_EMBY_CLIENT_NAME = "Emby for Android Mobile"

/**
 * Builds the `X-Emby-Authorization` header value Emby requires on the
 * authentication request (and accepts on all requests).
 */
fun buildAuthHeader(appVersion: String): String =
    "MediaBrowser Client=\"$DEFAULT_EMBY_CLIENT_NAME\", " +
        "Device=\"${deviceModel()}\", " +
        "DeviceId=\"${deviceId()}\", " +
        "Version=\"$appVersion\""
