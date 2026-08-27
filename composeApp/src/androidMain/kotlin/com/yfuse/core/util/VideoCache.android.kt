package com.yfuse.core.util

import com.yfuse.feature.player.VideoCachePool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun clearVideoCache(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        VideoCachePool.clear(context)
    }
