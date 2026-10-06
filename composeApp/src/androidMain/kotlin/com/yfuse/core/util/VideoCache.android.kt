package com.yfuse.core.util

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yfuse.core2.android.AndroidYCoreBlockCache
import com.yfuse.feature.player.VideoCachePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// YCore keeps its own persistent block store beside the Media3 cache the compatibility engines
// share. Both hold the same user-visible "video cache", so both are counted and both are cleared.

@OptIn(UnstableApi::class)
actual suspend fun clearVideoCache(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        val compatibility = runCatching { VideoCachePool.clear(context) }.getOrDefault(0L)
        val yCore = runCatching { AndroidYCoreBlockCache.clearAll(context.cacheDir) }.getOrDefault(0L)
        compatibility + yCore
    }

@OptIn(UnstableApi::class)
actual suspend fun videoCacheUsageBytes(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        val compatibility = runCatching { VideoCachePool.usage(context) }.getOrDefault(0L)
        val yCore = runCatching { AndroidYCoreBlockCache.usageBytes(context.cacheDir) }.getOrDefault(0L)
        compatibility + yCore
    }
