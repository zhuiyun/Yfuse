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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.ContextualTip
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Section
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.SettingRow
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.SettingsDivider
import com.yfuse.core.designsystem.SwipeActionsRow
import com.yfuse.core.designsystem.SwitchRow
import com.yfuse.core.designsystem.TabBarInset
import com.yfuse.core.designsystem.Tips
import com.yfuse.core.designsystem.ToastAction
import com.yfuse.core.designsystem.UndoWindow
import com.yfuse.core.designsystem.YfChip
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightOnChange
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDecorativePhase
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

/** What a right swipe does to a transfer: 暂停 what moves or waits, 继续 what is paused, 重试 what failed. */
internal enum class DownloadSwipe(
    val label: String,
) {
    Pause("暂停"),
    Resume("继续"),
    Retry("重试"),
}

/** Null for a finished download: there is nothing left to pause or resume. */
internal fun downloadSwipe(status: DownloadStatus): DownloadSwipe? =
    when (status) {
        DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.Downloading -> DownloadSwipe.Pause
        DownloadStatus.Paused -> DownloadSwipe.Resume
        DownloadStatus.Failed -> DownloadSwipe.Retry
        DownloadStatus.Completed -> null
    }

/**
 * 删除下载, 先做，给 5 秒撤销: the rows named here leave the list at once, their files only when the
 * toast has gone. A download can be tens of gigabytes; the delete used to be one tap with no way back.
 */
