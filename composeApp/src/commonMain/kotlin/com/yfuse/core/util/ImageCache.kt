package com.yfuse.core.util

/** Drops Coil's memory and disk caches — 下载与缓存 · 清除全部缓存. */
expect suspend fun clearImageCache()

/** Bytes currently occupied by the shared Coil disk cache. */
expect suspend fun imageCacheUsageBytes(): Long
