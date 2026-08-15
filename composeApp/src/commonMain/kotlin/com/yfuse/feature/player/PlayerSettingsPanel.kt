package com.yfuse.feature.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.SkipMode
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.rememberAccentColorsForSurface

/**
 * Single-purpose playback popups. The playback page chooses one kind per button; there is
 * deliberately no tab strip that can turn the popup back into a combined settings drawer.
 *
 * Split out of `PlayerControls` because it is a different kind of thing: the controls are a
 * layer over the picture that has to stay out of the way, this is a list of choices that
 * only exists once someone has asked for it. Nothing here runs while the film plays.
 */

internal enum class SettingsPanelKind {
    Tracks,
    Danmaku,
    Cast,
    More,
}

/** The playback page exposes subtitle and audio as two independent controls. */
internal enum class TrackPanelMode {
    Subtitle,
    Audio,
}

private enum class AdvancedPage {
    Root,
    Skip,
    Engine,
    Media,
}

/** Compact function popup; long choices scroll inside without turning into a screen drawer. */
@Composable
internal fun SettingsPanel(
    kind: SettingsPanelKind,
    state: PlaybackState,
    engineOptions: List<Pair<String, Boolean>>,
    qualityOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    castDevices: List<Pair<String, String>>,
    castingDeviceId: String?,
    castDiscovering: Boolean,
    castError: String?,
    castStatus: String?,
    castPosition: String?,
    castCapabilities: String?,
    danmaku: DanmakuPanelState,
    danmakuActions: DanmakuPanelActions,
    onOpenDanmakuSearch: () -> Unit,
    onOpenDanmakuSend: () -> Unit,
    onSelectSubtitle: (String) -> Unit,
    subtitleControls: SubtitleControlState,
    subtitleActions: SubtitleControlActions,
    remoteSubtitles: RemoteSubtitlePanelState,
    remoteSubtitleActions: RemoteSubtitleActions,
    onSelectAudio: (String) -> Unit,
    sleepTimer: SleepTimerState,
    sleepTimerActions: SleepTimerActions,
    onSelectEngine: (Int) -> Unit,
    onSelectQuality: (Int) -> Unit,
    onTranscode: () -> Unit,
    onDiscoverCast: () -> Unit,
    onCastTo: (String) -> Unit,
    onStopCast: () -> Unit,
    onLock: () -> Unit,
    onOpenGestureHelp: () -> Unit,
    watch: WatchRoomState,
    onOpenWatchTogether: () -> Unit,
    versions: List<Pair<String, String>>,
    selectedVersionId: String?,
    onSelectVersion: (String) -> Unit,
    skip: SkipSegmentState,
    skipActions: SkipSegmentActions,
    trackPanelMode: TrackPanelMode = TrackPanelMode.Subtitle,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedPage by remember(kind) { mutableStateOf(AdvancedPage.Root) }

    PlayerPopupPanel(onDismiss = onDismiss, modifier = modifier) {
        // The list takes whatever height the drawer has left instead of the old fixed
        // 210dp window, which scrolled a short slot inside a mostly empty screen.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (kind) {
                SettingsPanelKind.Danmaku ->
                    DanmakuTab(
                        state = danmaku,
                        actions = danmakuActions,
                        onOpenSearch = onOpenDanmakuSearch,
                        onOpenSend = onOpenDanmakuSend,
                    )

                SettingsPanelKind.Tracks -> {
                    if (
                        trackPanelMode == TrackPanelMode.Subtitle &&
                        state.subtitleTracks.isNotEmpty()
                    ) {
                        GroupLabel("主字幕")
                        OptionRow(
                            "关闭",
                            state.subtitleTracks.none { it.selected },
                            onClick = { onSelectSubtitle(EngineTrack.OFF) },
                        )
                        state.subtitleTracks.forEach { track ->
                            OptionRow(track.label, track.selected, onClick = { onSelectSubtitle(track.id) })
                        }
                        GroupLabel("副字幕")
                        if (subtitleControls.secondarySupported) {
                            OptionRow(
                                "关闭",
                                subtitleControls.secondaryTrackId == null,
                                onClick = { subtitleActions.onSecondaryTrack(EngineTrack.OFF) },
                            )
                            state.subtitleTracks.forEach { track ->
                                OptionRow(
                                    track.label,
                                    subtitleControls.secondaryTrackId == track.id,
                                    onClick = { subtitleActions.onSecondaryTrack(track.id) },
                                )
                            }
                        } else {
                            Text(
                                subtitleControls.secondaryUnavailableReason
                                    ?: "当前播放器内核不支持双字幕。",
                                style = AppTypography.caption.medium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                        GroupLabel("字幕时间偏移")
                        listOf(-5_000L, -2_000L, 0L, 2_000L, 5_000L).forEach { offset ->
                            val label =
                                when {
                                    offset < 0L -> "提前 ${-offset / 1000} 秒"
                                    offset > 0L -> "延后 ${offset / 1000} 秒"
                                    else -> "同步"
                                }
                            OptionRow(
                                label,
                                subtitleControls.offsetMs == offset,
                                onClick = { subtitleActions.onOffset(offset) },
                            )
                        }
                        GroupLabel("字幕大小")
                        listOf(0.8f to "小", 1f to "标准", 1.25f to "大", 1.5f to "特大")
                            .forEach { (scale, label) ->
                                OptionRow(
                                    label,
                                    subtitleControls.scale == scale,
                                    onClick = { subtitleActions.onScale(scale) },
                                )
                            }
                        GroupLabel("HDR 字幕亮度")
                        listOf(0.4f to "40%", 0.6f to "60%", 0.8f to "80%", 1f to "100%")
                            .forEach { (brightness, label) ->
                                OptionRow(
                                    label,
                                    subtitleControls.brightness == brightness,
                                    onClick = { subtitleActions.onBrightness(brightness) },
                                )
                            }
                    } else if (trackPanelMode == TrackPanelMode.Subtitle) {
                        Text(
                            "当前版本没有可用字幕，可继续搜索第三方字幕。",
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.68f),
                        )
                    }
                    if (trackPanelMode == TrackPanelMode.Subtitle) {
                        GroupLabel("第三方字幕")
                        OptionRow(
                            if (remoteSubtitles.loading) "正在搜索中文字幕…" else "搜索中文字幕",
                            false,
                            onClick = remoteSubtitleActions.onSearch,
                        )
                        remoteSubtitles.results.forEach { result ->
                            OptionRow(
                                label =
                                    listOf(result.label, result.detail)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · "),
                                selected = remoteSubtitles.downloadingId == result.id,
                                onClick = { remoteSubtitleActions.onDownload(result.id) },
                            )
                        }
                        remoteSubtitles.message?.let { message ->
                            Text(
                                message,
                                style = AppTypography.caption.medium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                    } else if (state.audioTracks.isNotEmpty()) {
                        state.audioTracks.forEach { track ->
                            OptionRow(
                                track.label,
                                track.selected,
                                onClick = { onSelectAudio(track.id) },
                            )
                        }
                    } else {
                        Text(
                            "当前版本没有可切换的音轨。",
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.68f),
                        )
                    }
                }

                SettingsPanelKind.More -> {
                    val diagnostics = state.diagnostics
                    when (advancedPage) {
                        AdvancedPage.Root -> {
                            GroupLabel("播放控制")
                            OptionRow("锁定控制", false, onClick = onLock)
                            OptionRow("手势说明", false, onClick = onOpenGestureHelp)
                            if (watch.available || watch.connected) {
                                OptionRow(
                                    if (watch.connected) {
                                        "一起看 · ${watch.roomCode.orEmpty()}"
                                    } else {
                                        "一起看"
                                    },
                                    watch.connected,
                                    onClick = onOpenWatchTogether,
                                )
                            }
                            if (versions.size > 1) {
                                GroupLabel("播放版本")
                                versions.forEach { (id, label) ->
                                    OptionRow(
                                        label,
                                        id == selectedVersionId,
                                        onClick = { onSelectVersion(id) },
                                    )
                                }
                            }
                            GroupLabel("睡眠定时")
                            SleepTimerOption.entries.forEach { option ->
                                OptionRow(
                                    option.label,
                                    sleepTimer.selected == option,
                                    onClick = { sleepTimerActions.onSelect(option) },
                                )
                            }
                            if (qualityOptions.isNotEmpty()) {
                                GroupLabel("播放画质")
                                qualityOptions.forEachIndexed { index, (label, selected) ->
                                    OptionRow(label, selected, onClick = { onSelectQuality(index) })
                                }
                            }
                            GroupLabel("高级播放")
                            if (skip.seriesName != null) {
                                OptionRow(
                                    label = "片头片尾",
                                    selected = false,
                                    onClick = { advancedPage = AdvancedPage.Skip },
                                    detailLabel =
                                        if (skip.mode == SkipMode.Button) "显示跳过" else skip.mode.label,
                                )
                            }
                            if (engineOptions.isNotEmpty() || transcodeLabel != null) {
                                OptionRow(
                                    label = "播放内核",
                                    selected = false,
                                    onClick = { advancedPage = AdvancedPage.Engine },
                                    detailLabel =
                                        engineOptions.firstOrNull { it.second }?.first
                                            ?: diagnostics.engine.ifBlank { "默认" },
                                )
                            }
                            OptionRow(
                                label = "媒体信息",
                                selected = false,
                                onClick = { advancedPage = AdvancedPage.Media },
                                detailLabel = diagnostics.playMethod.ifBlank { "实时诊断" },
                            )
                        }

                        AdvancedPage.Skip -> {
                            PopupBackLabel("片头片尾") { advancedPage = AdvancedPage.Root }
                            val here = (state.positionMs / 1000).coerceAtLeast(0L)
                            val leftFromHere =
                                ((state.durationMs - state.positionMs) / 1000).coerceAtLeast(0L)
                            GroupLabel("点按设为当前进度")
                            OptionRow(
                                label = skipBoundaryLabel("片头开始", skip.introStartSeconds),
                                selected = skip.introStartSeconds > 0L,
                                onClick = {
                                    skipActions.onSetTimes(
                                        here,
                                        skip.introEndSeconds,
                                        skip.creditsLeadSeconds,
                                    )
                                },
                                actionLabel = "取消".takeIf { skip.introStartSeconds > 0L },
                                onAction = {
                                    skipActions.onSetTimes(
                                        0L,
                                        skip.introEndSeconds,
                                        skip.creditsLeadSeconds,
                                    )
                                },
                            )
                            OptionRow(
                                label = skipBoundaryLabel("片头结束", skip.introEndSeconds),
                                selected = skip.introEndSeconds > 0L,
                                onClick = {
                                    skipActions.onSetTimes(
                                        skip.introStartSeconds,
                                        here,
                                        skip.creditsLeadSeconds,
                                    )
                                },
                                actionLabel = "取消".takeIf { skip.introEndSeconds > 0L },
                                onAction = {
                                    skipActions.onSetTimes(
                                        skip.introStartSeconds,
                                        0L,
                                        skip.creditsLeadSeconds,
                                    )
                                },
                            )
                            OptionRow(
                                label = skipCreditsLabel(skip.creditsLeadSeconds),
                                selected = skip.creditsLeadSeconds > 0L,
                                onClick = {
                                    skipActions.onSetTimes(
                                        skip.introStartSeconds,
                                        skip.introEndSeconds,
                                        leftFromHere,
                                    )
                                },
                                actionLabel = "取消".takeIf { skip.creditsLeadSeconds > 0L },
                                onAction = {
                                    skipActions.onSetTimes(
                                        skip.introStartSeconds,
                                        skip.introEndSeconds,
                                        0L,
                                    )
                                },
                            )
                            GroupLabel("到达片头片尾时")
                            SegmentedRow(
                                options =
                                    SkipMode.entries.map { mode ->
                                        if (mode == SkipMode.Button) "显示跳过" else mode.label
                                    },
                                selectedIndex = SkipMode.entries.indexOf(skip.mode),
                                onSelect = { skipActions.onSelectMode(SkipMode.entries[it]) },
                            )
                            if (skip.anySet) {
                                OptionRow(
                                    "清除《${skip.seriesName}》的设置",
                                    false,
                                    onClick = { skipActions.onSetTimes(0L, 0L, 0L) },
                                )
                            }
                        }

                        AdvancedPage.Engine -> {
                            PopupBackLabel("播放内核") { advancedPage = AdvancedPage.Root }
                            engineOptions.forEachIndexed { index, (label, selected) ->
                                OptionRow(label, selected, onClick = { onSelectEngine(index) })
                            }
                            if (transcodeLabel != null) {
                                OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                            }
                        }

                        AdvancedPage.Media -> {
                            PopupBackLabel("媒体信息") { advancedPage = AdvancedPage.Root }
                            DiagnosticRow("内核", diagnostics.engine.ifBlank { "未知" })
                            DiagnosticRow("解码器", diagnostics.decoder)
                            DiagnosticRow("播放方式", diagnostics.playMethod)
                            DiagnosticRow("所选画质", diagnostics.requestedQuality)
                            DiagnosticRow("设备链路", diagnostics.deviceOutputCapabilities)
                            DiagnosticRow(
                                "画面",
                                buildString {
                                    append(
                                        when {
                                            diagnostics.videoWidth > 0 && state.videoHeight > 0 ->
                                                "${diagnostics.videoWidth} × ${state.videoHeight}"
                                            state.videoHeight > 0 -> "${state.videoHeight}P"
                                            else -> "未知分辨率"
                                        },
                                    )
                                    if (diagnostics.frameRate > 0f) {
                                        append(" · ")
                                        append(diagnostics.frameRate.asFrameRate())
                                    }
                                },
                            )
                            DiagnosticRow("视频编码", diagnostics.videoCodec)
                            DiagnosticRow("动态范围", diagnostics.dynamicRange.ifBlank { "未知" })
                            DiagnosticRow("视频输出", diagnostics.videoOutput)
                            DiagnosticRow("音频", diagnostics.audioFormat.ifBlank { "未知" })
                            DiagnosticRow("音频输出", diagnostics.audioOutput)
                            DiagnosticRow("当前码率", diagnostics.bitrateBitsPerSecond.asBitrate())
                            DiagnosticRow("网络速度", diagnostics.networkBitsPerSecond.asBitrate())
                            DiagnosticRow(
                                "缓冲",
                                "${diagnostics.bufferedDurationMs / 1000.0f}s · ${diagnostics.bufferEvents} 次",
                            )
                            DiagnosticRow("丢帧", "${diagnostics.droppedFrames} 帧")
                            diagnostics.fallbackReason?.takeIf(String::isNotBlank)?.let { reason ->
                                DiagnosticRow("降级原因", reason)
                            }
                            if (watch.connected || watch.roomCode != null) {
                                GroupLabel("一起看")
                                DiagnosticRow(
                                    "状态",
                                    when {
                                        watch.reconnecting -> "重连中"
                                        watch.connected -> "已连接"
                                        else -> "未连接"
                                    },
                                )
                                DiagnosticRow("房间", watch.roomCode ?: "—")
                                DiagnosticRow("身份", if (watch.isHost) "房主" else "参与者")
                                DiagnosticRow("在线", "${watch.participantCount} 人")
                            }
                        }
                    }
                }

                SettingsPanelKind.Cast -> {
                    GroupLabel("局域网投屏设备")
                    castStatus?.let { DiagnosticRow("状态", it) }
                    castPosition?.let { DiagnosticRow("远端进度", it) }
                    castCapabilities?.let { DiagnosticRow("远端能力", it) }
                    if (castDiscovering) {
                        Text(
                            "正在发现 DLNA 设备…",
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    castDevices.forEach { (id, name) ->
                        OptionRow(name, id == castingDeviceId, onClick = { onCastTo(id) })
                    }
                    if (castingDeviceId != null) {
                        OptionRow("停止投屏", false, onClick = onStopCast)
                    }
                    if (castError != null) {
                        Text(
                            castError,
                            style = AppTypography.caption.medium,
                            color = DarkPalette.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    OptionRow("重新扫描", false, onClick = onDiscoverCast)
                }
            }
        }
    }
}

@Composable
private fun PopupBackLabel(
    title: String,
    onBack: () -> Unit,
) {
    Text(
        "‹  $title",
        style = AppTypography.body.strong,
        color = Color.White.copy(alpha = 0.90f),
        modifier =
            Modifier
                .noRippleClickable(onBack)
                .padding(horizontal = 3.dp, vertical = 6.dp),
    )
}

@Composable
internal fun SourcePickerPopup(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    PlayerPopupPanel(
        onDismiss = onDismiss,
        modifier = modifier,
        compact = true,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${options.size} 个可用",
                style = AppTypography.caption.strong,
                color = Color.White.copy(alpha = 0.86f),
            )
            Text(
                "切换不改变播放进度",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.44f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            options.forEach { (id, label) ->
                val selected = id == selectedId
                Column(
                    Modifier
                        .widthIn(min = 116.dp, max = 150.dp)
                        .glass(
                            shape = AppShapes.card,
                            fill = if (selected) accent.container else Color.White.copy(alpha = 0.05f),
                            border = if (selected) accent.border else Color.White.copy(alpha = 0.08f),
                        ).noRippleClickable { onSelect(id) }
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                ) {
                    Text(
                        label,
                        style = AppTypography.body.strong,
                        color = if (selected) accent.accent else Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                    Text(
                        if (selected) "当前线路" else "可用线路",
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.44f),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        Text(
            "播放失败时会自动切换到下一条可用线路",
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 9.dp),
        )
    }
}

/** Dedicated speed popup opened from the playback page; no unrelated settings are mixed in. */
@Composable
internal fun SpeedPickerPopup(
    speeds: List<Float>,
    selectedSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerPopupPanel(
        onDismiss = onDismiss,
        modifier = modifier,
        compact = true,
    ) {
        CompactChoiceGrid(
            options = speeds.map(::speedLabel),
            selectedIndex = speeds.indexOf(selectedSpeed),
            columns = 4,
            onSelect = { onSelect(speeds[it]) },
        )
        Text(
            "选择后立即生效",
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CompactChoiceGrid(
    options: List<String>,
    selectedIndex: Int,
    columns: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(columns).forEachIndexed { rowIndex, rowOptions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowOptions.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * columns + columnIndex
                    val selected = index == selectedIndex
                    Text(
                        label,
                        style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
                        color = if (selected) accent.accent else Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .glass(
                                    shape = AppShapes.pill,
                                    fill = if (selected) accent.container else Color.White.copy(alpha = 0.045f),
                                    border = if (selected) accent.border else Color.White.copy(alpha = 0.07f),
                                ).noRippleClickable { onSelect(index) }
                                .padding(vertical = 9.dp),
                    )
                }
                repeat(columns - rowOptions.size) {
                    Spacer(Modifier.weight(1f).size(1.dp))
                }
            }
        }
    }
}
