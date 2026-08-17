package com.yfuse.feature.player

/** Credential-free process-local route understood only by the Yfuse libmpv native build. */
internal const val YFUSE_REMOTE_BLURAY_SCHEME = "yfusebd"
internal const val YFUSE_REMOTE_BLURAY_PREFIX = "$YFUSE_REMOTE_BLURAY_SCHEME://"

internal fun String.isYfuseNativeRemoteBluRayUrl(): Boolean =
    startsWith(YFUSE_REMOTE_BLURAY_PREFIX, ignoreCase = true)
