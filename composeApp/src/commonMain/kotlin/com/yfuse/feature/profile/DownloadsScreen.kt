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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.summarizeOfflineQueue

enum class DownloadFilter(val label: String) {
    All("全部"), Active("进行中"), Completed("已完成"), Failed("失败")
}

enum class DownloadSort(val label: String) { Updated("最近更新"), Name("名称"), Size("大小") }

internal fun filterAndSortDownloads(
    items: List<OfflineMedia>,
    filter: DownloadFilter,
    sort: DownloadSort,
): List<OfflineMedia> = items.filter { item ->
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

@Composable
internal fun DownloadsScreen(
    onBack: () -> Unit,
    manager: OfflineMediaManager,
    onPlay: (OfflineMedia) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val items by manager.items.collectAsState()
    val wifiOnly by manager.wifiOnly.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var sort by remember { mutableStateOf(DownloadSort.Updated) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val shown = remember(items, filter, sort) {
        filterAndSortDownloads(items, filter, sort)
    }
    val selectedItems = items.filter { it.id in selected }
    val summary = remember(items) { summarizeOfflineQueue(items) }
    val canPauseAll = summary.active > 0
    val canResumeAll = summary.paused > 0 || summary.failed > 0

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
                    Modifier
                        .pressable(onClickLabel = "返回", onClick = onBack)
                        .touchTarget()
                        .size(36.dp)
                        .glass(AppShapes.control, palette.card3, palette.border),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.ChevronLeft, "返回", tint = palette.text, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("下载中心", style = AppTypography.section.strong, color = palette.text)
                    Text(
                        "${summary.total} 项 · ${summary.active} 进行中 · " +
                            "${summary.paused} 已暂停 · ${summary.failed} 失败",
                        style = AppTypography.caption.medium,
                        color = palette.sub2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${summary.completed} 已完成 · " +
                            "${formatDownloadBytes(summary.completedBytes)} 离线文件" +
                            if (summary.retryScheduled > 0) {
                                " · ${summary.retryScheduled} 项待自动重试"
                            } else {
                                ""
                            },
                        style = AppTypography.caption.regular,
                        color = palette.hint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (selected.isEmpty()) "多选" else "完成",
                    style = AppTypography.body.strong,
                    color = accent,
                    modifier = Modifier
                        .pressable(
                            onClickLabel = if (selected.isEmpty()) "选中当前下载" else "退出多选",
                        ) {
                            selected = if (selected.isEmpty()) {
                                shown.mapTo(linkedSetOf()) { it.id }
                            } else {
                                emptySet()
                            }
                        }
                        .touchTarget()
                        .padding(horizontal = 8.dp),
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载策略", style = AppTypography.body.strong, color = palette.text)
                    Text(
                        sort.label,
                        style = AppTypography.caption.strong,
                        color = accent,
                        modifier = Modifier
                            .pressable(onClickLabel = "更改排序，当前${sort.label}") {
                                sort = DownloadSort.entries[
                                    (DownloadSort.entries.indexOf(sort) + 1) % DownloadSort.entries.size
                                ]
                            }
                            .touchTarget()
                            .padding(horizontal = 8.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(
                            role = Role.Switch,
                            onClickLabel = if (wifiOnly) "允许蜂窝网络下载" else "仅允许 Wi-Fi 下载",
                        ) { manager.setWifiOnly(!wifiOnly) }
                        .touchTarget()
                        .semantics { toggleableState = ToggleableState(wifiOnly) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("仅 Wi-Fi 下载", style = AppTypography.body.strong, color = palette.text)
                        Text("关闭后可能使用蜂窝流量", style = AppTypography.caption.regular, color = palette.sub2)
                    }
                    Text(if (wifiOnly) "已开启" else "已关闭", style = AppTypography.body.strong, color = if (wifiOnly) accent else palette.sub2)
                }
                Text("队列控制", style = AppTypography.caption.strong, color = palette.sub2)
                if (largeText) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BatchAction(
                            label = "全部暂停",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canPauseAll,
                            onClick = manager::pauseAll,
                        )
                        BatchAction(
                            label = "全部继续/重试",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canResumeAll,
                            onClick = manager::resumeAll,
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BatchAction(
                            label = "全部暂停",
                            modifier = Modifier.weight(1f),
                            enabled = canPauseAll,
                            onClick = manager::pauseAll,
                        )
                        BatchAction(
                            label = "全部继续/重试",
                            modifier = Modifier.weight(1f),
                            enabled = canResumeAll,
                            onClick = manager::resumeAll,
                        )
                    }
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
                        style = if (active) AppTypography.body.strong else AppTypography.body.medium,
                        color = if (active) accent else palette.body,
                        modifier = Modifier
                            .pressable(
                                role = Role.RadioButton,
                                onClickLabel = "筛选${value.label}下载",
                            ) { filter = value }
                            .touchTarget()
                            .semantics { this.selected = active }
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
                val batchSurface = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .glass(GlassShapes.card, palette.card2, palette.border)
                    .padding(10.dp)
                if (largeText) {
                    Column(
                        batchSurface,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BatchAction("暂停", Modifier.fillMaxWidth()) {
                            selectedItems.forEach { manager.pause(it.id) }
                        }
                        BatchAction("继续/重试", Modifier.fillMaxWidth()) {
                            selectedItems.forEach { manager.resume(it.id) }
                        }
                        BatchAction("删除", Modifier.fillMaxWidth(), danger = true) {
                            selectedItems.forEach { manager.remove(it.id) }
                            selected = emptySet()
                        }
                    }
                } else {
                    Row(
                        batchSurface,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BatchAction("暂停", Modifier.weight(1f)) {
                            selectedItems.forEach { manager.pause(it.id) }
                        }
                        BatchAction("继续/重试", Modifier.weight(1f)) {
                            selectedItems.forEach { manager.resume(it.id) }
                        }
                        BatchAction("删除", Modifier.weight(1f), danger = true) {
                            selectedItems.forEach { manager.remove(it.id) }
                            selected = emptySet()
                        }
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
                        style = AppTypography.body.regular,
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
private fun BatchAction(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Text(
        label,
        style = AppTypography.body.strong,
        color = when {
            !enabled -> palette.hint
            danger -> Semantic.Error
            else -> accent
        },
        textAlign = TextAlign.Center,
        modifier = modifier
            .pressable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .touchTarget()
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
            .heightIn(min = MinTouchTarget)
            .glass(
                GlassShapes.card,
                if (selected) accent.copy(alpha = 0.10f) else palette.card,
                if (selected) accent.copy(alpha = 0.30f) else palette.border,
            ).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Text(if (selected) "✓" else "○", style = AppTypography.section.strong, color = if (selected) accent else palette.sub2)
                Spacer(Modifier.size(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = AppTypography.body.strong, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    downloadStatusText(item),
                    style = AppTypography.caption.medium,
                    color = when {
                        item.status == DownloadStatus.Failed -> Semantic.Error
                        item.nextRetryAt > 0L -> Semantic.Warning
                        else -> palette.sub2
                    },
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
                    style = AppTypography.body.strong,
                    color = accent,
                )
                Icon(
                    AppIcons.Close,
                    "删除下载",
                    tint = palette.sub2,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .pressable(onClickLabel = "删除下载", onClick = onRemove)
                        .touchTarget()
                        .size(28.dp)
                        .padding(7.dp),
                )
            }
        }
        if (item.status != DownloadStatus.Completed) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(AppShapes.track).background(palette.border)) {
                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height(4.dp).background(accent))
            }
        }
    }
}

private fun downloadStatusText(item: OfflineMedia): String = when (item.status) {
    DownloadStatus.Queued -> item.error ?: if (item.nextRetryAt > 0L) "等待自动重试" else "等待下载"
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
