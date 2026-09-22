package com.yfuse.core.util

/** Clears only transient playback data; downloaded offline media is stored separately. */
expect suspend fun clearVideoCache(): Long

/** Returns bytes currently occupied by transient playback data. */
expect suspend fun videoCacheUsageBytes(): Long
