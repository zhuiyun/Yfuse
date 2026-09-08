package com.yfuse.core.offline

internal data class OfflineBatchChange(
    val previous: OfflineMedia?,
    val item: OfflineMedia,
    val sourceChanged: Boolean,
)

internal data class OfflineEnqueueBatch(
    val items: List<OfflineMedia>,
    val changed: List<OfflineBatchChange>,
)

/** Plans the whole selection against one index snapshot; a duplicate item has one new revision. */
internal fun planOfflineEnqueueBatch(
    current: List<OfflineMedia>,
    requests: List<OfflineDownloadRequest>,
    storageTreeUri: String?,
    nowMs: Long,
): OfflineEnqueueBatch {
    val next = current.associateByTo(linkedMapOf(), OfflineMedia::id)
    val changed =
        requests.associateBy { "${it.serverId}#${it.itemId}" }.map { (id, request) ->
            val old = next[id]
            val plan = planOfflineEnqueue(old, request, nowMs)
            val item =
                plan.item.copy(
                    storageTreeUri = old?.storageTreeUri?.takeUnless { plan.sourceChanged } ?: storageTreeUri,
                )
            next[id] = item
            OfflineBatchChange(old, item, plan.sourceChanged)
        }
    return OfflineEnqueueBatch(next.values.toList(), changed)
}
