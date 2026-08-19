package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest

/** Optional executor for direct optical-disc items prepared by the platform source bridge. */
internal fun interface AndroidCore2DiscRouteFactory {
    fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        startSpeed: Float,
        forceSoftwareDecode: Boolean,
    ): YPlayer?
}
