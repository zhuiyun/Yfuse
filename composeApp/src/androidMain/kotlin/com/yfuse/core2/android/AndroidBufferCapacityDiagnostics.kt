package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.network.YPlaybackBufferDecision

/** Record why a full compressed queue had to resume before reaching the desired time reserve. */
internal fun logBufferCapacityLimit(
    route: String,
    decision: YPlaybackBufferDecision,
    bufferedUs: Long,
    queuedBytes: Long,
    queueBudgetBytes: Long,
) {
    if (!decision.capacityLimited) return
    AppLog.info(
        category = "player.core2",
        event = "buffer_capacity_limited",
        message = "Compressed queue reached its memory limit before the recovery reserve",
        attributes =
            mapOf(
                "route" to route,
                "bufferedMs" to (bufferedUs / 1_000L).toString(),
                "requiredMs" to (decision.requiredBufferedUs / 1_000L).toString(),
                "queueBytes" to queuedBytes.toString(),
                "queueBudgetBytes" to queueBudgetBytes.toString(),
            ),
    )
}
