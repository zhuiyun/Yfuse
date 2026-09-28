package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.offlineAddedComparator

enum class DownloadFilter(
    val label: String,
) {
    All("全部"),
    Active("进行中"),
    Completed("已完成"),
    Failed("失败"),
}

enum class DownloadSort(
    val label: String,
) {
    Added("添加顺序"),
    Name("名称"),
    Size("大小"),
}

internal fun filterAndSortDownloads(
    items: List<OfflineMedia>,
    filter: DownloadFilter,
    sort: DownloadSort,
): List<OfflineMedia> =
    items
        .filter { item ->
            when (filter) {
                DownloadFilter.All -> true
                DownloadFilter.Active ->
                    item.status in
                        setOf(
                            DownloadStatus.Queued,
                            DownloadStatus.WaitingForWifi,
                            DownloadStatus.Downloading,
                            DownloadStatus.Paused,
                        )
                DownloadFilter.Completed -> item.status == DownloadStatus.Completed
                DownloadFilter.Failed -> item.status == DownloadStatus.Failed
            }
        }.let { values ->
            when (sort) {
                DownloadSort.Added -> values.sortedWith(offlineAddedComparator)
                DownloadSort.Name ->
                    values.sortedWith(
                        compareBy<OfflineMedia> { it.title.lowercase() }.then(offlineAddedComparator),
                    )
                DownloadSort.Size ->
                    values.sortedWith(
                        compareByDescending<OfflineMedia> {
                            it.totalBytes.coerceAtLeast(0L)
                        }.then(offlineAddedComparator),
                    )
            }
        }
