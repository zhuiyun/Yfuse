package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.Anime4KMode
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.model.ShortDramaMode
import org.koin.core.context.GlobalContext
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** 更多's levels: the list of destinations, and the page behind each of them. */
internal enum class AdvancedPage {
    Root,
    Playback,
    Engine,
    Media,
    Bookmarks,
    Handoff,
}

/**
 * 更多 — the [AdvancedPage.Root] list and the page behind whichever row was opened.
 *
 * [advancedPage] is the shell's, keyed on the popup kind, so the level is forgotten whenever another
 * key takes the popup over and 更多 always opens on its list.
 */
@Composable
internal fun MorePanel(
    advancedPage: AdvancedPage,
    onAdvancedPage: (AdvancedPage) -> Unit,
    reduceMotion: Boolean,
    state: PlaybackState,
    /** The live timeline, read by 媒体信息 alone; see [SettingsPanel]. */
    playback: State<PlaybackState>,
    /** Bumped by the native disc session; 播放设置 reads it so its disc rows follow that session. */
    discNavigationRevision: State<Long>,
    containerLabel: String?,
    engineOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    onSelectEngine: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResetAdaptiveLearning: () -> Unit,
    /** 播放问题说明与诊断导出; the dialog belongs to the shell, which outlives this page. */
    onOpenProblem: () -> Unit,
    onNextDiscTitle: () -> Unit,
    onNextDiscChapter: () -> Unit,
    onShowDiscMenu: () -> Unit,
    onExternalPlayer: (() -> Unit)?,
    onLock: () -> Unit,
    onOpenGestureHelp: () -> Unit,
    watch: WatchRoomState,
    onOpenWatchTogether: () -> Unit,
    versions: List<Pair<String, String>>,
    selectedVersionId: String?,
    onSelectVersion: (String) -> Unit,
    sleepTimer: SleepTimerState,
    sleepTimerActions: SleepTimerActions,
    ambientLightEnabled: Boolean,
    onToggleAmbientLight: () -> Unit,
    autoNextEnabled: Boolean,
    onToggleAutoNext: () -> Unit,
    /** This series' 短剧模式, or null outside a series. */
    shortDramaMode: ShortDramaMode?,
    onSelectShortDramaMode: (ShortDramaMode) -> Unit,
    bookmarks: PlaybackBookmarkPanelState,
    bookmarkActions: PlaybackBookmarkActions,
) {
    // Going a level deeper and coming back is this panel's own transition. The travel is
    // resolved to pixels up here because a transition spec is not a composable and cannot
    // read the density itself.
    val density = LocalDensity.current
    val pushTravelPx = with(density) { Motion.pushOffset.roundToPx() }
    val popTravelPx = with(density) { Motion.popOffset.roundToPx() }
    val diagnostics = state.diagnostics
    AdvancedPageHost(
        page = advancedPage,
        reduceMotion = reduceMotion,
        pushTravelPx = pushTravelPx,
        popTravelPx = popTravelPx,
    ) { page ->
        when (page) {
            AdvancedPage.Bookmarks -> {
                PopupBackLabel("时间书签") { onAdvancedPage(AdvancedPage.Root) }
                PlaybackBookmarkPanel(bookmarks, bookmarkActions)
            }
            AdvancedPage.Handoff -> {
                PopupBackLabel("设备接力") { onAdvancedPage(AdvancedPage.Root) }
                PlayerDeviceHandoffPanel(watch.connected)
            }
            AdvancedPage.Root -> {
                PopupMenuRow(
                    icon = AppIcons.Cast,
                    title = "设备接力",
                    subtitle = "在同账号的另一台设备继续观看",
                    onClick = { onAdvancedPage(AdvancedPage.Handoff) },
                )
                PopupDivider()
                PopupMenuRow(
                    icon = AppIcons.Bookmark,
                    title = "时间书签",
                    subtitle = "标记片段与备注 · ${bookmarks.items.size} 个",
                    onClick = { onAdvancedPage(AdvancedPage.Bookmarks) },
                )
                PopupDivider()
                PopupMenuRow(
                    icon = AppIcons.PlaybackSource,
                    title = "播放内核",
                    subtitle = "当前：${diagnostics.engine.ifBlank { "等待内核" }}",
                    onClick = { onAdvancedPage(AdvancedPage.Engine) },
                )
                PopupDivider()
                PopupMenuRow(
                    icon = AppIcons.Info,
                    title = "媒体信息",
                    subtitle = diagnostics.playMethod.ifBlank { "实时播放诊断" },
                    onClick = { onAdvancedPage(AdvancedPage.Media) },
                )
                PopupDivider()
                PopupMenuRow(
                    icon = AppIcons.Grid,
                    title = "播放设置",
                    subtitle = "定时与播放控制",
                    onClick = { onAdvancedPage(AdvancedPage.Playback) },
                )
                // On the first page rather than eleven rows into 播放设置: most of what
                // the picture answers to is invisible until someone says so. A remote
                // has no touches to explain.
                if (!LocalTelevisionChrome.current) {
                    PopupDivider()
                    PopupMenuRow(
                        icon = AppIcons.Gesture,
                        title = "手势与快捷键",
                        subtitle = "画面手势、按键长按与键盘快捷键",
                        onClick = overlayAction(onOpenGestureHelp),
                    )
                }
            }

            AdvancedPage.Playback -> {
                PopupBackLabel("播放设置") { onAdvancedPage(AdvancedPage.Root) }
                // Native HDMV state is richer than mpv's edition/chapter properties:
                // it owns menu/angle state and is pushed from libbluray callbacks.
                // Observing the revision the shell collects makes menu availability and
                // activation visible immediately without polling the native session.
                @Suppress("UNUSED_VARIABLE")
                val observedDiscNavigationRevision = discNavigationRevision.value
                val activeDisc = ActiveDiscNavigation.navigation
                val disc =
                    if (ActiveDiscNavigation.isBound && activeDisc.available) {
                        activeDisc
                    } else {
                        state.discNavigation
                    }
                if (disc.available) {
                    GroupLabel("${disc.kind.label}导航")
                    if (disc.effectiveTitleCount > 1) {
                        if (ActiveDiscNavigation.isBound) {
                            GroupLabel("标题 / Playlist")
                            disc.titleOptions.forEach { title ->
                                val authored = title.title?.trim()?.takeIf(String::isNotEmpty)
                                val playlist = title.playlistLabel
                                val authoredIsPlaylist =
                                    authored?.contains(".mpls", ignoreCase = true) == true ||
                                        authored?.contains("mpls/", ignoreCase = true) == true ||
                                        authored?.contains("mpls\\", ignoreCase = true) == true
                                val label =
                                    listOfNotNull(
                                        authored?.takeUnless { authoredIsPlaylist }
                                            ?: playlist
                                            ?: "标题 ${title.index + 1}",
                                        playlist?.takeUnless {
                                            authoredIsPlaylist ||
                                                it == authored
                                        },
                                        "默认".takeIf { title.isDefault },
                                    ).joinToString(" · ")
                                OptionRow(
                                    label = label,
                                    selected = title.index == disc.selectedTitleIndex,
                                    onClick = { ActiveDiscNavigation.selectTitle(title.index) },
                                )
                            }
                        } else {
                            OptionRow(
                                listOfNotNull(
                                    disc.selectedTitle?.label,
                                    "${disc.selectedTitleIndex + 1} / ${disc.effectiveTitleCount}",
                                ).joinToString(" · "),
                                false,
                                onClick = onNextDiscTitle,
                            )
                        }
                    }
                    if (disc.effectiveChapterCount > 1) {
                        if (ActiveDiscNavigation.isBound) {
                            GroupLabel("章节")
                            disc.chapterOptions.forEach { chapter ->
                                OptionRow(
                                    label =
                                        listOfNotNull(chapter.timeLabel, chapter.label)
                                            .joinToString(" · "),
                                    selected = chapter.index == disc.selectedChapterIndex,
                                    onClick = { ActiveDiscNavigation.selectChapter(chapter.index) },
                                )
                            }
                        } else {
                            val chapterPosition =
                                "${disc.selectedChapterIndex + 1} / " +
                                    "${disc.effectiveChapterCount}"
                            OptionRow(
                                listOfNotNull(
                                    disc.selectedChapter?.timeLabel,
                                    disc.selectedChapter?.label,
                                    chapterPosition,
                                ).joinToString(" · "),
                                false,
                                onClick = onNextDiscChapter,
                            )
                        }
                    }
                    if (disc.effectiveAngleCount > 1 && ActiveDiscNavigation.isBound) {
                        GroupLabel("多视角")
                        disc.angleOptions.forEach { angle ->
                            OptionRow(
                                label = angle.label,
                                selected = angle.index == disc.selectedAngleIndex,
                                onClick = { ActiveDiscNavigation.selectAngle(angle.index) },
                            )
                        }
                    }
                    if (disc.menuSupported) {
                        val menuSlow by ActiveDiscNavigation.menuSlow.collectAsState()
                        OptionRow(
                            if (menuSlow) "光盘菜单读取中…" else "打开光盘菜单",
                            disc.menuActive,
                            onClick = {
                                if (
                                    !ActiveDiscNavigation.sendMenuCommand(
                                        com.yfuse.core.playback.PlaybackDiscMenuCommand.ShowMenu,
                                    )
                                ) {
                                    onShowDiscMenu()
                                }
                            },
                        )
                    }
                }
                GroupLabel("Anime4K · YCore SDR 动漫")
                val animePreferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
                val animeMode by animePreferences.anime4KMode.collectAsState()
                val showFrameRate by animePreferences.showFrameRate.collectAsState()
                Anime4KMode.entries.forEach { mode ->
                    OptionRow(
                        mode.label,
                        animeMode == mode,
                        onClick = { animePreferences.setAnime4KMode(mode) },
                    )
                }
                Text(
                    "下次播放生效；HDR 与杜比视界保留原始输出",
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.6f),
                )
                GroupLabel("画面")
                PopupToggleHeader(
                    label = "显示帧率",
                    checked = showFrameRate,
                    onToggle = { animePreferences.setShowFrameRate(!showFrameRate) },
                )
                Text(
                    "显示页面绘制帧率及视频帧率",
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.6f),
                )
                // Stays open: the light is judged against the picture behind the panel.
                PopupToggleHeader(
                    label = "氛围光",
                    checked = ambientLightEnabled,
                    onToggle = onToggleAmbientLight,
                )
                // The same switch as 设置 → 播放; the episode playing follows it at once.
                PopupToggleHeader(
                    label = "自动播放下一集",
                    checked = autoNextEnabled,
                    onToggle = onToggleAutoNext,
                )
                shortDramaMode?.let { mode ->
                    GroupLabel("短剧模式")
                    SegmentedRow(
                        options = ShortDramaMode.entries.map(ShortDramaMode::label),
                        selectedIndex = mode.ordinal,
                        onSelect = { onSelectShortDramaMode(ShortDramaMode.entries[it]) },
                    )
                    Text(
                        "只对本剧生效，同一文件夹里的视频算作一部。自动：竖版画面竖屏播放；" +
                            "短剧：始终竖屏，在画面中间上下滑切集；普通剧集：始终横屏。",
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                // A remote has no touches to lock out.
                if (!LocalTelevisionChrome.current) {
                    OptionRow("锁定控制", false, onClick = overlayAction(onLock))
                }
                onExternalPlayer?.let { open ->
                    OptionRow("使用外部播放器", false, onClick = overlayAction(open))
                }
                if (watch.available || watch.connected) {
                    OptionRow(
                        if (watch.connected) {
                            "一起看 · ${watch.roomCode.orEmpty()}"
                        } else {
                            "一起看"
                        },
                        watch.connected,
                        onClick = overlayAction(onOpenWatchTogether),
                    )
                }
                if (versions.size > 1) {
                    GroupLabel("播放版本")
                    versions.forEach { (id, label) ->
                        OptionRow(
                            label,
                            id == selectedVersionId,
                            onClick = overlayAction { onSelectVersion(id) },
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
                PlayerGestureSettingsSection(animePreferences)
            }

            AdvancedPage.Engine -> {
                PopupBackLabel("播放内核", "仅当前视频 · 切换后重新加载") {
                    onAdvancedPage(AdvancedPage.Root)
                }
                engineOptions.forEachIndexed { index, (label, selected) ->
                    EngineChoiceRow(
                        label = label,
                        selected = selected,
                        onClick = { onSelectEngine(index) },
                    )
                    if (index != engineOptions.lastIndex || transcodeLabel != null) {
                        PopupDivider()
                    }
                }
                if (transcodeLabel != null) {
                    PopupMenuRow(
                        icon = AppIcons.Play,
                        title = transcodeLabel,
                        subtitle = "服务器兼容播放模式",
                        selected = transcodeActive,
                        onClick = onTranscode,
                    )
                }
            }

            AdvancedPage.Media -> {
                // The one page in this panel that is a live readout:
                // it takes the timeline itself so the four that are not
                // stay off it.
                val liveDiagnostics = playback.value.diagnostics
                PopupBackLabel("媒体信息") { onAdvancedPage(AdvancedPage.Root) }
                PopupMenuRow(
                    icon = AppIcons.Info,
                    title = "播放问题说明与诊断导出",
                    onClick = onOpenProblem,
                )
                DiagnosticRow("容器", containerLabel ?: "未知")
                DiagnosticRow(
                    "YCore 管线",
                    liveDiagnostics.plannedRenderPath.ifBlank { "等待规划" },
                )
                DiagnosticRow("运行健康", liveDiagnostics.playbackHealth)
                DiagnosticRow(
                    "A/V 同步",
                    liveDiagnostics.avSyncOffsetMs?.let { offset ->
                        val signed = if (offset > 0L) "+$offset" else offset.toString()
                        "$signed ms · ${liveDiagnostics.avSyncMeasurement}"
                    } ?: liveDiagnostics.avSyncMeasurement,
                )
                DiagnosticRow("功耗估计", liveDiagnostics.powerProfile)
                DiagnosticRow("资源压力", liveDiagnostics.resourcePressure)
                DiagnosticRow("媒体探测", liveDiagnostics.mediaProbe)
                DiagnosticRow("历史基线", liveDiagnostics.performanceBaseline)
                DiagnosticRow(
                    "分辨率",
                    when {
                        liveDiagnostics.videoWidth > 0 && state.videoHeight > 0 ->
                            "${liveDiagnostics.videoWidth} × ${state.videoHeight}"
                        state.videoHeight > 0 -> "${state.videoHeight}P"
                        else -> "未知"
                    },
                )
                DiagnosticRow(
                    "视频",
                    listOf(
                        liveDiagnostics.videoCodec,
                        liveDiagnostics.dynamicRange,
                    ).filter(String::isNotBlank).joinToString(" · "),
                )
                DiagnosticRow("码率", liveDiagnostics.bitrateBitsPerSecond.asBitrate())
                DiagnosticRow("实时网络", liveDiagnostics.networkBitsPerSecond.asBitrate())
                DiagnosticRow(
                    "前向缓冲",
                    maxOf(
                        liveDiagnostics.bufferedDurationMs,
                        liveDiagnostics.sourceBufferedMs,
                    ).asSeconds(),
                )
                DiagnosticRow("帧率", liveDiagnostics.frameRate.asFrameRate())
                DiagnosticRow(
                    "音频",
                    state.audioTracks.firstOrNull { it.selected }?.label
                        ?: liveDiagnostics.audioFormat.ifBlank { "未知" },
                )
                DiagnosticRow(
                    "字幕",
                    state.subtitleTracks.firstOrNull { it.selected }?.label ?: "未加载",
                )
                DiagnosticRow(
                    "播放内核",
                    listOf(
                        liveDiagnostics.engine,
                        liveDiagnostics.decoder,
                    ).filter(String::isNotBlank).joinToString(" · "),
                )
                PopupDivider()
                Text(
                    "●  ${liveDiagnostics.playMethod.ifBlank { "实时播放" }}",
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                )
                liveDiagnostics.fallbackReason?.takeIf(String::isNotBlank)?.let { reason ->
                    DiagnosticRow("降级原因", reason)
                }
                liveDiagnostics.planningReason?.takeIf(String::isNotBlank)?.let { reason ->
                    DiagnosticRow("规划原因", reason)
                }
                PopupDivider()
                PopupMenuRow(
                    icon = AppIcons.Refresh,
                    title = "重置 YCore 学习数据",
                    subtitle = "清除本机故障记忆与性能基线",
                    onClick = onResetAdaptiveLearning,
                )
            }
        }
    }
}

/** Keeps the source Activity mounted while the user chooses a receiver. */
@Composable
private fun PlayerDeviceHandoffPanel(inWatchRoom: Boolean) {
    if (inWatchRoom) {
        DiagnosticRow("设备接力", "请先离开一起看房间，再接力个人播放")
        return
    }
    val controller = remember { runCatching { GlobalContext.get().getOrNull<HandoffController>() }.getOrNull() }
    if (controller == null) {
        DiagnosticRow("设备接力", "此播放环境暂未启用账号接力服务")
        return
    }
    val state by controller.state.collectAsState()
    DiagnosticRow("接力规则", "接收设备确认就绪后，本机才会暂停")
    if (!state.online) DiagnosticRow("连接", state.connectionLabel)
    if (state.busy) {
        state.message?.let { DiagnosticRow("进度", it) }
        OptionRow("取消接力", selected = false, onClick = controller::cancelTransfer)
    } else {
        if (state.online && state.devices.isEmpty()) DiagnosticRow("在线设备", "暂未发现可接收的设备")
        state.devices.forEach { device ->
            PopupMenuRow(
                icon = AppIcons.Cast,
                title = "接力到 ${device.name}",
                subtitle = device.platform,
                onClick = { controller.send(device.sessionId) },
            )
        }
        state.message?.let { DiagnosticRow("结果", it) }
    }
    state.connectionError?.let { DiagnosticRow("连接提示", it) }
    state.error?.let { DiagnosticRow("接力提示", it) }
}

/**
 * 更多's two levels: the list of destinations, and the page behind one of them.
 *
 * The same gesture as a detail page opening, at panel scale — [Motion.PUSH] and [Motion.pushOffset]
 * going deeper, [Motion.POP] and [Motion.popOffset] coming back — so 返回 inside this panel reads
 * as 返回 everywhere else in the app rather than as the contents being replaced.
 */
@Composable
private fun AdvancedPageHost(
    page: AdvancedPage,
    reduceMotion: Boolean,
    pushTravelPx: Int,
    popTravelPx: Int,
    content: @Composable ColumnScope.(AdvancedPage) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        contentKey = { it },
        transitionSpec = {
            advancedPageTransform(
                popping = targetState == AdvancedPage.Root,
                reduceMotion = reduceMotion,
                pushTravelPx = pushTravelPx,
                popTravelPx = popTravelPx,
            )
        },
        modifier = Modifier.fillMaxWidth(),
        label = "player-advanced-page",
    ) { current ->
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            content(current)
        }
    }
}

private fun AnimatedContentTransitionScope<*>.advancedPageTransform(
    popping: Boolean,
    reduceMotion: Boolean,
    pushTravelPx: Int,
    popTravelPx: Int,
): ContentTransform {
    if (reduceMotion) {
        return (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
    }
    val transform =
        if (popping) {
            (
                fadeIn(tween(Motion.POP, easing = Motion.Curve)) +
                    slideInHorizontally(tween(Motion.POP, easing = Motion.Curve)) { -popTravelPx }
            ) togetherWith
                (
                    fadeOut(tween(Motion.POP, easing = Motion.Curve)) +
                        slideOutHorizontally(tween(Motion.POP, easing = Motion.Curve)) { popTravelPx }
                )
        } else {
            (
                fadeIn(tween(Motion.PUSH, easing = Motion.Curve)) +
                    slideInHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { pushTravelPx }
            ) togetherWith
                (
                    // One duration for the whole push, as the pop already does: the outgoing
                    // page used to finish fading a page-length before it finished moving.
                    fadeOut(tween(Motion.PUSH, easing = Motion.Curve)) +
                        slideOutHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { -pushTravelPx / 2 }
                )
        }
    // The two levels are nowhere near the same height, so the surface travels between them
    // instead of cutting — otherwise it changes shape under a page that is still arriving.
    return transform.using(SizeTransform(clip = false) { _, _ -> Motion.settle() })
}

@Composable
private fun PopupBackLabel(
    title: String,
    trailing: String? = null,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹  $title",
            style = AppTypography.body.strong,
            color = Color.White.copy(alpha = 0.90f),
            modifier = Modifier.noRippleClickable(onBack),
        )
        trailing?.let {
            Text(
                it,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.48f),
            )
        }
    }
}

private fun engineDescription(label: String): String =
    when {
        label.contains("EXO", ignoreCase = true) -> "系统解码 · HDR/Dolby Vision"
        label.contains("MDK", ignoreCase = true) -> "画质优先 · 高兼容性"
        label.contains("MPV", ignoreCase = true) -> "格式支持更完整"
        else -> "兼容播放内核"
    }

@Composable
private fun EngineChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val badge = label.substringBefore(' ').take(3).uppercase()
    Row(
        Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick)
            .padding(horizontal = 5.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .glass(
                    shape = AppShapes.thumb,
                    fill = if (selected) accent.container else Color.Transparent,
                    border = if (selected) accent.border else Color.White.copy(alpha = 0.16f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                style = AppTypography.caption.strong,
                color = if (selected) accent.accent else Color.White.copy(alpha = 0.70f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTypography.body.strong, color = Color.White.copy(alpha = 0.92f))
            Text(
                engineDescription(label),
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.52f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .size(24.dp)
                .glass(
                    shape = AppShapes.pill,
                    fill = if (selected) accent.container else Color.Transparent,
                    border = if (selected) accent.border else Color.White.copy(alpha = 0.22f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
