package com.yfuse.core.util

import android.content.Context
import coil3.SingletonImageLoader

/** Set once from `YfuseApp.onCreate`; Coil's singleton needs a platform context. */
internal var imageCacheContext: Context? = null

actual fun clearImageCache() {
    val context = imageCacheContext ?: return
    val loader = SingletonImageLoader.get(context)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
}
