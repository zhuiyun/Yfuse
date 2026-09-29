package com.yfuse.core.filesource

/** One video of a folder's play queue, with the sidecars chosen for it. */
data class FileSourceQueueEntry(
    val entry: FileSourceEntry,
    /** From the source's root: the folder's path plus the file name. */
    val path: List<String>,
    val itemId: String,
    val subtitles: List<PairedSubtitle>,
)

/**
 * What tapping a video in a folder plays: that video, and the folder's other videos around it in
 * the order the browser shows them, so 下一集 and autoplay work in a season folder as they do in
 * a server's season.
 */
data class FileSourceQueuePlan(
    val entries: List<FileSourceQueueEntry>,
    val startIndex: Int,
) {
    /**
     * Sidecars in the order they are fetched: the tapped video's first, then the ones after it —
     * the next episodes are what autoplay reaches — then the ones before. Whatever has not
     * arrived within the preparation budget plays without its sidecar rather than holding the
     * launch.
     */
    fun subtitleFetchOrder(): List<Pair<Int, PairedSubtitle>> {
        val order = listOf(startIndex) + (startIndex + 1 until entries.size) + (startIndex - 1 downTo 0)
        return order.flatMap { index -> entries[index].subtitles.map { index to it } }
    }
}

/**
 * Builds the queue for [start] from its folder's listing. The queue is a window of at most
 * [maxItems] around the tapped video, so a folder of thousands of clips does not become a queue
 * of thousands of items.
 */
fun planFileSourceQueue(
    sourceId: String,
    folderPath: List<String>,
    folder: List<FileSourceEntry>,
    start: FileSourceEntry,
    maxItems: Int = MAX_QUEUE_ITEMS,
): FileSourceQueuePlan {
    require(maxItems > 0)
    val videos = folder.browsable().filter { it.isVideo }.ifEmpty { listOf(start) }
    val tapped = videos.indexOfFirst { it.name == start.name }.takeIf { it >= 0 } ?: 0
    val first = (tapped - maxItems / 2).coerceIn(0, (videos.size - maxItems).coerceAtLeast(0))
    val window = videos.subList(first, minOf(videos.size, first + maxItems))
    return FileSourceQueuePlan(
        entries =
            window.map { video ->
                val path = folderPath + video.name
                FileSourceQueueEntry(
                    entry = video,
                    path = path,
                    itemId = fileSourceItemId(sourceId, path),
                    subtitles = pairSubtitles(video, folder),
                )
            },
        startIndex = tapped - first,
    )
}

private const val MAX_QUEUE_ITEMS = 200
