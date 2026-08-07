package com.yfuse.core.util

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Set once from `YfuseApp.onCreate`; Coil's singleton needs a platform context. */
internal var imageCacheContext: Context? = null

actual suspend fun clearImageCache() = withContext(Dispatchers.IO) {
    val context = imageCacheContext ?: return@withContext
    val loader = SingletonImageLoader.get(context)
    loader.memoryCache?.clear()
    // Coil's DiskCache.clear() synchronously walks and deletes its entries.
    loader.diskCache?.clear()
}
