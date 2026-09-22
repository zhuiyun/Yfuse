package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaServerKind
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.detail.DetailIntent
import com.yfuse.feature.detail.DetailState
import com.yfuse.feature.detail.EpisodeProgressAction
import com.yfuse.feature.detail.EpisodeSelectionPreset
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope
import kotlinx.coroutines.launch

/** Which secondary sheet the detail screen currently shows. */
internal enum class TvDetailSheet { More, Organization, AiringCalendar, EpisodeProgress }

/**
 * The remaining actions from the phone's 更多 sheet.
 *
 * The phone reaches these through a bottom sheet with a scroll. A television gets one focusable
 * list: the actions are few and each is a single press, so nesting them further would only add
 * D-pad travel.
 */
@Composable
internal fun TvDetailMoreDialog(
    component: DetailComponent,
    state: DetailState,
    detail: MediaDetail,
    focusMemory: TvUiFocusMemory,
    onOpenSheet: (TvDetailSheet) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusScope = "detail:more"
    val firstRequester = remember { FocusRequester() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val isSeries = detail.seriesId != null || state.seasons.isNotEmpty()
    val isPlex = state.server?.kind == MediaServerKind.Plex

    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }

    GlassDialog(onDismiss = onDismiss, maxWidth = 720.dp, contentPadding = 26.dp) {
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("更多操作", color = TvOnSurface, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            status?.let { Text(it, color = TvAccent, fontSize = 15.sp) }

            if (isSeries) {
                TvSettingRow(
                    title = "播出日历",
                    value = "",
                    stableId = "more:calendar",
                    focusMemory = focusMemory,
                    onClick = { onOpenSheet(TvDetailSheet.AiringCalendar) },
                    icon = AppIcons.WatchCalendar,
                    focusScope = focusScope,
                    subtitle = "这部剧接下来的更新时间",
                    focusRequester = firstRequester,
                )
                TvSettingRow(
                    title = "加入追剧",
                    value = "",
                    stableId = "more:follow",
                    focusMemory = focusMemory,
                    onClick = {
                        busy = true
                        scope.launch {
                            component
                                .toggleSeriesFollow(detail)
                                .fold(
                                    onSuccess = { following ->
                                        status = if (following) "已加入追剧" else "已取消追剧"
                                    },
                                    onFailure = { status = "追剧设置失败：${it.message ?: "请重试"}" },
                                )
                            busy = false
                        }
                    },
                    icon = AppIcons.Bell,
                    focusScope = focusScope,
                    subtitle = "更新时出现在追剧日历里",
                    enabled = !busy,
                )
                TvSettingRow(
                    title = "剧集进度管理",
                    value = "",
                    stableId = "more:progress",
                    focusMemory = focusMemory,
                    onClick = {
                        component.store.accept(DetailIntent.OpenProgressManager)
                        onOpenSheet(TvDetailSheet.EpisodeProgress)
                    },
                    icon = AppIcons.EpisodeList,
                    focusScope = focusScope,
                    subtitle = "批量标记整季已看或未看",
                )
            }

            TvSettingRow(
                title = "加入合集或播放列表",
                value = "",
                stableId = "more:organization",
                focusMemory = focusMemory,
                onClick = {
                    component.store.accept(DetailIntent.LoadOrganizationContainers)
                    onOpenSheet(TvDetailSheet.Organization)
                },
                icon = AppIcons.Bookmark,
                focusScope = focusScope,
                focusRequester = if (isSeries) null else firstRequester,
            )
            TvSettingRow(
                title = "刷新服务器元数据",
                value = "",
                stableId = "more:refresh",
                focusMemory = focusMemory,
                onClick = {
                    busy = true
                    scope.launch {
                        component
                            .refreshServerMetadata(detail)
                            .fold(
                                onSuccess = { status = "已请求服务器重新识别" },
                                onFailure = { status = "刷新失败：${it.message ?: "请重试"}" },
                            )
                        busy = false
                    }
                },
                icon = AppIcons.Refresh,
                focusScope = focusScope,
                subtitle = "让服务器重新抓取封面、简介与演职员",
                enabled = !busy,
            )
            if (isPlex) {
                TvSettingRow(
                    title = "分析 Plex 媒体",
                    value = "",
                    stableId = "more:analyze",
                    focusMemory = focusMemory,
                    onClick = {
                        busy = true
                        scope.launch {
                            component
                                .analyzeServerMetadata(detail)
                                .fold(
                                    onSuccess = { status = "已请求 Plex 重新分析" },
                                    onFailure = { status = "分析失败：${it.message ?: "请重试"}" },
                                )
                            busy = false
                        }
                    },
                    icon = AppIcons.Info,
                    focusScope = focusScope,
                    subtitle = "重新读取码率、时长与音视频轨道",
                    enabled = !busy,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    label = "关闭",
                    stableId = "more:close",
                    focusScope = focusScope,
                    focusMemory = focusMemory,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun TvOrganizationDialog(
    component: DetailComponent,
    state: DetailState,
    focusMemory: TvUiFocusMemory,
    onDismiss: () -> Unit,
) {
    val focusScope = "detail:organization"
    val firstRequester = remember { FocusRequester() }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.organizationContainers.size) {
        if (state.organizationContainers.isNotEmpty()) firstRequester.requestFocusWhenAttached()
    }

    GlassDialog(onDismiss = onDismiss, maxWidth = 720.dp, contentPadding = 26.dp) {
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("加入合集或播放列表", color = TvOnSurface, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            status?.let { Text(it, color = TvAccent, fontSize = 15.sp) }
            state.organizationError?.let { Text(it, color = TvDanger, fontSize = 15.sp) }

            when {
                state.organizationLoading ->
                    Text("正在读取…", color = TvOnSurfaceMuted, fontSize = 15.sp)
                state.organizationContainers.isEmpty() ->
                    Text(
                        "这台服务器上还没有可写入的合集或播放列表。",
                        color = TvOnSurfaceMuted,
                        fontSize = 15.sp,
                    )
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.organizationContainers.forEachIndexed { index, container ->
                            item(key = "organization:${container.id}") {
                                TvSettingRow(
                                    title = container.title,
                                    value = if (container.editable) "加入" else "只读",
                                    stableId = "organization:${container.id}",
                                    focusMemory = focusMemory,
                                    onClick = {
                                        component.store.accept(
                                            DetailIntent.AddToOrganizationContainer(container.id),
                                        )
                                        status = "已加入《${container.title}》"
                                    },
                                    icon = AppIcons.Bookmark,
                                    focusScope = focusScope,
                                    subtitle = container.itemCount?.let { "$it 个条目" },
                                    enabled = container.editable,
                                    focusRequester = if (index == 0) firstRequester else null,
                                )
                            }
                        }
                    }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    label = "关闭",
                    stableId = "organization:close",
                    focusScope = focusScope,
                    focusMemory = focusMemory,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun TvAiringCalendarDialog(
    component: DetailComponent,
    detail: MediaDetail,
    focusMemory: TvUiFocusMemory,
    onDismiss: () -> Unit,
) {
    val focusScope = "detail:airing"
    var days by remember { mutableStateOf<List<CalendarDay>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(detail.id) {
        loading = true
        component
            .loadSeriesAiringCalendar(detail, onPreview = { preview -> days = preview })
            .fold(
                onSuccess = { days = it },
                onFailure = { error = it.message ?: "无法读取播出日历" },
            )
        loading = false
        closeRequester.requestFocusWhenAttached()
    }

    GlassDialog(onDismiss = onDismiss, maxWidth = 760.dp, contentPadding = 26.dp) {
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("播出日历", color = TvOnSurface, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(detail.title, color = TvOnSurfaceMuted, fontSize = 15.sp)

            when {
                error != null -> Text(error.orEmpty(), color = TvDanger, fontSize = 15.sp)
                loading && days.isEmpty() -> Text("正在读取…", color = TvOnSurfaceMuted, fontSize = 15.sp)
                days.isEmpty() -> Text("暂时没有已公布的播出安排。", color = TvOnSurfaceMuted, fontSize = 15.sp)
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        days.forEach { day ->
                            item(key = "airing-day:${day.date}") {
                                Text(
                                    day.date,
                                    color = TvAccent,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            day.entries.forEach { entry ->
                                item(
                                    key =
                                        "airing:${day.date}:${entry.episode.seasonNumber}" +
                                            ":${entry.episode.episodeNumber}",
                                ) {
                                    Text(
                                        buildString {
                                            append("S${entry.episode.seasonNumber}")
                                            append("E${entry.episode.episodeNumber}")
                                            entry.episode.episodeTitle?.let { append("  $it") }
                                        },
                                        color = TvOnSurface,
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                        }
                    }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    label = "关闭",
                    stableId = "airing:close",
                    focusScope = focusScope,
                    focusMemory = focusMemory,
                    onClick = onDismiss,
                    focusRequester = closeRequester,
                )
            }
        }
    }
}

@Composable
internal fun TvEpisodeProgressDialog(
    component: DetailComponent,
    state: DetailState,
    focusMemory: TvUiFocusMemory,
    onDismiss: () -> Unit,
) {
    val focusScope = "detail:progress"
    val store = component.store
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }

    GlassDialog(onDismiss = onDismiss, maxWidth = 780.dp, contentPadding = 26.dp) {
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("剧集进度管理", color = TvOnSurface, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "已选 ${state.progressSelection.size} / ${state.episodes.size} 集",
                color = TvOnSurfaceMuted,
                fontSize = 15.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EpisodeSelectionPreset.entries.forEachIndexed { index, preset ->
                    TvActionButton(
                        label = preset.tvLabel(),
                        stableId = "progress:preset:${preset.name}",
                        focusScope = focusScope,
                        focusMemory = focusMemory,
                        onClick = { store.accept(DetailIntent.SelectProgressEpisodes(preset)) },
                        focusRequester = if (index == 0) firstRequester else null,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.episodes.forEach { episode ->
                    item(key = "progress:${episode.id}") {
                        TvSettingRow(
                            title = episode.name,
                            value = if (episode.id in state.progressSelection) "已选" else "",
                            stableId = "progress:episode:${episode.id}",
                            focusMemory = focusMemory,
                            onClick = { store.accept(DetailIntent.ToggleProgressEpisode(episode.id)) },
                            icon = if (episode.played) AppIcons.Check else AppIcons.EpisodeList,
                            focusScope = focusScope,
                            subtitle = if (episode.played) "已看过" else "未看",
                            selected = episode.id in state.progressSelection,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EpisodeProgressAction.entries.forEach { action ->
                    TvActionButton(
                        label = action.tvLabel(),
                        stableId = "progress:action:${action.name}",
                        focusScope = focusScope,
                        focusMemory = focusMemory,
                        onClick = { store.accept(DetailIntent.ApplyEpisodeProgress(action)) },
                        primary = action == EpisodeProgressAction.MarkWatched,
                    )
                }
                TvActionButton(
                    label = "关闭",
                    stableId = "progress:close",
                    focusScope = focusScope,
                    focusMemory = focusMemory,
                    onClick = {
                        store.accept(DetailIntent.CloseProgressManager)
                        onDismiss()
                    },
                )
            }
            if (state.progressSaving) {
                Text("正在写入服务器…", color = TvAccent, fontSize = 14.sp)
            }
        }
    }
}

private fun EpisodeSelectionPreset.tvLabel(): String =
    when (this) {
        EpisodeSelectionPreset.All -> "全选"
        EpisodeSelectionPreset.Watched -> "选已看"
        EpisodeSelectionPreset.Unwatched -> "选未看"
        EpisodeSelectionPreset.Invert -> "反选"
    }

private fun EpisodeProgressAction.tvLabel(): String =
    when (this) {
        EpisodeProgressAction.MarkWatched -> "标记已看"
        EpisodeProgressAction.MarkUnwatched -> "标记未看"
        EpisodeProgressAction.Reset -> "清除进度"
    }
