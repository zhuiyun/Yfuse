package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.OfflineDownloadQuality
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.estimateOfflineDownloadBytes
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.daysBetweenIso
import com.yfuse.core.util.isoWeekdayLabel
import com.yfuse.feature.profile.formatDownloadBytes

@Composable
internal fun SeriesAiringCalendarDialog(
    title: String,
    days: List<CalendarDay>,
    loading: Boolean,
    error: String?,
    identityCandidates: List<TmdbSeriesIdentityCandidate> = emptyList(),
    followed: Boolean = false,
    reminderMode: CalendarReminderMode = CalendarReminderMode.Off,
    remindBeforeMinutes: Int = 30,
    onSelectIdentity: (TmdbSeriesIdentityCandidate) -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onSetReminder: (CalendarReminderMode, Int) -> Unit = { _, _ -> },
    onRebindIdentity: () -> Unit = {},
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val today = currentIsoDate()
    val episodeCount = days.sumOf { it.entries.size }
    val officialSchedule =
        days.asSequence()
            .flatMap { it.entries.asSequence() }
            .map { it.episode }
            .firstOrNull { it.scheduleAuthority == AiringScheduleAuthority.Official }
    val scheduleSubtitle =
        when {
            officialSchedule != null ->
                buildList {
                    add("$episodeCount 集")
                    add("官方会员日历")
                    officialSchedule.airTime?.let { time ->
                        add(
                            if (officialSchedule.timeZoneId == "Asia/Shanghai") {
                                "北京时间 $time"
                            } else {
                                "$time (${officialSchedule.timeZoneId ?: "原播时区"})"
                            },
                        )
                    }
                    officialSchedule.platforms.takeIf { it.isNotEmpty() }?.joinToString("/")?.let(::add)
                }.joinToString(" · ")
            episodeCount > 0 -> "$episodeCount 集 · 按原产地播出日期"
            else -> "按原产地播出日期"
        }
    GlassDialog(liquidButtons = false, onDismiss = onDismiss, scrollable = false) {
        OverlayHeader(
            title = "$title · 播出日历",
            subtitle = scheduleSubtitle,
            onClose = onDismiss,
        )
        if (identityCandidates.isEmpty()) {
            OverlayOptionRow(
                label = if (followed) "已加入追剧" else "加入追剧",
                description = if (followed) "在追剧中心优先显示该剧" else "关注排期和入库状态",
                selected = followed,
                onClick = onToggleFollow,
            )
            if (followed) {
                Text(
                    "更新提醒",
                    style = AppTypography.caption.strong,
                    color = palette.sub2,
                    modifier = Modifier.padding(top = 6.dp),
                )
                listOf(
                    CalendarReminderMode.Off to "关闭",
                    CalendarReminderMode.BeforeAndAtBroadcast to "播出前和播出时",
                    CalendarReminderMode.AtBroadcast to "播出时",
                    CalendarReminderMode.WhenAvailable to "新入库时",
                ).forEach { (mode, label) ->
                    OverlayOptionRow(
                        label = label,
                        description =
                            if (mode == CalendarReminderMode.BeforeAndAtBroadcast) {
                                "提前 $remindBeforeMinutes 分钟"
                            } else {
                                null
                            },
                        selected = reminderMode == mode,
                        onClick = { onSetReminder(mode, remindBeforeMinutes) },
                    )
                }
                if (reminderMode == CalendarReminderMode.BeforeAndAtBroadcast) {
                    Text(
                        "提前时间",
                        style = AppTypography.caption.strong,
                        color = palette.sub2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(10, 30, 60, 120, 360)) { minutes ->
                            OverlayOptionRow(
                                label = if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时",
                                selected = remindBeforeMinutes == minutes,
                                onClick = {
                                    onSetReminder(
                                        CalendarReminderMode.BeforeAndAtBroadcast,
                                        minutes,
                                    )
                                },
                                modifier = Modifier.width(92.dp),
                            )
                        }
                    }
                }
            }
            OverlayOptionRow(
                label = "重新匹配剧集",
                description = "排期不对时，重新选择 TMDB 条目",
                selected = false,
                onClick = onRebindIdentity,
            )
        }
        when {
            identityCandidates.isNotEmpty() -> {
                Text(
                    "媒体库缺少可靠的 TMDB 标识，请选择一次；选择结果会保存到本机。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                identityCandidates.forEach { candidate ->
                    OverlayOptionRow(
                        label = candidate.title,
                        description = listOfNotNull(candidate.year?.toString(), "TMDB ${candidate.tmdbId}").joinToString(" · "),
                        selected = false,
                        onClick = { onSelectIdentity(candidate) },
                    )
                }
            }

            loading && days.isEmpty() ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("正在读取该剧播出安排…", style = AppTypography.body.regular, color = palette.sub)
                }

            error != null && days.isEmpty() -> {
                Text(
                    error,
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                OverlayOptionRow(label = "重新加载", selected = false, onClick = onRetry)
            }

            days.isEmpty() ->
                Text(
                    "TMDB 暂未提供该剧当前播出季的集数日期。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(vertical = 20.dp),
                )

            else ->
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(days, key = { it.date }) { day ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    seriesCalendarDayLabel(day.date, today),
                                    style = AppTypography.body.strong,
                                    color = palette.text,
                                )
                                Text(
                                    "${day.date} · ${isoWeekdayLabel(day.date)}",
                                    style = AppTypography.caption.regular,
                                    color = palette.sub2,
                                )
                            }
                            day.entries.forEach { entry ->
                                val (status, tint) = seriesCalendarStatus(entry.status, palette.error, palette.sub2)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .flatGlass(GlassShapes.chip, palette.card2, palette.border)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        entry.episode.episodeLabel,
                                        style = AppTypography.body.medium,
                                        color = palette.text,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(status, style = AppTypography.caption.strong, color = tint)
                                }
                            }
                        }
                    }
                }
        }
    }
}

