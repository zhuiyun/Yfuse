package com.yfuse.core.util

import androidx.media3.common.util.UnstableApi
import com.yfuse.feature.player.VideoCachePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
actual suspend fun clearVideoCache(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        VideoCachePool.clear(context)
    }

@OptIn(UnstableApi::class)
actual suspend fun videoCacheUsageBytes(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        runCatching { VideoCachePool.usage(context) }.getOrDefault(0L)
    }
