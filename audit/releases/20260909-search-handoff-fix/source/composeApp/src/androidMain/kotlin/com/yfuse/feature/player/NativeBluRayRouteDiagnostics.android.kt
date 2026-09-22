package com.yfuse.feature.player

internal fun String.isYfuseNativeBluRayRoute(): Boolean =
    isYfuseNativeRemoteBluRayUrl() || startsWith(YFUSE_BDMV_PREFIX, ignoreCase = true)
