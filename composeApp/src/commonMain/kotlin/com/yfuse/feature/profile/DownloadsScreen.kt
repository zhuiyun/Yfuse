package com.yfuse.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightOnChange
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDecorativePhase
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineIndexStatus
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.OfflineQueueSummary
import com.yfuse.core.offline.rememberOfflineStorageDirectoryPicker
import com.yfuse.core.offline.summarizeOfflineQueue
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The page's own row rhythm, restated for the bars that share one lazy item.
 *
 * They cannot lean on the list's `Arrangement.spacedBy`: a collapsed reveal is zero-height but
 * still claims its share of it, so each bar owns the gap above itself and gives it back on the
 * way out.
 */
private val DownloadBarGap = 14.dp

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
    Updated("最近更新"),
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
    val policy by manager.policy.collectAsState()
    val autoDownloadRuleCount by manager.autoDownloadRuleCount.collectAsState()
    val operationError by manager.operationError.collectAsState()
    val indexStatus by manager.indexStatus.collectAsState()
    val pickStorageDirectory =
        rememberOfflineStorageDirectoryPicker { treeUri, label ->
            manager.setStorageDirectory(treeUri, label)
        }
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var sort by remember { mutableStateOf(DownloadSort.Updated) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }

    val shown =
        remember(items, filter, sort) {
            filterAndSortDownloads(items, filter, sort)
        }
    val selectedItems = items.filter { it.id in selected }
    val summary = remember(items) { summarizeOfflineQueue(items) }
    val canPauseAll = summary.active > 0
    val canResumeAll = summary.paused > 0 || summary.failed > 0

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = SettingsHeaderTop, bottom = TabBarInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            motionItem {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = SettingsBackInset, end = Dimens.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsBackButton(onBack)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text("下载中心", style = AppTypography.section.strong, color = palette.text)
                        // One line, and only what is true. The header used to carry six counters
                        // over two wrapped lines, which on an empty queue was five zeros and a
                        // "0 B 离线文件" — the page opened by telling the reader nothing, at length.
                        Text(
                            when (indexStatus) {
                                OfflineIndexStatus.Loading -> "正在读取下载记录…"
                                OfflineIndexStatus.Failed -> "下载记录暂不可用"
                                OfflineIndexStatus.Ready -> downloadSummaryLine(summary)
                            },
                            style = AppTypography.caption.medium,
                            color = palette.sub2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Nothing to multi-select on an empty page.
                    AnimatedVisibility(
                        visible = items.isNotEmpty(),
                        enter = downloadRevealEnter(vertical = false),
                        exit = downloadRevealExit(vertical = false),
                    ) {
                        Text(
                            if (selected.isEmpty()) "多选" else "完成",
                            style = AppTypography.body.strong,
                            color = accent,
                            modifier =
                                Modifier
                                    .pressable(
                                        onClickLabel = if (selected.isEmpty()) "选中当前下载" else "退出多选",
                                    ) {
                                        selected =
                                            if (selected.isEmpty()) {
                                                shown.mapTo(linkedSetOf()) { it.id }
                                            } else {
                                                emptySet()
                                            }
                                    }.touchTarget()
                                    .padding(horizontal = 8.dp),
                        )
                    }
                }
            }

            operationError?.let { message ->
                motionItem(key = "download-operation-error") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            style = AppTypography.caption.medium,
                            color = palette.error,
                        )
                        Text(
                            "关闭",
                            style = AppTypography.caption.strong,
                            color = accent,
                            modifier =
                                Modifier
                                    .pressable(
                                        onClick = manager::clearOperationError,
                                    ).touchTarget()
                                    .padding(8.dp),
                        )
                    }
                }
            }

            // The one setting the page owns, as the settings row it is everywhere else in 我的 —
            // rather than a "下载策略" card that also held a sort control disguised as a status
            // line and a pair of queue buttons for a queue that is usually empty.
            motionItem {
                Section(title = "下载设置") {
                    SettingsCard {
                        SettingRow(
                            title = "离线视频容量上限",
                            value =
                                com.yfuse.core.offline
                                    .offlineVideoBudgetLabel(policy.storageBudgetBytes),
                            embedded = true,
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
                        SettingsDivider()
                        SwitchRow(
                            "自动追更仅在充电时下载",
                            policy.autoDownloadChargingOnly,
                            embedded = true,
                            onChange = {
                                manager.setDownloadBudget(
                                    policy.storageBudgetBytes,
                                    it,
                                    policy.windowStartMinute,
                                    policy.windowEndMinute,
                                )
                            },
                        )
                        SettingsDivider()
                        SettingRow(
                            title = "允许下载时段",
                            value =
                                com.yfuse.core.offline.offlineDownloadWindowLabel(
                                    policy.windowStartMinute,
                                    policy.windowEndMinute,
                                ),
                            embedded = true,
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
                        SettingsDivider()
                        SettingRow(
                            title = "保存位置",
                            value = "${policy.storageLabel ?: "应用内部存储"} ›",
                            embedded = true,
                            onClick = pickStorageDirectory,
                            icon = AppIcons.Download,
                            iconTint = SettingTint.downloads,
                        )
                        if (policy.storageTreeUri != null) {
                            SettingsDivider()
                            SettingRow(
                                title = "改回内部存储",
                                value = "仅影响新下载 ›",
                                embedded = true,
                                onClick = { manager.setStorageDirectory(null) },
                            )
                        }
                        SettingsDivider()
                        SwitchRow(
                            "仅 Wi-Fi 下载",
                            wifiOnly,
                            embedded = true,
                            icon = AppIcons.Download,
                            iconTint = SettingTint.downloads,
                            onChange = manager::setWifiOnly,
                        )
                        SettingsDivider()
                        SwitchRow(
                            "看完自动删除",
                            policy.autoDeleteWatched,
                            embedded = true,
                            icon = AppIcons.Close,
                            iconTint = SettingTint.downloads,
                            onChange = manager::setAutoDeleteWatched,
                        )
                        SettingsDivider()
                        SwitchRow(
                            "自动下载新集",
                            policy.autoDownloadEnabled,
                            embedded = true,
                            icon = AppIcons.Refresh,
                            iconTint = SettingTint.downloads,
                            onChange = manager::setAutoDownloadEnabled,
                        )
                        if (autoDownloadRuleCount > 0) {
                            SettingsDivider()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column {
                                    Text("追更保留数量", style = AppTypography.body.medium, color = palette.text)
                                    Text(
                                        "$autoDownloadRuleCount 条规则 · 每季最多保留",
                                        style = AppTypography.caption.regular,
                                        color = palette.sub2,
                                    )
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    motionItems(listOf(1, 3, 5, 10)) { count ->
                                        DownloadChip(
                                            label = count.toString(),
                                            active = policy.autoDownloadItemLimit == count,
                                            role = Role.RadioButton,
                                            onClickLabel = "每季保留 $count 集",
                                            onClick = { manager.setAutoDownloadItemLimit(count) },
                                        )
                                    }
                                }
                            }
                            SettingsDivider()
                            SettingRow(
                                title = "清除追更规则",
                                value = "$autoDownloadRuleCount 条 ›",
                                embedded = true,
                                onClick = manager::clearAutoDownloadRules,
                            )
                        }
                        SettingsDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column {
                                Text("同时下载", style = AppTypography.body.medium, color = palette.text)
                                Text("1–3 个任务", style = AppTypography.caption.regular, color = palette.sub2)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                motionItems((1..3).toList()) { count ->
                                    DownloadChip(
                                        label = count.toString(),
                                        active = policy.maxConcurrentDownloads == count,
                                        role = Role.RadioButton,
                                        onClickLabel = "同时下载 $count 个任务",
                                        onClick = { manager.setMaxConcurrentDownloads(count) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The three bars that exist only for part of the page's life share one lazy item, and
            // each carries its own gap inside its reveal.
            //
            // They stay composed so they can collapse rather than being dropped, which is what made
            // the first download look like it shoved the page. One item rather than three because a
            // collapsed AnimatedVisibility is zero-height but still claims the list's 14dp of
            // Arrangement.spacedBy: as separate items, the commonest state of the page — a queue
            // with nothing selected — would carry two permanent gaps it has no rows for.
            //
            // A plain item rather than a motionItem: its height is already being animated from the
            // inside, and animateItem's spring would spend every one of those frames chasing a
            // tween it cannot catch.
            item(key = "download-action-bars") {
                Column(Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = items.isNotEmpty(),
                        enter = downloadRevealEnter(vertical = true),
                        exit = downloadRevealExit(vertical = true),
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            motionItems(DownloadFilter.entries) { value ->
                                val active = filter == value
                                DownloadChip(
                                    label = value.label,
                                    active = active,
                                    role = Role.RadioButton,
                                    onClickLabel = "筛选${value.label}下载",
                                    onClick = { filter = value },
                                )
                            }
                            // Sorting belongs with filtering: both decide what the list under them
                            // looks like, and it used to sit in another card's header where it read
                            // as a label rather than a control.
                            motionItem {
                                DownloadChip(
                                    label = "排序 · ${sort.label}",
                                    active = false,
                                    role = Role.Button,
                                    onClickLabel = "更改排序，当前${sort.label}",
                                    onClick = {
                                        sort =
                                            DownloadSort.entries[
                                                (DownloadSort.entries.indexOf(sort) + 1) % DownloadSort.entries.size,
                                            ]
                                    },
                                )
                            }
                        }
                    }

                    // Queue-wide actions, only while there is a queue to act on.
                    AnimatedVisibility(
                        visible = canPauseAll || canResumeAll,
                        enter = downloadRevealEnter(vertical = true),
                        exit = downloadRevealExit(vertical = true),
                    ) {
                        val queueActions =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = DownloadBarGap,
                                    start = Dimens.pageHorizontal,
                                    end = Dimens.pageHorizontal,
                                )
                        if (largeText) {
                            Column(queueActions, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BatchAction("全部暂停", Modifier.fillMaxWidth(), enabled = canPauseAll) {
                                    manager.pauseAll()
                                }
                                BatchAction("全部继续/重试", Modifier.fillMaxWidth(), enabled = canResumeAll) {
                                    manager.resumeAll()
                                }
                            }
                        } else {
                            Row(queueActions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BatchAction("全部暂停", Modifier.weight(1f), enabled = canPauseAll) {
                                    manager.pauseAll()
                                }
                                BatchAction("全部继续/重试", Modifier.weight(1f), enabled = canResumeAll) {
                                    manager.resumeAll()
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = selectedItems.isNotEmpty(),
                        enter = downloadRevealEnter(vertical = true),
                        exit = downloadRevealExit(vertical = true),
                    ) {
                        val batchSurface =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = DownloadBarGap,
                                    start = Dimens.pageHorizontal,
                                    end = Dimens.pageHorizontal,
                                ).glass(GlassShapes.card, palette.card2, palette.border)
                                .padding(10.dp)
                        if (largeText) {
                            Column(
                                batchSurface,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BatchAction("暂停", Modifier.fillMaxWidth()) {
                                    manager.pauseMany(selectedItems.map(OfflineMedia::id))
                                }
                                BatchAction("继续/重试", Modifier.fillMaxWidth()) {
                                    manager.resumeMany(selectedItems.map(OfflineMedia::id))
                                }
                                BatchAction("删除", Modifier.fillMaxWidth(), danger = true) {
                                    notice = "已删除 ${selectedItems.size} 项下载"
                                    manager.removeMany(selectedItems.map(OfflineMedia::id))
                                    selected = emptySet()
                                }
                            }
                        } else {
                            Row(
                                batchSurface,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BatchAction("暂停", Modifier.weight(1f)) {
                                    manager.pauseMany(selectedItems.map(OfflineMedia::id))
                                }
                                BatchAction("继续/重试", Modifier.weight(1f)) {
                                    manager.resumeMany(selectedItems.map(OfflineMedia::id))
                                }
                                BatchAction("删除", Modifier.weight(1f), danger = true) {
                                    notice = "已删除 ${selectedItems.size} 项下载"
                                    manager.removeMany(selectedItems.map(OfflineMedia::id))
                                    selected = emptySet()
                                }
                            }
                        }
                    }
                }
            }

            if (shown.isEmpty()) {
                motionItem {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 52.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(AppIcons.Download, null, tint = palette.hint, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when {
                                indexStatus == OfflineIndexStatus.Loading -> "正在读取下载记录…"
                                indexStatus == OfflineIndexStatus.Failed -> "请检查存储后重新打开应用"
                                items.isEmpty() -> "还没有下载任务\n在详情页选择下载后会出现在这里"
                                else -> "当前筛选没有任务"
                            },
                            style = AppTypography.body.regular,
                            color = palette.hint,
                        )
                    }
                }
            } else {
                motionItems(shown, key = { it.id }, contentType = { "download-task" }) { item ->
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
                        onRemove = {
                            manager.remove(item.id)
                            selected = selected - item.id
                        },
                        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                    )
                }
            }
        }

        // The batch bar is gone the moment the rows it acted on are, so the page would
        // otherwise answer a delete of twelve files with nothing at all. The offline manager
        // publishes no completion of its own, so the confirmation is posted where the call is.
        ActionToast(
            message = notice,
            onDismiss = { notice = null },
            modifier = Modifier.padding(bottom = TabBarInset),
        )
    }
}

/**
 * What the queue is doing right now, in one line and with the zeros left out.
 *
 * Counters that are zero are not information — they are five things to read before finding
 * out there is nothing to read. An idle page says so in three characters instead.
 */
internal fun downloadSummaryLine(summary: OfflineQueueSummary): String {
    if (summary.total == 0) return "还没有下载任务"
    val parts =
        buildList {
            if (summary.active > 0) add("${summary.active} 进行中")
            if (summary.paused > 0) add("${summary.paused} 已暂停")
            if (summary.failed > 0) add("${summary.failed} 失败")
            if (summary.retryScheduled > 0) add("${summary.retryScheduled} 待重试")
            if (summary.completed > 0) {
                add("${summary.completed} 已完成 · ${formatDownloadBytes(summary.completedBytes)}")
            }
        }
    return parts.joinToString(" · ").ifEmpty { "${summary.total} 项" }
}

/**
 * Controls that only exist while there is something to act on.
 *
 * They used to be present or absent between two frames, so choosing a filter or the first
 * selection made the page twitch. Now the surrounding row grows or collapses around them while
 * they fade. On [Motion.DISCLOSURE], which is what every other expand and collapse in the app
 * takes, so two of them on screen at once finish together. 减弱动态效果 keeps the state change
 * and drops the movement by running the same transition in zero time, rather than leaving an
 * animation the setting exists to remove.
 */
@Composable
private fun downloadRevealEnter(vertical: Boolean): EnterTransition {
    val duration = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.DISCLOSURE
    val fade = fadeIn(tween(duration, easing = Motion.Curve))
    return if (vertical) {
        fade + expandVertically(tween(duration, easing = Motion.Curve))
    } else {
        fade + expandHorizontally(tween(duration, easing = Motion.Curve))
    }
}

/** The closing half of [downloadRevealEnter]. */
@Composable
private fun downloadRevealExit(vertical: Boolean): ExitTransition {
    val duration = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.DISCLOSURE
    val fade = fadeOut(tween(duration, easing = Motion.Curve))
    return if (vertical) {
        fade + shrinkVertically(tween(duration, easing = Motion.Curve))
    } else {
        fade + shrinkHorizontally(tween(duration, easing = Motion.Curve))
    }
}

/** Filter and sort controls share one chip so the row reads as one set of controls. */
@Composable
private fun DownloadChip(
    label: String,
    active: Boolean,
    role: Role,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    Text(
        label,
        style = if (active) AppTypography.body.strong else AppTypography.body.medium,
        // Selection is a colour change, and every other chip in the app eases it — this one
        // cut between the two states, which on a filter row reads as the chips being redrawn.
        color = selectionColor(if (active) accent else palette.body),
        maxLines = 1,
        modifier =
            Modifier
                .pressable(role = role, onClickLabel = onClickLabel, onClick = onClick)
                .touchTarget()
                .then(if (role == Role.RadioButton) Modifier.semantics { selected = active } else Modifier)
                .glass(
                    GlassShapes.chip,
                    selectionColor(if (active) accent.copy(alpha = 0.13f) else palette.card2),
                    selectionColor(if (active) accent.copy(alpha = 0.28f) else palette.border),
                ).padding(horizontal = 13.dp, vertical = 7.dp),
    )
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
        color =
            when {
                !enabled -> palette.hint
                danger -> Semantic.Error
                else -> accent
            },
        textAlign = TextAlign.Center,
        modifier =
            modifier
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
        modifier
            .fillMaxWidth()
            .pressable(
                onClick =
                    if (selectionMode) {
                        onToggleSelected
                    } else {
                        {
                            if (item.playable) {
                                onPlay()
                            } else if (item.status ==
                                DownloadStatus.Downloading
                            ) {
                                onPause()
                            } else {
                                onResume()
                            }
                        }
                    },
            ).heightIn(min = MinTouchTarget)
            .glass(
                GlassShapes.card,
                if (selected) accent.copy(alpha = 0.10f) else palette.card,
                if (selected) accent.copy(alpha = 0.30f) else palette.border,
            ).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Text(
                    if (selected) "✓" else "○",
                    style = AppTypography.section.strong,
                    color = if (selected) accent else palette.sub2,
                )
                Spacer(Modifier.size(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    downloadStatusText(item),
                    modifier =
                        Modifier.lightOnChange(
                            item.status,
                            if (item.status == DownloadStatus.Completed) LightEffect.Converge else LightEffect.Node,
                            emitWhen = item.status != DownloadStatus.Failed,
                        ),
                    style = AppTypography.caption.medium,
                    color =
                        when {
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
                    modifier =
                        Modifier
                            .padding(start = 6.dp)
                            .pressable(onClickLabel = "删除下载", onClick = onRemove)
                            .touchTarget()
                            .size(28.dp)
                            .padding(7.dp),
                )
            }
        }
        DownloadProgressTrack(item = item, accent = accent, trackColor = palette.border)
    }
}

/**
 * The 4dp track under a transfer. While bytes are moving a highlight flows along the filled
 * part, so a stalled download and a busy one no longer look the same; the fill itself eases
 * to each new value instead of stepping. On completion the track draws in to the left and
 * leaves a single green point, then that row simply has no track — rows that were already
 * complete when the list opened never show either.
 */
@Composable
private fun DownloadProgressTrack(
    item: OfflineMedia,
    accent: Color,
    trackColor: Color,
) {
    val completed = item.status == DownloadStatus.Completed
    val initiallyCompleted = remember { completed }
    if (initiallyCompleted) return
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress =
        animateFloatAsState(
            targetValue = item.progress.coerceIn(0f, 1f),
            animationSpec = Motion.settle(reduceMotion),
            label = "downloadProgress",
        )
    val collapse =
        animateFloatAsState(
            targetValue = if (completed) 1f else 0f,
            animationSpec = tween(if (reduceMotion) 0 else Motion.DOWNLOAD_COMPLETE, easing = Motion.Curve),
            label = "downloadComplete",
        )
    val flowing = item.status == DownloadStatus.Downloading && !reduceMotion
    val flow =
        rememberDecorativePhase(
            enabled = flowing,
            periodMillis = Motion.DOWNLOAD_FLOW,
            label = "downloadFlow",
        )
    Box(
        Modifier.fillMaxWidth().height(4.dp).drawWithCache {
            var cachedWidth = Float.NaN
            var cachedOutline: Outline? = null
            val path = Path()
            onDrawBehind {
                val amount = collapse.value.coerceIn(0f, 1f)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val geometry = downloadTrackGeometry(size.width, progress.value, amount, rtl)
                if (geometry.trackWidth > 0f) {
                    if (cachedWidth != geometry.trackWidth) {
                        cachedWidth = geometry.trackWidth
                        val outline =
                            AppShapes.track.createOutline(
                                Size(cachedWidth, size.height),
                                layoutDirection,
                                this,
                            )
                        cachedOutline = outline
                        path.reset()
                        when (outline) {
                            is Outline.Generic -> path.addPath(outline.path)
                            is Outline.Rectangle -> path.addRect(outline.rect)
                            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                        }
                    }
                    translate(left = geometry.trackLeft) {
                        drawOutline(checkNotNull(cachedOutline), trackColor)
                        clipPath(path) {
                            val fillLeft = geometry.fillLeft - geometry.trackLeft
                            drawRect(accent, Offset(fillLeft, 0f), Size(geometry.fillWidth, size.height))
                            if (flowing && geometry.fillWidth > 0f) {
                                val band = geometry.fillWidth * 0.35f
                                val head = fillLeft - band + (geometry.fillWidth + 2f * band) * flow.value
                                clipRect(left = fillLeft, right = fillLeft + geometry.fillWidth) {
                                    drawRect(
                                        Brush.horizontalGradient(
                                            0f to Color.Transparent,
                                            0.5f to Color.White.copy(alpha = 0.38f),
                                            1f to Color.Transparent,
                                            startX = head - band,
                                            endX = head,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                // The original completion dot occupies the 4dp start box and scales about its centre.
                val radius = size.height / 2f
                val centerX = if (rtl) size.width - radius else radius
                if (amount > 0f) drawCircle(Brand.Online, radius * amount, Offset(centerX, radius), alpha = amount)
            }
        },
    )
}

private fun downloadStatusText(item: OfflineMedia): String =
    when (item.status) {
        DownloadStatus.Queued -> item.error ?: if (item.nextRetryAt > 0L) "等待自动重试" else "等待下载"
        DownloadStatus.WaitingForWifi -> "等待 Wi-Fi"
        DownloadStatus.Downloading -> "${formatDownloadBytes(
            item.downloadedBytes,
        )} / ${formatDownloadBytes(item.totalBytes)}"
        DownloadStatus.Paused -> "已暂停 · ${formatDownloadBytes(item.downloadedBytes)}"
        DownloadStatus.Completed ->
            item.error?.let { "已完成 · $it" }
                ?: "已完成 · ${formatDownloadBytes(item.downloadedBytes)}"
        DownloadStatus.Failed -> item.error ?: "下载失败，可点按重试"
    }

internal fun formatDownloadBytes(value: Long): String =
    when {
        value >= 1024L * 1024L * 1024L -> "${(value / 1024.0 / 1024.0 / 1024.0 * 10).toInt() / 10.0} GB"
        value >= 1024L * 1024L -> "${value / 1024L / 1024L} MB"
        value >= 1024L -> "${value / 1024L} KB"
        else -> "$value B"
    }
