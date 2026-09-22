package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.strategy.YPlaybackPlan

/** Optional executor for GPU/software routes that are still backed by the compatibility runtime. */
internal fun interface AndroidCore2FallbackRouteFactory {
    fun create(
        item: YMediaItem,
        request: YPlayerOpenRequest,
        plan: YPlaybackPlan,
        startSpeed: Float,
    ): YPlayer?
}