private class DownloadRemoval(
    val ids: Set<String>,
    val message: String,
)

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
    val accent = LocalAccentColors.current.accent
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val personal =
        remember {
            org.koin.core.context.GlobalContext
                .get()
                .get<com.yfuse.core.personal.PersonalLibraryRepository>()
        }
    val access by personal.policy.collectAsState()
    val allItems by manager.items.collectAsState()
    // The delete waiting on its toast (see [UndoWindow]), and the same change as state: its rows
    // are hidden from the list — and from every count — while it waits.
    val removals = remember { UndoWindow<DownloadRemoval>() }
    var removal by remember { mutableStateOf<DownloadRemoval?>(null) }
    // A fresh toast for every delete: two deletes can read alike, and a toast only re-posts on a
    // new message.
    var removalToast by remember { mutableIntStateOf(0) }
    val hidden = removal?.ids.orEmpty()
    val items =
        remember(allItems, access, hidden) {
            allItems.filter { access.allowsServer(it.serverId) && it.id !in hidden }
        }
    val wifiOnly by manager.wifiOnly.collectAsState()
    val policy by manager.policy.collectAsState()
    val autoDownloadRuleCount by manager.autoDownloadRuleCount.collectAsState()
    val operationError by manager.operationError.collectAsState()
    val indexStatus by manager.indexStatus.collectAsState()
    val pickStorageDirectory =
        rememberOfflineStorageDirectoryPicker { treeUri, label ->
            if (personal.policy.value.canManageServers) manager.setStorageDirectory(treeUri, label)
        }
    var showSettings by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DownloadFilter.All) }
    var sort by remember { mutableStateOf(DownloadSort.Updated) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun commit(change: DownloadRemoval) = manager.removeMany(change.ids.toList())

    // Every delete on this page — a swipe, the row's ×, the batch bar — goes through here.
    fun remove(
        ids: Set<String>,
        message: String,
    ) {
        if (ids.isEmpty()) return
        val change = DownloadRemoval(ids, message)
        removals.hold(change)?.let(::commit)
        removal = change
        removalToast++
        selected = selected - ids
    }

    fun undoRemoval(change: DownloadRemoval) {
        if (removals.undo { it === change } != null) removal = null
    }

    // The toast left — timed out, swiped away, the app sent to the background: the files go now.
    fun settleRemoval() {
        removals.release()?.let(::commit)
        removal = null
    }
    // Leaving the page is the toast leaving too; nothing may stay held behind a closed page.
    DisposableEffect(removals) {
        onDispose { removals.release()?.let(::commit) }
    }

    val shown =
        remember(items, filter, sort) {
            filterAndSortDownloads(items, filter, sort)
        }
    val selectedItems = items.filter { it.id in selected }

    fun removeSelected() = remove(selectedItems.mapTo(linkedSetOf()) { it.id }, "已删除 ${selectedItems.size} 项下载")

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
            motionItem(key = "download-settings-toggle") {
                SettingRow(
                    title = if (showSettings) "收起下载设置" else "下载设置",
                    value = if (access.canManageServers) "Wi-Fi、容量、自动追更" else "请切换至家长资料管理",
                    onClick = { if (access.canManageServers) showSettings = !showSettings },
                )
            }
            if (showSettings && access.canManageServers) {
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
                                value = policy.storageLabel ?: "应用内部存储",
                                embedded = true,
                                onClick = pickStorageDirectory,
                                icon = AppIcons.Download,
                                iconTint = SettingTint.downloads,
                            )
                            if (policy.storageTreeUri != null) {
                                SettingsDivider()
                                SettingRow(
                                    title = "改回内部存储",
                                    value = "仅影响新下载",
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
                                            YfChip(
                                                label = count.toString(),
                                                selected = policy.autoDownloadItemLimit == count,
                                                onClickLabel = "每季保留 $count 集",
                                                onClick = { manager.setAutoDownloadItemLimit(count) },
                                            )
                                        }
                                    }
                                }
                                SettingsDivider()
                                SettingRow(
                                    title = "清除追更规则",
                                    value = "$autoDownloadRuleCount 条",
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
                                        YfChip(
                                            label = count.toString(),
                                            selected = policy.maxConcurrentDownloads == count,
                                            onClickLabel = "同时下载 $count 个任务",
                                            onClick = { manager.setMaxConcurrentDownloads(count) },
                                        )
                                    }
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
                                YfChip(
                                    label = value.label,
                                    selected = active,
                                    onClickLabel = "筛选${value.label}下载",
                                    onClick = { filter = value },
                                )
                            }
                            // Sorting belongs with filtering: both decide what the list under them
                            // looks like, and it used to sit in another card's header where it read
                            // as a label rather than a control.
                            motionItem {
                                YfChip(
                                    label = "排序 · ${sort.label}",
                                    selected = false,
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
                                    manager.pauseMany(items.map { it.id })
                                }
                                BatchAction("全部继续/重试", Modifier.fillMaxWidth(), enabled = canResumeAll) {
                                    manager.resumeMany(items.map { it.id })
                                }
                            }
                        } else {
                            Row(queueActions, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BatchAction("全部暂停", Modifier.weight(1f), enabled = canPauseAll) {
                                    manager.pauseMany(items.map { it.id })
                                }
                                BatchAction("全部继续/重试", Modifier.weight(1f), enabled = canResumeAll) {
                                    manager.resumeMany(items.map { it.id })
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
                                ).glass(AppShapes.card, palette.card2, palette.border)
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
                                BatchAction("删除", Modifier.fillMaxWidth(), danger = true, onClick = ::removeSelected)
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
                                BatchAction("删除", Modifier.weight(1f), danger = true, onClick = ::removeSelected)
                            }
                        }
                    }
                }
            }

            if (indexStatus == OfflineIndexStatus.Failed) {
                // A failed read is not an empty list: say so, and offer the read again.
                motionItem {
                    ErrorState(
                        message = "下载记录未能读取，请检查存储后重试",
                        onRetry = manager::retryIndex,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    )
                }
            } else if (shown.isEmpty()) {
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
                    val removeItem = { remove(setOf(item.id), "已删除「${item.title}」") }
                    // Right: the transfer's own next step. Left: 删除, undoable like the × beside it.
                    // Selecting is its own mode, and a row being ticked does not also swipe.
                    SwipeActionsRow(
                        modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
                        leading =
                            downloadSwipe(item.status)?.let { swipe ->
                                ItemAction(
                                    label = swipe.label,
                                    icon = if (swipe == DownloadSwipe.Pause) AppIcons.Pause else AppIcons.Play,
                                    id = "download.${swipe.name}",
                                ) {
                                    when (swipe) {
                                        DownloadSwipe.Pause -> manager.pause(item.id)
                                        DownloadSwipe.Resume, DownloadSwipe.Retry -> manager.resume(item.id)
                                    }
                                }
                            },
                        trailing =
                            ItemAction(
                                label = "删除",
                                icon = AppIcons.Close,
                                destructive = true,
                                undoable = true,
                                id = "download.remove",
                                onSelect = removeItem,
                            ),
                        enabled = selected.isEmpty(),
                    ) { actions ->
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
                            onRemove = removeItem,
                            modifier = actions,
                        )
                    }
                }
            }
        }

        // Once there are rows to swipe; the first swipe retires it.
        ContextualTip(
            id = Tips.SWIPE_ROW,
            text = "左滑可删除下载，右滑可暂停或继续",
            active = shown.isNotEmpty() && selected.isEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TabBarInset),
        )

        // The rows a delete took are gone at once, so the page would otherwise answer a delete of
        // twelve files with nothing at all; the toast says what went and holds it back for 撤销.
        key(removalToast) {
            val pending = removal
            ActionToast(
                message = pending?.message,
                onDismiss = ::settleRemoval,
                action = pending?.let { change -> ToastAction("撤销") { undoRemoval(change) } },
            )
        }
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

@Composable
private fun BatchAction(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
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
                .glass(AppShapes.chip, palette.card3, palette.border)
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
    val accent = LocalAccentColors.current.accent
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
                AppShapes.card,
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
