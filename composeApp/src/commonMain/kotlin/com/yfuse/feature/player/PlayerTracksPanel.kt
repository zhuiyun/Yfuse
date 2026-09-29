package com.yfuse.feature.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.motionAwareScrollTo
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 字幕 and 音轨 — the [SettingsPanelKind.Tracks] popup, in whichever of its two modes the key that
 * opened it asked for. [panelScroll] scrolls the whole popup; the selected subtitle is brought into
 * view with it.
 */
@Composable
internal fun TracksPanel(
    trackPanelMode: TrackPanelMode,
    state: PlaybackState,
    /** Read for 自动校准's measured offset only, which is telemetry rather than projection. */
    playback: State<PlaybackState>,
    panelScroll: ScrollState,
    reduceMotion: Boolean,
    onSelectSubtitle: (String) -> Unit,
    subtitleControls: SubtitleControlState,
    subtitleActions: SubtitleControlActions,
    remoteSubtitles: RemoteSubtitlePanelState,
    remoteSubtitleActions: RemoteSubtitleActions,
    onSelectAudio: (String) -> Unit,
    audioControls: AudioControlState,
    audioActions: AudioControlActions,
) {
    if (
        trackPanelMode == TrackPanelMode.Subtitle &&
        state.subtitleTracks.isNotEmpty()
    ) {
        // 主字幕 leads: it is what the panel is opened for, and it used to sit
        // below the dual-subtitle presets, the swap and the preview — past the
        // first screen of a popup that is at most 308dp tall. Then 副字幕 and
        // the dual layouts, then offsets and style, then 第三方字幕 last.
        //
        // Where the selected track sits, taken once per opening: in a long list
        // it is otherwise below the fold, and it is the one row worth seeing.
        var selectedSubtitleSpan by remember(trackPanelMode) { mutableStateOf<IntRange?>(null) }
        LaunchedEffect(selectedSubtitleSpan) {
            val span = selectedSubtitleSpan ?: return@LaunchedEffect
            val viewport = panelScroll.viewportSize
            if (viewport > 0 && span.last > viewport) {
                val height = span.last - span.first
                panelScroll.motionAwareScrollTo(span.first - (viewport - height) / 2, reduceMotion)
            }
        }
        GroupLabel("主字幕")
        OptionRow(
            "关闭",
            state.subtitleTracks.none { it.selected },
            onClick = { onSelectSubtitle(EngineTrack.OFF) },
        )
        state.subtitleTracks.forEach { track ->
            OptionRow(
                track.label,
                track.selected,
                onClick = { onSelectSubtitle(track.id) },
                modifier =
                    if (track.selected && selectedSubtitleSpan == null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            val top = coordinates.positionInParent().y.roundToInt()
                            selectedSubtitleSpan = top..(top + coordinates.size.height)
                        }
                    } else {
                        Modifier
                    },
            )
        }
        // 同步 right under the track it moves: a line out of step is the thing
        // people open this panel to fix, and it used to sit below the dual layouts.
        if (subtitleControls.offsetAvailable) {
            OffsetStepper(
                label = "字幕同步",
                valueMs = subtitleControls.offsetMs,
                fineStepMs = SUBTITLE_OFFSET_FINE_STEP_MS,
                coarseStepMs = SUBTITLE_OFFSET_COARSE_STEP_MS,
                limitMs = SUBTITLE_OFFSET_LIMIT_MS,
                stepLabel = ::subtitleOffsetStepLabel,
                valueLabel = ::subtitleOffsetLabel,
                onChange = subtitleActions.onOffset,
            )
        }
        GroupLabel("副字幕")
        subtitleControls.dualLayoutNote?.let { UnsupportedSubtitleControl(it) }
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
            GroupLabel("双字幕方案 · 主上副下")
            DualSubtitleLanguagePair.entries.forEach { pair ->
                OptionRow(
                    pair.label,
                    selected = false,
                    onClick = { subtitleActions.onLanguagePair(pair) },
                )
            }
            if (
                subtitleControls.secondaryTrackId != null &&
                state.subtitleTracks.any { it.selected }
            ) {
                OptionRow("互换主副字幕", selected = false, onClick = subtitleActions.onSwap)
            }
        } else {
            Text(
                subtitleControls.secondaryUnavailableReason
                    ?: "当前播放器内核不支持双字幕。",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.68f),
            )
        }
        if (!subtitleControls.offsetAvailable) {
            GroupLabel("字幕同步")
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
        }
        if (
            subtitleControls.secondaryOffsetAvailable &&
            subtitleControls.secondaryTrackId != null
        ) {
            OffsetStepper(
                label = "副字幕同步",
                valueMs = subtitleControls.secondaryOffsetMs,
                fineStepMs = SUBTITLE_OFFSET_FINE_STEP_MS,
                coarseStepMs = SUBTITLE_OFFSET_COARSE_STEP_MS,
                limitMs = SUBTITLE_OFFSET_LIMIT_MS,
                stepLabel = ::subtitleOffsetStepLabel,
                valueLabel = ::subtitleOffsetLabel,
                onChange = subtitleActions.onSecondaryOffset,
            )
        }
        GroupLabel("字幕样式")
        if (
            subtitleControls.scaleAvailable &&
            subtitleControls.brightnessAvailable &&
            subtitleControls.positionAvailable
        ) {
            SubtitleStylePreset.entries
                .filterNot { it == SubtitleStylePreset.Custom }
                .forEach { preset ->
                    OptionRow(
                        preset.label,
                        subtitleControls.stylePreset == preset,
                        onClick = { subtitleActions.onStylePreset(preset) },
                    )
                }
        } else {
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
        }
        GroupLabel("字幕位置")
        if (subtitleControls.positionAvailable) {
            listOf(0.76f to "靠上", 0.88f to "居中偏下", 0.92f to "标准", 0.96f to "靠下")
                .forEach { (position, label) ->
                    OptionRow(
                        label,
                        subtitleControls.position == position,
                        onClick = { subtitleActions.onPosition(position) },
                    )
                }
        } else {
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
        }
        GroupLabel("字幕大小")
        if (subtitleControls.scaleAvailable) {
            listOf(0.8f to "小", 1f to "标准", 1.25f to "大", 1.5f to "特大")
                .forEach { (scale, label) ->
                    OptionRow(
                        label,
                        subtitleControls.scale == scale,
                        onClick = { subtitleActions.onScale(scale) },
                    )
                }
        } else {
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
        }
        if (subtitleControls.secondarySupported) {
            if (subtitleControls.independentScaleAvailable) {
                GroupLabel("副字幕字号")
                listOf(0.8f, 1f, 1.2f, 1.5f).forEach { scale ->
                    OptionRow(
                        "${(scale * 100).toInt()}%",
                        subtitleControls.secondaryScale == scale,
                        onClick = { subtitleActions.onSecondaryScale(scale) },
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text("主字幕预览", color = Color.White, fontSize = (18 * subtitleControls.scale).sp)
                Spacer(Modifier.size(4.dp))
                Text(
                    "Secondary subtitle",
                    color = Color.White,
                    fontSize = (18 * subtitleControls.secondaryScale).sp,
                )
            }
        }
        GroupLabel("HDR 字幕亮度")
        if (subtitleControls.brightnessAvailable) {
            listOf(0.4f to "40%", 0.6f to "60%", 0.8f to "80%", 1f to "100%")
                .forEach { (brightness, label) ->
                    OptionRow(
                        label,
                        subtitleControls.brightness == brightness,
                        onClick = { subtitleActions.onBrightness(brightness) },
                    )
                }
        } else {
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
        }
        GroupLabel("字幕颜色")
        if (subtitleControls.appearanceAvailable) {
            listOf(
                0xFFFFFFFFL to "白色",
                0xFFFFF2CCL to "暖黄",
                0xFFBFE3FFL to "浅蓝",
                0xFFBFFFD0L to "浅绿",
            ).forEach { (color, label) ->
                OptionRow(
                    label,
                    subtitleControls.appearance.textColorArgb == color,
                    onClick = { subtitleActions.onTextColor(color) },
                )
            }
            GroupLabel("字幕背景")
            listOf(
                0x00000000L to "透明",
                0x66000000L to "半透明黑",
                0x99000000L to "深色背景",
            ).forEach { (color, label) ->
                OptionRow(
                    label,
                    subtitleControls.appearance.backgroundColorArgb == color,
                    onClick = { subtitleActions.onBackgroundColor(color) },
                )
            }
            GroupLabel("字幕描边")
            listOf(0f to "关闭", 1f to "细", 2f to "标准", 4f to "粗")
                .forEach { (width, label) ->
                    OptionRow(
                        label,
                        subtitleControls.appearance.outlineWidth == width,
                        onClick = { subtitleActions.onOutlineWidth(width) },
                    )
                }
            listOf(0xFF000000L to "黑色描边", 0xFFFFFFFFL to "白色描边")
                .forEach { (color, label) ->
                    OptionRow(
                        label,
                        subtitleControls.appearance.outlineColorArgb == color,
                        onClick = { subtitleActions.onOutlineColor(color) },
                    )
                }
        } else {
            UnsupportedSubtitleControl(subtitleControls.unavailableReason)
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
        if (remoteSubtitles.searchUnavailableReason == null) {
            OptionRow(
                "搜索语言 · ${remoteSubtitles.language.label}",
                false,
                onClick = {
                    val languages = SubtitleSearchLanguage.entries
                    remoteSubtitleActions.onLanguage(
                        languages[
                            (remoteSubtitles.language.ordinal + 1) %
                                languages.size,
                        ],
                    )
                },
                detailLabel = "点击切换语言",
            )
            OptionRow(
                if (remoteSubtitles.loading) {
                    "正在搜索${remoteSubtitles.language.label}字幕…"
                } else {
                    "搜索${remoteSubtitles.language.label}字幕"
                },
                false,
                onClick = remoteSubtitleActions.onSearch,
            )
        } else {
            UnsupportedSubtitleControl(remoteSubtitles.searchUnavailableReason)
        }
        if (remoteSubtitles.importUnavailableReason == null) {
            OptionRow(
                "导入本地字幕",
                false,
                onClick = remoteSubtitleActions.onImport,
                detailLabel = "SRT / ASS / SSA / VTT · 仅本次播放，不上传",
            )
        } else {
            UnsupportedSubtitleControl(remoteSubtitles.importUnavailableReason)
        }
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
        GroupLabel("音频同步")
        if (audioControls.available) {
            // Telemetry, so it is not in the projection the rest of this page
            // reads; derived from the live state here keeps the page itself
            // off the position tick.
            val measuredAvOffsetMs by remember(playback, audioControls.measuredAvOffsetMs) {
                derivedStateOf {
                    playback.value.diagnostics.avSyncOffsetMs
                        ?: audioControls.measuredAvOffsetMs
                }
            }
            measuredAvOffsetMs?.let { offset ->
                OptionRow(
                    label =
                        if (offset == 0L) {
                            "自动校准 · 当前已同步"
                        } else {
                            "自动校准当前输出 · ${if (offset > 0L) "+" else ""}$offset ms"
                        },
                    selected = false,
                    onClick = audioActions.onAutoSync,
                )
            }
            OffsetStepper(
                label = "手动调整",
                valueMs = audioControls.delayMs,
                fineStepMs = AUDIO_DELAY_FINE_STEP_MS,
                coarseStepMs = AUDIO_DELAY_COARSE_STEP_MS,
                limitMs = AUDIO_DELAY_LIMIT_MS,
                stepLabel = ::audioDelayStepLabel,
                valueLabel = ::audioDelayLabel,
                onChange = audioActions.onDelay,
            )
        } else {
            Text(
                audioControls.unavailableReason ?: "当前播放模式不支持音频延迟。",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.68f),
            )
        }
        GroupLabel("音频增强")
        if (audioControls.enhancementAvailable) {
            AudioEnhancementMode.entries.forEach { mode ->
                OptionRow(
                    mode.label,
                    audioControls.enhancement == mode,
                    onClick = { audioActions.onEnhancement(mode) },
                )
            }
            Text(
                "响度均衡会统一不同剧集的主观音量；夜间人声会压缩动态范围。音频增强会关闭原码直通。",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.68f),
            )
        } else {
            Text(
                "当前锁定内核不支持音量增强或夜间人声。",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.68f),
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

@Composable
private fun UnsupportedSubtitleControl(reason: String?) {
    Text(
        reason ?: "当前播放内核不支持此字幕调节。",
        style = AppTypography.caption.medium,
        color = Color.White.copy(alpha = 0.68f),
    )
}

/**
 * An offset nudged in steps instead of picked from presets: a coarse and a fine step either side
 * of the current value, and 复位 back to zero. The presets it replaced moved whole seconds at a
 * time, which cannot land a subtitle that is 0.4 s out or a Bluetooth delay of 150 ms.
 *
 * [onChange] is handed the new absolute value: every offset action behind a stepper stores what it
 * is given rather than adding it to what it had.
 */
@Composable
private fun OffsetStepper(
    label: String,
    valueMs: Long,
    fineStepMs: Long,
    coarseStepMs: Long,
    limitMs: Long,
    stepLabel: (Long) -> String,
    valueLabel: (Long) -> String,
    onChange: (Long) -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val adjusted = valueMs != 0L
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupLabel(label)
        Text(
            "复位",
            style = AppTypography.caption.strong,
            color = if (adjusted) accent.accent else Color.White.copy(alpha = 0.34f),
            modifier =
                Modifier
                    .pressable(enabled = adjusted, onClickLabel = "复位为同步", onClick = { onChange(0L) })
                    .touchTarget()
                    .padding(horizontal = Dimens.space.sm, vertical = Dimens.space.xs),
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(-coarseStepMs, -fineStepMs).forEach { step ->
            OffsetStep(stepLabel(step), spoken = valueLabel(step)) {
                onChange(steppedOffsetMs(valueMs, step, limitMs))
            }
        }
        Text(
            valueLabel(valueMs),
            style = AppTypography.caption.strong,
            color = if (adjusted) accent.accent else Color.White.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Said after each press, so a step is answered with where it landed.
            modifier = Modifier.weight(1.6f).liveStatus(),
        )
        listOf(fineStepMs, coarseStepMs).forEach { step ->
            OffsetStep(stepLabel(step), spoken = valueLabel(step)) {
                onChange(steppedOffsetMs(valueMs, step, limitMs))
            }
        }
    }
}

@Composable
private fun RowScope.OffsetStep(
    label: String,
    /** The step with its direction and unit — 提前 0.5 秒 — where the key only has room for −0.5. */
    spoken: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = AppTypography.caption.medium,
        color = Color.White.copy(alpha = 0.86f),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .weight(1f)
                .playerChoiceFeedback(selected = false, shape = AppShapes.pill, onClick = onClick)
                .semantics { contentDescription = spoken }
                .padding(vertical = Dimens.space.sm),
    )
}

private const val SUBTITLE_OFFSET_FINE_STEP_MS = 100L

private const val SUBTITLE_OFFSET_COARSE_STEP_MS = 500L

/** The secondary track refuses anything past a minute either way; the primary keeps to the same range. */
private const val SUBTITLE_OFFSET_LIMIT_MS = 60_000L

private const val AUDIO_DELAY_FINE_STEP_MS = 50L

private const val AUDIO_DELAY_COARSE_STEP_MS = 200L

/** The range 自动校准 works within — see [calibratedAudioDelayMs]. */
private const val AUDIO_DELAY_LIMIT_MS = 2_000L
