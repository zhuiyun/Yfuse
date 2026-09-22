package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.MAX_CONCURRENT_OFFLINE_DOWNLOADS
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.offlinePlaybackUri
import com.yfuse.core.offline.rememberOfflineStorageDirectoryPicker
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.profile.DownloadFilter
import com.yfuse.feature.profile.DownloadSort
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.profile.filterAndSortDownloads

/**
 * Offline library management for the remote.
 *
 * The download engine, resume logic and index are the phone's; only the surface is new. A row
 * expands into its actions rather than hiding them behind a long-press, because a television
 * remote has no long-press affordance that viewers reliably discover.
 */
@Composable
internal fun TvDownloadsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:downloads"
    val manager = component.offlineMedia
    val items by manager.items.collectAsState()
    val policy by manager.policy.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var offlineToPlay by remember { mutableStateOf<OfflineMedia?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val shown = remember(items, filter) { filterAndSortDownloads(items, filter, DownloadSort.Updated) }
    val activeCount =
        items.count {
            it.status == DownloadStatus.Downloading ||
                it.status == DownloadStatus.Queued ||
                it.status == DownloadStatus.WaitingForWifi
        }
    val completedBytes = items.filter { it.playable }.sumOf { it.totalBytes }

    val pickStorageDirectory =
        rememberOfflineStorageDirectoryPicker { treeUri, label ->
            manager.setStorageDirectory(treeUri, label)
            status = "下载位置已改为 $label"
        }

    TvSettingsPageScaffold(page = TvSettingsPage.Downloads, status = status) {
        item(key = "downloads-summary") {
            TvSettingsNote(
                if (items.isEmpty()) {
                    "还没有离线内容。在剧集或影片详情页选择「下载」即可加入队列。"
                } else {
                    "共 ${items.size} 项，进行中 $activeCount 项，已完成占用 ${formatBytes(completedBytes)}"
                },
            )
        }

        item(key = "downloads-filters") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DownloadFilter.entries.forEachIndexed { index, entry ->
                    TvActionButton(
                        label = entry.label,
                        stableId = "downloads:filter:${entry.name}",
                        focusScope = focusScope,
                        focusMemory = focusMemory,
                        onClick = { filter = entry },
                        selected = entry == filter,
                        focusRequester = if (index == 0) firstRowRequester else null,
                        navigationRequester = navigationRequester,
                        returnToNavigationOnLeft = index == 0,
                    )
                }
            }
        }

        if (shown.isEmpty() && items.isNotEmpty()) {
            item(key = "downloads-filter-empty") {
                TvSettingsNote("这个筛选下没有内容。")
            }
        }

        shown.forEach { media ->
            item(key = "download:${media.id}") {
                Column {
                    TvSettingRow(
                        title = media.title,
                        value = media.statusLabel(),
                        stableId = "downloads:item:${media.id}",
                        focusMemory = focusMemory,
                        onClick = { expandedId = if (expandedId == media.id) null else media.id },
                        icon = media.statusIcon(),
                        focusScope = focusScope,
                        subtitle = media.progressLabel(),
                        selected = expandedId == media.id,
                        navigationRequester = navigationRequester,
                    )
                    if (expandedId == media.id) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 56.dp, top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (media.playable) {
                                TvActionButton(
                                    label = "播放",
                                    stableId = "downloads:play:${media.id}",
                                    focusScope = focusScope,
                                    focusMemory = focusMemory,
                                    onClick = { offlineToPlay = media },
                                    primary = true,
                                    navigationRequester = navigationRequester,
                                )
                            }
                            when (media.status) {
                                DownloadStatus.Downloading,
                                DownloadStatus.Queued,
                                DownloadStatus.WaitingForWifi,
                                ->
                                    TvActionButton(
                                        label = "暂停",
                                        stableId = "downloads:pause:${media.id}",
                                        focusScope = focusScope,
                                        focusMemory = focusMemory,
                                        onClick = { manager.pause(media.id) },
                                        navigationRequester = navigationRequester,
                                    )
                                DownloadStatus.Paused, DownloadStatus.Failed ->
                                    TvActionButton(
                                        label = if (media.status == DownloadStatus.Failed) "重试" else "继续",
                                        stableId = "downloads:resume:${media.id}",
                                        focusScope = focusScope,
                                        focusMemory = focusMemory,
                                        onClick = { manager.resume(media.id) },
                                        navigationRequester = navigationRequester,
                                    )
                                DownloadStatus.Completed -> Unit
                            }
                            TvActionButton(
                                label = "删除",
                                stableId = "downloads:remove:${media.id}",
                                focusScope = focusScope,
                                focusMemory = focusMemory,
                                onClick = {
                                    manager.remove(media.id)
                                    expandedId = null
                                    status = "已删除《${media.title}》的离线文件"
                                },
                                navigationRequester = navigationRequester,
                            )
                        }
                    }
                    media.error?.takeIf { media.status == DownloadStatus.Failed }?.let { message ->
                        TvSettingsNote(message, tone = TvDanger, modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }

        if (activeCount > 0) {
            item(key = "downloads-queue-controls") { TvSettingsSectionTitle("队列") }
            item(key = "downloads-pause-all") {
                TvSettingRow(
                    title = "全部暂停",
                    value = "",
                    stableId = "downloads:pause-all",
                    focusMemory = focusMemory,
                    onClick = {
                        manager.pauseAll()
                        status = "已暂停全部下载"
                    },
                    icon = AppIcons.Pause,
                    focusScope = focusScope,
                    navigationRequester = navigationRequester,
                )
            }
        }
        if (items.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }) {
            item(key = "downloads-resume-all") {
                TvSettingRow(
                    title = "全部继续",
                    value = "",
                    stableId = "downloads:resume-all",
                    focusMemory = focusMemory,
                    onClick = {
                        manager.resumeAll()
                        status = "已继续全部下载"
                    },
                    icon = AppIcons.Play,
                    focusScope = focusScope,
                    navigationRequester = navigationRequester,
                )
            }
        }

        item(key = "downloads-section-settings") { TvSettingsSectionTitle("下载设置") }
        item(key = "downloads-budget") {
            TvSettingRow(
                title = "离线视频容量上限",
                value =
                    com.yfuse.core.offline
                        .offlineVideoBudgetLabel(policy.storageBudgetBytes),
                stableId = "downloads:budget",
                icon = AppIcons.Download,
                focusMemory = focusMemory,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
                onClick = {
                    val options = com.yfuse.core.offline.offlineVideoBudgetOptions
                    val next = options[(options.indexOf(policy.storageBudgetBytes) + 1) % options.size]
                    manager.setDownloadBudget(
                        next,
                        policy.autoDownloadChargingOnly,
                        policy.windowStartMinute,
                        policy.windowEndMinute,
                    )
                },
            )
        }
        item(key = "downloads-charging") {
            TvToggleRow(
                title = "自动追更仅在充电时下载",
                checked = policy.autoDownloadChargingOnly,
                stableId = "downloads:charging",
                icon = AppIcons.Download,
                focusMemory = focusMemory,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
                onToggle = {
                    manager.setDownloadBudget(
                        policy.storageBudgetBytes,
                        it,
                        policy.windowStartMinute,
                        policy.windowEndMinute,
                    )
                },
            )
        }
        item(key = "downloads-window") {
            TvSettingRow(
                title = "允许下载时段",
                value =
                    com.yfuse.core.offline.offlineDownloadWindowLabel(
                        policy.windowStartMinute,
                        policy.windowEndMinute,
                    ),
                stableId = "downloads:window",
                icon = AppIcons.Download,
                focusMemory = focusMemory,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
                onClick = {
                    val options = com.yfuse.core.offline.offlineDownloadWindowOptions
                    val next =
                        options[
                            (options.indexOf(policy.windowStartMinute to policy.windowEndMinute) + 1) %
                                options.size,
                        ]
                    manager.setDownloadBudget(
                        policy.storageBudgetBytes,
                        policy.autoDownloadChargingOnly,
                        next.first,
                        next.second,
                    )
                },
            )
        }
        item(key = "downloads-wifi-only") {
            TvToggleRow(
                title = "仅在 Wi-Fi 下下载",
                checked = policy.wifiOnly,
                stableId = "downloads:wifi-only",
                focusMemory = focusMemory,
                onToggle = manager::setWifiOnly,
                icon = AppIcons.Cloud,
                focusScope = focusScope,
                subtitle = "有线连接的电视始终视为非计费网络",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "downloads-concurrent") {
            TvChoiceRow(
                title = "同时下载数",
                options = (1..MAX_CONCURRENT_OFFLINE_DOWNLOADS).toList(),
                selected = policy.maxConcurrentDownloads.coerceIn(1, MAX_CONCURRENT_OFFLINE_DOWNLOADS),
                label = { "$it 个" },
                stableId = "downloads:concurrent",
                focusMemory = focusMemory,
                onSelect = manager::setMaxConcurrentDownloads,
                icon = AppIcons.Collapse,
                focusScope = focusScope,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "downloads-auto-delete") {
            TvToggleRow(
                title = "看完后自动删除",
                checked = policy.autoDeleteWatched,
                stableId = "downloads:auto-delete",
                focusMemory = focusMemory,
                onToggle = manager::setAutoDeleteWatched,
                icon = AppIcons.Close,
                focusScope = focusScope,
                subtitle = "播放到结尾后释放这一集占用的空间",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "downloads-auto-download") {
            TvToggleRow(
                title = "自动下载新剧集",
                checked = policy.autoDownloadEnabled,
                stableId = "downloads:auto-download",
                focusMemory = focusMemory,
                onToggle = manager::setAutoDownloadEnabled,
                icon = AppIcons.Series,
                focusScope = focusScope,
                subtitle = "已订阅的剧集更新后自动排入队列",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "downloads-storage") {
            TvSettingRow(
                title = "下载位置",
                value = policy.storageLabel ?: "应用私有空间",
                stableId = "downloads:storage",
                focusMemory = focusMemory,
                onClick = {
                    // A television often has no document provider at all, so the picker can be
                    // absent rather than merely cancelled. Say so instead of failing silently.
                    runCatching { pickStorageDirectory() }
                        .onFailure { status = "这台设备没有可用的文件选择器，将继续使用应用私有空间。" }
                },
                icon = AppIcons.Server,
                focusScope = focusScope,
                subtitle = "选择 U 盘或外置硬盘上的目录，容量比机身空间大得多",
                navigationRequester = navigationRequester,
            )
        }
        if (policy.storageTreeUri != null) {
            item(key = "downloads-storage-reset") {
                TvSettingRow(
                    title = "恢复到应用私有空间",
                    value = "",
                    stableId = "downloads:storage-reset",
                    focusMemory = focusMemory,
                    onClick = {
                        manager.setStorageDirectory(null, null)
                        status = "新的下载会写入应用私有空间"
                    },
                    icon = AppIcons.Refresh,
                    focusScope = focusScope,
                    subtitle = "已经下载到外置目录的文件不会移动",
                    navigationRequester = navigationRequester,
                )
            }
        }
    }

    offlineToPlay?.takeIf { it.playable }?.let { offline ->
        val path = offline.localPath
        if (path != null) {
            PlayerLauncher(
                items =
                    listOf(
                        PlayerMediaItem(
                            id = offline.itemId,
                            url = offlinePlaybackUri(path),
                            transcodeUrl = offlinePlaybackUri(path),
                            title = offline.title,
                            serverId = offline.serverId,
                            externalSubtitleUri = offline.subtitlePath?.let(::offlinePlaybackUri),
                            externalSubtitleLanguage = offline.subtitleLanguage,
                        ),
                    ),
                startIndex = 0,
                startPositionMs = 0L,
                onLaunched = { offlineToPlay = null },
            )
        }
    }
}

private fun OfflineMedia.statusLabel(): String =
    when (status) {
        DownloadStatus.Queued -> "排队中"
        DownloadStatus.WaitingForWifi -> "等待 Wi-Fi"
        DownloadStatus.Downloading -> "下载中"
        DownloadStatus.Paused -> "已暂停"
        DownloadStatus.Completed -> "已完成"
        DownloadStatus.Failed -> "失败"
    }

private fun OfflineMedia.statusIcon() =
    when (status) {
        DownloadStatus.Completed -> AppIcons.Check
        DownloadStatus.Failed -> AppIcons.Info
        DownloadStatus.Paused -> AppIcons.Pause
        else -> AppIcons.Download
    }

private fun OfflineMedia.progressLabel(): String =
    when {
        status == DownloadStatus.Completed -> formatBytes(totalBytes)
        totalBytes > 0L -> {
            val percent = (downloadedBytes * 100 / totalBytes).coerceIn(0, 100)
            "$percent% · ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
        }
        downloadedBytes > 0L -> formatBytes(downloadedBytes)
        else -> "等待开始"
    }
