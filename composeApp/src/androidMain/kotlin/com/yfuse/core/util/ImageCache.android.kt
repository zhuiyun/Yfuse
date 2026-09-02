package com.yfuse.core.util

import android.app.Application
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Set once from `YfuseApp.onCreate`; restricting this to Application prevents Activity leaks. */
internal var imageCacheContext: Application? = null

actual suspend fun clearImageCache() =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        // Coil's DiskCache.clear() synchronously walks and deletes its entries.
        loader.diskCache?.clear()
    }

actual suspend fun imageCacheUsageBytes(): Long =
    withContext(Dispatchers.IO) {
        val context = imageCacheContext ?: return@withContext 0L
        context.cacheDir.resolve(IMAGE_CACHE_DIRECTORY).directorySizeBytes()
    }

private fun java.io.File.directorySizeBytes(): Long {
    if (!exists()) return 0L
    if (isFile) return length().coerceAtLeast(0L)
    return listFiles().orEmpty().sumOf { child -> child.directorySizeBytes() }
}

private const val IMAGE_CACHE_DIRECTORY = "image_cache_v2"
