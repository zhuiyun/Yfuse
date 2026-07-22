package com.yfuse

/** Human-readable device model, used in the Emby authorization header. */
expect fun deviceModel(): String

/** Stable per-install device id, used in the Emby authorization header. */
expect fun deviceId(): String
