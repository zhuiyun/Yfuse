package com.yfuse.core.network

import com.yfuse.deviceId
import com.yfuse.deviceModel

const val CLIENT_NAME = "Yfuse"
const val APP_VERSION = "0.1.0"

/**
 * Builds the `X-Emby-Authorization` header value Emby requires on the
 * authentication request (and accepts on all requests).
 */
fun buildAuthHeader(appVersion: String = APP_VERSION): String =
    "MediaBrowser Client=\"$CLIENT_NAME\", " +
        "Device=\"${deviceModel()}\", " +
        "DeviceId=\"${deviceId()}\", " +
        "Version=\"$appVersion\""
