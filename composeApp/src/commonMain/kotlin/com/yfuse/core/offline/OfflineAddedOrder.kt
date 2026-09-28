package com.yfuse.core.offline

/** Newest additions first, with an immutable tie-breaker even if the database returns a new order. */
internal val offlineAddedComparator: Comparator<OfflineMedia> =
    compareByDescending<OfflineMedia> { it.addedOrder }.thenBy { it.id }

/**
 * Give legacy rows an order once, preserving the loaded list's order. New rows are appended by
 * the batch planner: the new batch goes above existing rows, in the request's episode order.
 * The caller persists these values before publishing the recovered index.
 */
internal fun ensureOfflineAddedOrder(items: List<OfflineMedia>): List<OfflineMedia> {
    var next = items.maxOfOrNull { it.addedOrder.coerceAtLeast(0L) } ?: 0L
    val missing = items.count { it.addedOrder <= 0L }
    check(next <= Long.MAX_VALUE - missing) { "下载记录序号已达上限" }
    val assigned = mutableMapOf<String, Long>()
    items.asReversed().filter { it.addedOrder <= 0L }.forEach { assigned[it.id] = ++next }
    return items
        .map { item -> assigned[item.id]?.let { item.copy(addedOrder = it) } ?: item }
        .sortedWith(offlineAddedComparator)
}
