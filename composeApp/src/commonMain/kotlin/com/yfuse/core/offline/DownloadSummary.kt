package com.yfuse.core.offline

/** A queue-wide snapshot; historical completed downloads never dilute current progress. */
data class DownloadSummary(
    val active: Int,
    val paused: Int,
    val failed: Int,
    val percent: Int?,
    val title: String,
    val detail: String,
) {
    val visible: Boolean get() = active + paused + failed > 0
}

fun summarizeDownloads(items: List<OfflineMedia>): DownloadSummary {
    val pending = items.filter { it.status != DownloadStatus.Completed }
    val running =
        pending.filter {
            it.status in
                setOf(DownloadStatus.Downloading, DownloadStatus.Queued, DownloadStatus.WaitingForWifi)
        }
    val paused = pending.count { it.status == DownloadStatus.Paused }
    val failed = pending.count { it.status == DownloadStatus.Failed }
    val percent =
        pending.takeIf { it.isNotEmpty() && it.all { item -> item.totalBytes > 0L } }?.let { queue ->
            val total = queue.sumOf { it.totalBytes.toDouble() }
            (
                queue.sumOf {
                    it.downloadedBytes
                        .coerceIn(
                            0L,
                            it.totalBytes,
                        ).toDouble()
                } / total * 100
            ).toInt().coerceIn(0, 100)
        }
    val downloading = running.count { it.status == DownloadStatus.Downloading }
    return DownloadSummary(
        active = running.size,
        paused = paused,
        failed = failed,
        percent = percent,
        title =
            when {
                running.isNotEmpty() -> "下载任务 · ${running.size} 项"
                paused > 0 -> "已暂停 · $paused 项"
                failed > 0 -> "下载失败 · $failed 项"
                else -> "下载已完成"
            },
        detail =
            when {
                downloading > 0 -> "$downloading 项下载中" + (percent?.let { " · $it%" } ?: " · 正在读取文件大小")
                running.any { it.status == DownloadStatus.WaitingForWifi } -> "等待 Wi-Fi 或下载条件满足"
                running.isNotEmpty() -> "等待下载"
                failed > 0 ->
                    when (pending.firstOrNull { it.status == DownloadStatus.Failed }?.lastFailureKind) {
                        DownloadFailureKind.Authentication -> "登录已失效，请重新登录服务器"
                        DownloadFailureKind.Storage -> "存储空间或目录不可用"
                        DownloadFailureKind.Network -> "网络连接失败，可重试"
                        DownloadFailureKind.Source -> "片源不可用，请检查资源"
                        DownloadFailureKind.Server -> "服务器暂时无法提供文件"
                        else -> "打开下载管理查看原因"
                    }
                paused > 0 -> "可继续下载"
                else -> "离线内容已准备好"
            },
    )
}
