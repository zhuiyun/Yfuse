package com.yfuse.core.util

/** Clears only transient playback data; downloaded offline media is stored separately. */
expect suspend fun clearVideoCache(): Long
