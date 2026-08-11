package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.SkipMode
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.shadow

/**
 * The player's settings panel and the tabs inside it.
 *
 * Split out of `PlayerControls` because it is a different kind of thing: the controls are a
 * layer over the picture that has to stay out of the way, this is a list of choices that
 * only exists once someone has asked for it. Nothing here runs while the film plays.
 */

internal enum class Tab(val label: String) {
    Playback("播放"),
    Tracks("音轨"),
    Picture("画面"),
    Danmaku("弹幕"),
    Cast("投屏"),
    Advanced("高级"),
}

/**
 * Settings panel — `right:120px; bottom:70px; width:230px`, `rgba(255,255,255,.92)`,
 * `radius:18px`, `padding:6px 0 12px`, `0 20px 50px -12px rgba(30,40,70,.3)`.
 */
@Composable
internal fun SettingsPanel(
    tab: Tab,
    state: PlaybackState,
    speeds: List<Float>,
    engineOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    castDevices: List<Pair<String, String>>,
    castingDeviceId: String?,
    castDiscovering: Boolean,
    castError: String?,
    danmaku: DanmakuPanelState,
    danmakuActions: DanmakuPanelActions,
    onOpenDanmakuSearch: () -> Unit,
    onOpenDanmakuSend: () -> Unit,
    onTab: (Tab) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    subtitleControls: SubtitleControlState,
    subtitleActions: SubtitleControlActions,
    remoteSubtitles: RemoteSubtitlePanelState,
    remoteSubtitleActions: RemoteSubtitleActions,
    onSelectAudio: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    filled: Boolean,
    onToggleFill: () -> Unit,
    onSelectEngine: (Int) -> Unit,
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
    onDismiss: () -> Unit,
) {
    val tabs = buildList {
        add(Tab.Playback)
        if (state.subtitleTracks.isNotEmpty() || state.audioTracks.size > 1) add(Tab.Tracks)
        add(Tab.Picture)
        add(Tab.Danmaku)
        add(Tab.Cast)
        add(Tab.Advanced)
    }
    val shape = AppShapes.sheet

    // Dismiss catcher only — the old full-screen `rgba(0,0,0,.35)` scrim dimmed the film
    // itself every time a track list opened. The panel earns its separation from its own
    // material and shadow instead, so the picture behind it stays untouched.
    Box(Modifier.fillMaxSize().noRippleClickable(onDismiss))
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                // Sits directly above the chip row that opens it, on the same right edge.
                .padding(end = 22.dp, bottom = 84.dp)
                .width(248.dp)
                .shadow(Shadows.playerSheet, shape)
                .glass(
                    shape = shape,
                    fill = PlayerTokens.drawerFillLandscape,
                    border = Color.White.copy(alpha = 0.20f),
                )
                .noRippleClickable { }
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            // Segmented tab row. The pill alone carries the active state; the 2px rule
            // underneath it was a second signal saying the same thing.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEach { entry ->
                    val active = entry == tab
                    Text(
                        entry.label,
                        style = if (active) AppTypography.body.strong else AppTypography.body.medium,
                        color = if (active) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.58f)
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .glass(
                                shape = GlassShapes.thumb,
                                fill = if (active) {
                                    Color.White.copy(alpha = 0.18f)
                                } else {
                                    Color.Transparent
                                },
                                border = if (active) {
                                    Color.White.copy(alpha = 0.26f)
                                } else {
                                    null
                                },
                            )
                            .noRippleClickable { onTab(entry) }
                            .padding(vertical = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            )

            // `padding:10px 14px 2px; max-height:150px`.
            Column(
                Modifier
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (tab) {
                    Tab.Danmaku -> DanmakuTab(
                        state = danmaku,
                        actions = danmakuActions,
                        onOpenSearch = onOpenDanmakuSearch,
                        onOpenSend = onOpenDanmakuSend,
                    )

                    Tab.Tracks -> {
                        if (state.subtitleTracks.isNotEmpty()) {
                            GroupLabel("字幕")
                            OptionRow(
                                "关闭",
                                state.subtitleTracks.none { it.selected },
                                onClick = { onSelectSubtitle(EngineTrack.OFF) },
                            )
                            state.subtitleTracks.forEach { track ->
                                OptionRow(track.label, track.selected, onClick = { onSelectSubtitle(track.id) })
                            }
                            GroupLabel("字幕时间偏移")
                            listOf(-5_000L, -2_000L, 0L, 2_000L, 5_000L).forEach { offset ->
                                val label = when {
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
                            GroupLabel("第三方字幕")
                            OptionRow(
                                if (remoteSubtitles.loading) "正在搜索中文字幕…" else "搜索中文字幕",
                                false,
                                onClick = remoteSubtitleActions.onSearch,
                            )
                            remoteSubtitles.results.forEach { result ->
                                OptionRow(
                                    label = listOf(result.label, result.detail)
                                        .filter(String::isNotBlank)
                                        .joinToString(" · "),
                                    selected = remoteSubtitles.downloadingId == result.id,
                                    onClick = { remoteSubtitleActions.onDownload(result.id) },
                                )
                            }
                            remoteSubtitles.message?.let { message ->
                                Text(message, style = AppTypography.caption.medium, color = Color.White.copy(alpha = 0.68f))
                            }
                        }
                        if (state.audioTracks.isNotEmpty()) {
                            GroupLabel("音轨")
                            state.audioTracks.forEach { track ->
                                OptionRow(track.label, track.selected, onClick = { onSelectAudio(track.id) })
                            }
                        }
                    }

                    Tab.Playback -> {
                        // 剧集列表与画面比例都在顶栏有常驻圆钮，这里不再重复列一遍；
                        // 只留顶栏没有的入口。
                        GroupLabel("播放")
                        OptionRow("锁定控制", false, onClick = onLock)
                        OptionRow("手势说明", false, onClick = onOpenGestureHelp)
                        OptionRow(
                            if (watch.connected) {
                                "一起看 · ${watch.roomCode.orEmpty()}"
                            } else {
                                "一起看"
                            },
                            watch.connected,
                            onClick = onOpenWatchTogether,
                        )

                        // A single file is not a choice, so the group only appears once
                        // the library actually holds more than one copy of this title.
                        if (versions.size > 1) {
                            GroupLabel("版本")
                            versions.forEach { (id, label) ->
                                OptionRow(label, id == selectedVersionId, onClick = { onSelectVersion(id) })
                            }
                        }

                        GroupLabel("播放速度")
                        speeds.forEach { speed ->
                            OptionRow(speedLabel(speed), speed == state.speed, onClick = { onSpeed(speed) })
                        }

                    }

                    Tab.Advanced -> {
                        GroupLabel("高级播放")
                        // Only for a series: an opening belongs to the show, and there is
                        // nothing sensible to hang a film's times off. Setting a boundary
                        // from where playback already is beats typing seconds at a
                        // fullscreen landscape keyboard — 我的 has the numeric editor for
                        // when a value needs nudging afterwards.
                        if (skip.seriesName != null) {
                            GroupLabel("片头片尾 · 点按设为当前进度")
                            val here = (state.positionMs / 1000).coerceAtLeast(0L)
                            // 片尾 is kept as a distance from the end, so what gets stored
                            // is how much of the episode is left — the same tap, counted
                            // from the other side. Zero when the duration is not known yet,
                            // which reads as "unset" rather than "starts at the very end".
                            val leftFromHere = ((state.durationMs - state.positionMs) / 1000)
                                .coerceAtLeast(0L)
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
                                // Setting a boundary is one tap, so clearing one has to be
                                // too: without this the only way back from a mistimed tap
                                // was to wipe all three and re-enter the others.
                                actionLabel = "取消".takeIf { skip.introStartSeconds > 0L },
                                onAction = {
                                    skipActions.onSetTimes(0L, skip.introEndSeconds, skip.creditsLeadSeconds)
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
                                    skipActions.onSetTimes(skip.introStartSeconds, skip.introEndSeconds, 0L)
                                },
                            )
                            // Three answers, not a switch: "don't skip automatically" and
                            // "don't offer it at all" are different requests, and the second
                            // used to be reachable only by deleting the times.
                            GroupLabel("到达片头片尾时")
                            SegmentedRow(
                                options = SkipMode.entries.map { it.label },
                                selectedIndex = SkipMode.entries.indexOf(skip.mode),
                                onSelect = { skipActions.onSelectMode(SkipMode.entries[it]) },
                            )
                            // Also offered for a half-entered intro, which is exactly when
                            // starting over is most likely to be what's wanted.
                            if (skip.anySet) {
                                OptionRow(
                                    "清除《${skip.seriesName}》的设置",
                                    false,
                                    onClick = { skipActions.onSetTimes(0L, 0L, 0L) },
                                )
                            }
                        }

                        if (engineOptions.isNotEmpty() || transcodeLabel != null) {
                            GroupLabel("播放器内核")
                        }
                        engineOptions.forEachIndexed { index, (label, selected) ->
                            OptionRow(label, selected, onClick = { onSelectEngine(index) })
                        }
                        if (transcodeLabel != null) {
                            OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                        }

                        GroupLabel("诊断")
                        val diagnostics = state.diagnostics
                        GroupLabel("实时播放信息")
                        DiagnosticRow("内核", diagnostics.engine.ifBlank { "未知" })
                        DiagnosticRow("解码器", diagnostics.decoder)
                        DiagnosticRow("播放方式", diagnostics.playMethod)
                        DiagnosticRow(
                            "画面",
                            buildString {
                                append(if (state.videoHeight > 0) "${state.videoHeight}P" else "未知分辨率")
                                if (diagnostics.frameRate > 0f) {
                                    append(" · ")
                                    append(diagnostics.frameRate.asFrameRate())
                                }
                            },
                        )
                        DiagnosticRow("视频编码", diagnostics.videoCodec)
                        DiagnosticRow("当前码率", diagnostics.bitrateBitsPerSecond.asBitrate())
                        DiagnosticRow("网络速度", diagnostics.networkBitsPerSecond.asBitrate())
                        DiagnosticRow(
                            "缓冲",
                            "${diagnostics.bufferedDurationMs / 1000.0f}s · ${diagnostics.bufferEvents} 次",
                        )
                        DiagnosticRow("丢帧", "${diagnostics.droppedFrames} 帧")

                        // The room's own state, next to the playback state it is driving.
                        // When 一起看 misbehaves the question is always the same — am I
                        // still connected, who is in charge, how many of us are there —
                        // and until now the only answer was the one-line banner.
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

                    Tab.Picture -> {
                        GroupLabel("画面")
                        OptionRow(if (filled) "填充屏幕" else "适应画面", filled, onClick = onToggleFill)
                        if (transcodeLabel != null) {
                            GroupLabel("兼容播放")
                            OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                        }
                    }

                    Tab.Cast -> {
                        GroupLabel("局域网投屏设备")
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
}
