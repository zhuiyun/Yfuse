package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel

const val CLIENT_NAME = "Yfuse"

/**
 * Builds the `X-Emby-Authorization` header value Emby requires on the
 * authentication request (and accepts on all requests).
 */
fun buildAuthHeader(appVersion: String): String =
    "MediaBrowser Client=\"$CLIENT_NAME\", " +
        "Device=\"${deviceModel()}\", " +
        "DeviceId=\"${deviceId()}\", " +
        "Version=\"$appVersion\""
