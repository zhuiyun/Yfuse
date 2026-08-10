package com.yfuse.core.data

/** One-shot exact-media failover plan produced by Detail and consumed by the Player queue. */
data class PlaybackFailoverPlan(
    val itemId: String,
    val mediaKey: String,
    val fallbackServerIds: List<String>,
)

class PlaybackFailoverRequest {
    private var pending: PlaybackFailoverPlan? = null

    @Synchronized
    fun set(plan: PlaybackFailoverPlan) {
        pending = plan
    }

    @Synchronized
    fun consume(itemId: String): PlaybackFailoverPlan? {
        val plan = pending?.takeIf { it.itemId == itemId }
        if (plan != null) pending = null
        return plan
    }
}