internal fun reminderModeLabel(
    mode: CalendarReminderMode,
    beforeMinutes: Int = 30,
): String =
    when (mode) {
        CalendarReminderMode.Off -> "关闭"
        CalendarReminderMode.BeforeAndAtBroadcast -> "提前 $beforeMinutes 分钟和播出时"
        CalendarReminderMode.AtBroadcast -> "播出时"
        CalendarReminderMode.WhenAvailable -> "检测到新入库时"
    }

internal fun nextReminderMode(mode: CalendarReminderMode): CalendarReminderMode =
    when (mode) {
        CalendarReminderMode.Off -> CalendarReminderMode.BeforeAndAtBroadcast
        CalendarReminderMode.BeforeAndAtBroadcast -> CalendarReminderMode.AtBroadcast
        CalendarReminderMode.AtBroadcast -> CalendarReminderMode.WhenAvailable
        CalendarReminderMode.WhenAvailable -> CalendarReminderMode.Off
    }

internal fun seriesCalendarDayLabel(
    date: String,
    today: String,
): String =
    when (val delta = daysBetweenIso(today, date)) {
        0 -> "今天"
        1 -> "明天"
        -1 -> "昨天"
        else -> if (delta > 0) "$delta 天后" else "${-delta} 天前"
    }

private fun seriesCalendarStatus(
    status: LibraryStatus,
    errorColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color,
): Pair<String, androidx.compose.ui.graphics.Color> =
    when (status) {
        LibraryStatus.Unaired -> "未播出" to mutedColor
        LibraryStatus.Missing -> "未入库" to errorColor
        LibraryStatus.Available -> "可播放" to Brand.Online
        LibraryStatus.InProgress -> "观看中" to Brand.Online
        LibraryStatus.Watched -> "已看" to mutedColor
        LibraryStatus.Unknown -> "仅供参考" to mutedColor
    }

