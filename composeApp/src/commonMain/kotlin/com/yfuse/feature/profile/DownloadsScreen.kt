package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.continuousRounded
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager

enum class DownloadFilter(val label: String) {
    All("全部"), Active("进行中"), Completed("已完成"), Failed("失败")
}

enum class DownloadSort(val label: String) { Updated("最近更新"), Name("名称"), Size("大小") }

@Composable
internal fun DownloadsScreen(
    onBack: () -> Unit,
    manager: OfflineMediaManager,
    onPlay: (OfflineMedia) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    val items by manager.items.collectAsState()
    val wifiOnly by manager.wifiOnly.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var sort by remember { mutableStateOf(DownloadSort.Updated) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val shown = remember(items, filter, sort) {
        items.filter { item ->
            when (filter) {
                DownloadFilter.All -> true
                DownloadFilter.Active -> item.status in setOf(
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
                DownloadSort.Updated -> values.sortedByDescending { it.updatedAtEpochMs }
                DownloadSort.Name -> values.sortedBy { it.title.lowercase() }
                DownloadSort.Size -> values.sortedByDescending { maxOf(it.totalBytes, it.downloadedBytes) }
            }
        }
    }
    val selectedItems = items.filter { it.id in selected }
    val usedBytes = items.filter { it.status == DownloadStatus.Completed }.sumOf { it.downloadedBytes }
    val activeCount = items.count { it.status in setOf(DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.Downloading, DownloadStatus.Paused) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = Dimens.contentTop, bottom = TabBarInset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).pressable(onClick = onBack)
                        .glass(continuousRounded(12.dp), palette.card3, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("下载中心", style = sc(20f, 700), color = palette.text)
                    Text(
                        "${items.size} 项 · $activeCount 进行中 · ${formatDownloadBytes(usedBytes)} 离线文件",
                        style = mr(10.5f, 500),
                        color = palette.sub2,
                    )
                }
                Text(
                    if (selected.isEmpty()) "多选" else "完成",
                    style = sc(12f, 700),
                    color = accent,
                    modifier = Modifier.pressable {
                        selected = if (selected.isEmpty()) shown.mapTo(linkedSetOf()) { it.id } else emptySet()
                    }.padding(8.dp),
                )
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal)
                    .glass(GlassShapes.card, palette.card, palette.border)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("下载策略", style = sc(13f, 700), color = palette.text)
                    Text(sort.label, style = mr(11f, 600), color = accent, modifier = Modifier.pressable {
                        sort = DownloadSort.entries[(DownloadSort.entries.indexOf(sort) + 1) % DownloadSort.entries.size]
                    })
                }
                Row(
                    Modifier.fillMaxWidth().pressable { manager.setWifiOnly(!wifiOnly) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("仅 Wi-Fi 下载", style = sc(12.5f, 600), color = palette.text)
                        Text("关闭后可能使用蜂窝流量", style = mr(10.5f, 400), color = palette.sub2)
                    }
                    Text(if (wifiOnly) "已开启" else "已关闭", style = sc(11f, 700), color = if (wifiOnly) accent else palette.sub2)
                }
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DownloadFilter.entries) { value ->
                    val active = filter == value
                    Text(
                        value.label,
                        style = sc(11.5f, if (active) 700 else 500),
                        color = if (active) accent else palette.body,
                        modifier = Modifier.pressable { filter = value }
                            .glass(
                                GlassShapes.chip,
                                if (active) accent.copy(alpha = 0.13f) else palette.card2,
                                if (active) accent.copy(alpha = 0.28f) else palette.border,
                            ).padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal)
                        .glass(GlassShapes.card, palette.card2, palette.border)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BatchAction("暂停") { selectedItems.forEach { manager.pause(it.id) } }
                    BatchAction("继续/重试") { selectedItems.forEach { manager.resume(it.id) } }
                    BatchAction("删除", danger = true) {
                        selectedItems.forEach { manager.remove(it.id) }
                        selected = emptySet()
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(AppIcons.Download, null, tint = palette.hint, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (items.isEmpty()) "还没有下载任务\n在详情页选择下载后会出现在这里" else "当前筛选没有任务",
                        style = sc(12f, 400, lineHeight = 19f),
                        color = palette.hint,
                    )
                }
            }
        } else {
            items(shown, key = { it.id }) { item ->
                DownloadTaskRow(
                    item = item,
                    selected = item.id in selected,
                    selectionMode = selected.isNotEmpty(),
                    onToggleSelected = {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    },
                    onPlay = { onPlay(item) },
                    onPause = { manager.pause(item.id) },
                    onResume = { manager.resume(item.id) },
                    onRemove = { manager.remove(item.id); selected = selected - item.id },
                    modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                )
            }
        }
    }
}

@Composable
private fun BatchAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Text(
        label,
        style = sc(11f, 700),
        color = if (danger) Semantic.Error else accent,
        modifier = Modifier.pressable(onClick = onClick)
            .glass(GlassShapes.chip, palette.card3, palette.border)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun DownloadTaskRow(
    item: OfflineMedia,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Column(
        modifier.fillMaxWidth()
            .pressable(onClick = if (selectionMode) onToggleSelected else {
                { if (item.playable) onPlay() else if (item.status == DownloadStatus.Downloading) onPause() else onResume() }
            })
            .glass(
                GlassShapes.card,
                if (selected) accent.copy(alpha = 0.10f) else palette.card,
                if (selected) accent.copy(alpha = 0.30f) else palette.border,
            ).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Text(if (selected) "✓" else "○", style = sc(16f, 700), color = if (selected) accent else palette.sub2)
                Spacer(Modifier.size(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = sc(13f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    downloadStatusText(item),
                    style = mr(10.5f, 500),
                    color = if (item.status == DownloadStatus.Failed) Semantic.Error else palette.sub2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionMode) {
                Text(
                    when (item.status) {
                        DownloadStatus.Completed -> "播放"
                        DownloadStatus.Downloading -> "暂停"
                        DownloadStatus.Failed -> "重试"
                        else -> "继续"
                    },
                    style = sc(11f, 700),
                    color = accent,
                )
                Icon(
                    AppIcons.Close,
                    "删除下载",
                    tint = palette.sub2,
                    modifier = Modifier.padding(start = 10.dp).size(28.dp).pressable(onClick = onRemove).padding(7.dp),
                )
            }
        }
        if (item.status != DownloadStatus.Completed) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(continuousRounded(2.dp)).background(palette.border)) {
                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height(4.dp).background(accent))
            }
        }
    }
}

private fun downloadStatusText(item: OfflineMedia): String = when (item.status) {
    DownloadStatus.Queued -> "等待下载"
    DownloadStatus.WaitingForWifi -> "等待 Wi-Fi"
    DownloadStatus.Downloading -> "${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}"
    DownloadStatus.Paused -> "已暂停 · ${formatDownloadBytes(item.downloadedBytes)}"
    DownloadStatus.Completed -> "已完成 · ${formatDownloadBytes(item.downloadedBytes)}"
    DownloadStatus.Failed -> item.error ?: "下载失败，可点按重试"
}

internal fun formatDownloadBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "${(value / 1024.0 / 1024.0 / 1024.0 * 10).toInt() / 10.0} GB"
    value >= 1024L * 1024L -> "${value / 1024L / 1024L} MB"
    value >= 1024L -> "${value / 1024L} KB"
    else -> "$value B"
}