@Composable
internal fun OfflineDownloadDialog(
    detail: MediaDetail,
    episodes: List<Episode>,
    selectedVersionId: String?,
    onConfirm: (OfflineDownloadSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val versions = detail.versions
    var versionId by remember(detail.id, selectedVersionId) {
        mutableStateOf(selectedVersionId ?: versions.firstOrNull()?.id)
    }
    var quality by remember(detail.id) { mutableStateOf(OfflineDownloadQuality.Original) }
    var subtitleIndex by remember(detail.id) { mutableStateOf<Int?>(null) }
    var batchMode by remember(detail.id) { mutableStateOf(OfflineBatchMode.Current) }
    var autoDownloadNewEpisodes by remember(detail.id) { mutableStateOf(false) }
    val selectedVersion = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()
    val selectedSubtitle = selectedVersion?.subtitleTracks?.firstOrNull { it.index == subtitleIndex }
    val batchCount =
        when (batchMode) {
            OfflineBatchMode.Current -> 1
            OfflineBatchMode.Season -> episodes.size.coerceAtLeast(1)
            OfflineBatchMode.Unwatched -> episodes.count { !it.played }
        }
    val selection =
        OfflineDownloadSelection(
            batchMode = batchMode,
            mediaSourceId = selectedVersion?.id,
            quality = quality,
            subtitleStreamIndex = selectedSubtitle?.index,
            subtitleCodec = selectedSubtitle?.codec,
            subtitleLanguage = selectedSubtitle?.language,
            subtitleDefault = selectedSubtitle?.default == true,
            subtitleForced = selectedSubtitle?.forced == true,
            autoDownloadNewEpisodes = autoDownloadNewEpisodes,
        )
    val totalEstimate =
        estimateOfflineDownloadBytes(
            currentItemId = detail.id,
            currentTitle = detail.title,
            currentRuntimeMinutes = detail.runtimeMinutes,
            currentVersions = detail.versions,
            seasonEpisodes = episodes,
            selection = selection,
        )

    GlassDialog(liquidButtons = false, onDismiss = onDismiss) {
        OverlayHeader(
            title = "智能离线",
            subtitle =
                buildString {
                    append("下载前确认版本、画质和字幕")
                    append(" · ")
                    append(totalEstimate?.let { "预计 ${formatDownloadBytes(it)}" } ?: "空间待服务器确认")
                },
            onClose = onDismiss,
        )

        Text("范围", style = AppTypography.caption.strong, color = palette.sub2)
        OfflineChoiceRow(OfflineBatchMode.entries, batchMode, { it.label }) { batchMode = it }

        if (versions.size > 1) {
            Text("版本", style = AppTypography.caption.strong, color = palette.sub2)
            versions.forEach { version ->
                OverlayOptionRow(
                    label =
                        listOfNotNull(version.name, version.summary.takeIf(String::isNotBlank))
                            .joinToString(" · "),
                    selected = version.id == selectedVersion?.id,
                    onClick = {
                        versionId = version.id
                        subtitleIndex = null
                    },
                )
            }
        }

        Text("画质", style = AppTypography.caption.strong, color = palette.sub2)
        OfflineChoiceRow(OfflineDownloadQuality.entries, quality, { it.label }) { quality = it }

        selectedVersion?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
            Text("字幕", style = AppTypography.caption.strong, color = palette.sub2)
            OverlayOptionRow(
                label = "不下载字幕",
                selected = subtitleIndex == null,
                onClick = { subtitleIndex = null },
            )
            tracks.forEach { track ->
                val index = track.index ?: return@forEach
                OverlayOptionRow(
                    label = track.label,
                    selected = index == subtitleIndex,
                    onClick = { subtitleIndex = index },
                )
            }
        }

        if (detail.seriesId != null) {
            Text("追更", style = AppTypography.caption.strong, color = palette.sub2)
            OverlayOptionRow(
                label = "自动下载后续新集",
                selected = autoDownloadNewEpisodes,
                onClick = { autoDownloadNewEpisodes = !autoDownloadNewEpisodes },
            )
        }

        Text(
            when (batchMode) {
                OfflineBatchMode.Current -> "将加入 1 个下载任务"
                OfflineBatchMode.Season -> "将加入 $batchCount 集；每集自动选择对应媒体源"
                OfflineBatchMode.Unwatched ->
                    if (batchCount == 0) {
                        "本季已全部看完，没有可下载的未看剧集"
                    } else {
                        "将加入 $batchCount 集，已看剧集会跳过"
                    }
            },
            style = AppTypography.caption.regular,
            color = palette.sub2,
            modifier = Modifier.padding(top = 8.dp),
        )
        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "加入下载",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(selection) },
            confirmEnabled =
                (versions.isEmpty() || selectedVersion != null) &&
                    !(batchMode == OfflineBatchMode.Unwatched && batchCount == 0),
        )
    }
}

@Composable
private fun <T> OfflineChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values) { value ->
            OverlayOptionRow(
                label = label(value),
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.width(118.dp),
            )
        }
    }
}

@Composable
internal fun OrganizationContainerDialog(
    containers: List<MediaContainer>,
    loading: Boolean,
    error: String?,
    addingIds: Set<String>,
    addedIds: Set<String>,
    onRetry: () -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(liquidButtons = false, onDismiss = onDismiss, scrollable = false) {
        OverlayHeader(
            title = "加入合集或播放列表",
            subtitle = "使用服务器上已有的容器",
            onClose = onDismiss,
        )
        when {
            loading && containers.isEmpty() ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("正在读取服务器容器…", style = AppTypography.body.regular, color = palette.sub)
                }

            error != null && containers.isEmpty() -> {
                Text(
                    text = error,
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                OverlayOptionRow(label = "重试", selected = false, onClick = onRetry)
            }

            containers.isEmpty() ->
                Text(
                    text = "此服务器没有可用的合集或播放列表。Yfuse 不会偷偷创建替代片单。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )

            else -> {
                if (error != null) {
                    Text(
                        text = error,
                        style = AppTypography.caption.medium,
                        color = palette.sub,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing),
                ) {
                    items(
                        items = containers,
                        key = { "${it.serverId}-${it.kind}-${it.id}" },
                    ) { container ->
                        val added = container.id in addedIds
                        val adding = container.id in addingIds
                        val kind =
                            if (container.kind == MediaContainerKind.BoxSet) {
                                "合集"
                            } else {
                                "播放列表"
                            }
                        OverlayOptionRow(
                            label =
                                buildString {
                                    append(kind)
                                    append(" · ")
                                    append(container.title)
                                    if (adding) append(" · 正在加入…")
                                },
                            selected = added,
                            onClick = { if (!adding && !added) onAdd(container.id) },
                        )
                    }
                }
            }
        }
    }
}
