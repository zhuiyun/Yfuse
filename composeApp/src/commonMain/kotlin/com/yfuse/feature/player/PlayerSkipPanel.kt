package com.yfuse.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.SkipMode
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 标记片头片尾 — where this series' intro begins and ends and where its credits begin, typed in or
 * taken from the picture behind the popup with 当前.
 */
@Composable
internal fun SkipPanel(
    skip: SkipSegmentState,
    skipActions: SkipSegmentActions,
    state: PlaybackState,
    /** Read when 当前 is tapped, never kept up to date while the panel is open. */
    playback: State<PlaybackState>,
) {
    val enabled = skip.mode != SkipMode.Off
    // 使用当前时间 is a question asked at the moment of the tap, so it is read
    // when the tap happens rather than kept up to date while the panel sits
    // open over the film.
    val here = { (playback.value.positionMs / 1000).coerceAtLeast(0L) }
    val durationSeconds = (state.durationMs / 1000).coerceAtLeast(0L)
    val savedCreditsStart =
        creditsStartSecondsFromLead(skip.creditsLeadSeconds, durationSeconds)
    var introStartInput by remember(skip.introStartSeconds) {
        mutableStateOf(formatSkipTimestamp(skip.introStartSeconds))
    }
    var introEndInput by remember(skip.introEndSeconds) {
        mutableStateOf(
            skip.introEndSeconds
                .takeIf { it > 0L }
                ?.let(::formatSkipTimestamp)
                .orEmpty(),
        )
    }
    var creditsInput by remember(skip.creditsLeadSeconds, durationSeconds) {
        mutableStateOf(savedCreditsStart?.let(::formatSkipTimestamp).orEmpty())
    }
    var introError by remember(skip.introStartSeconds, skip.introEndSeconds) {
        mutableStateOf<String?>(null)
    }
    var creditsError by remember(skip.creditsLeadSeconds, durationSeconds) {
        mutableStateOf<String?>(null)
    }

    PopupToggleHeader(
        label = "跳过片头/片尾",
        checked = enabled,
        onToggle = {
            skipActions.onSelectMode(
                if (enabled) SkipMode.Off else SkipMode.Button,
            )
        },
    )
    SegmentedRow(
        options = listOf("显示跳过按钮", "自动跳过"),
        selectedIndex = if (skip.mode == SkipMode.Auto) 1 else 0,
        onSelect = { index ->
            skipActions.onSelectMode(
                if (index == 0) SkipMode.Button else SkipMode.Auto,
            )
        },
    )
    PopupDivider()
    GroupLabel("片头")
    SkipTimeField(
        label = "开始时间",
        value = introStartInput,
        onValueChange = {
            introStartInput = it
            introError = null
        },
        onUseCurrent = {
            introStartInput = formatSkipTimestamp(here())
            introError = null
        },
    )
    SkipTimeField(
        label = "结束时间",
        value = introEndInput,
        onValueChange = {
            introEndInput = it
            introError = null
        },
        onUseCurrent = {
            introEndInput = formatSkipTimestamp(here())
            introError = null
        },
    )
    introError?.let { error ->
        Text(
            error,
            style = AppTypography.caption.medium,
            color = DarkPalette.error,
            modifier = Modifier.padding(horizontal = 5.dp),
        )
    }
    OptionRow(
        label = "保存片头时间",
        selected = false,
        onClick = {
            val introStart = parseSkipTimestamp(introStartInput)
            val introEnd = parseSkipTimestamp(introEndInput)
            introError =
                when {
                    introStart == null || introEnd == null ->
                        "请输入秒数、mm:ss 或 hh:mm:ss"
                    introStart == 0L && introEnd == 0L -> {
                        skipActions.onSetTimes(0L, 0L, skip.creditsLeadSeconds)
                        null
                    }
                    introEnd <= introStart -> "片头结束时间必须晚于开始时间"
                    durationSeconds > 0L && introEnd >= durationSeconds ->
                        "片头结束时间必须早于视频结束"
                    else -> {
                        skipActions.onSetTimes(
                            introStart,
                            introEnd,
                            skip.creditsLeadSeconds,
                        )
                        null
                    }
                }
        },
    )

    PopupDivider()
    GroupLabel("片尾")
    Text(
        "只设置片尾开始的时间点；无需结束时间。",
        style = AppTypography.caption.medium,
        color = Color.White.copy(alpha = 0.54f),
        modifier = Modifier.padding(horizontal = 5.dp),
    )
    SkipTimeField(
        label = "片尾时间",
        value = creditsInput,
        onValueChange = {
            creditsInput = it
            creditsError = null
        },
        onUseCurrent = {
            creditsInput = formatSkipTimestamp(here())
            creditsError = null
        },
    )
    creditsError?.let { error ->
        Text(
            error,
            style = AppTypography.caption.medium,
            color = DarkPalette.error,
            modifier = Modifier.padding(horizontal = 5.dp),
        )
    }
    OptionRow(
        label = "保存片尾时间",
        selected = false,
        onClick = {
            val creditsStart = parseSkipTimestamp(creditsInput)
            creditsError =
                when {
                    creditsStart == null -> "请输入秒数、mm:ss 或 hh:mm:ss"
                    creditsStart == 0L -> {
                        skipActions.onSetTimes(
                            skip.introStartSeconds,
                            skip.introEndSeconds,
                            0L,
                        )
                        null
                    }
                    durationSeconds <= 0L ->
                        "视频时长尚未就绪，暂时无法保存片尾时间"
                    else -> {
                        val lead =
                            creditsLeadSecondsFromStart(
                                creditsStart,
                                durationSeconds,
                            )
                        if (lead == null) {
                            "片尾时间必须位于视频时长范围内"
                        } else {
                            skipActions.onSetTimes(
                                skip.introStartSeconds,
                                skip.introEndSeconds,
                                lead,
                            )
                            null
                        }
                    }
                }
        },
    )
    if (skip.anySet) {
        PopupDivider()
        OptionRow(
            label = "清除片头片尾标记",
            selected = false,
            onClick = { skipActions.onSetTimes(0L, 0L, 0L) },
        )
    }
}

@Composable
private fun SkipTimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onUseCurrent: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            style = AppTypography.caption.strong,
            color = Color.White.copy(alpha = 0.72f),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .glass(
                        shape = AppShapes.thumb,
                        fill = Color.White.copy(alpha = 0.055f),
                        border = Color.White.copy(alpha = 0.13f),
                    ).padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { candidate ->
                        val normalized = candidate.replace('：', ':')
                        if (
                            normalized.length <= 10 &&
                            normalized.all { it.isDigit() || it == ':' }
                        ) {
                            onValueChange(normalized)
                        }
                    },
                    singleLine = true,
                    textStyle =
                        AppTypography.body.strong.copy(
                            color = Color.White.copy(alpha = 0.94f),
                        ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    cursorBrush = SolidColor(accent.accent),
                    decorationBox = { field ->
                        if (value.isBlank()) {
                            Text(
                                "mm:ss / hh:mm:ss",
                                style = AppTypography.body.medium,
                                color = Color.White.copy(alpha = 0.34f),
                            )
                        }
                        field()
                    },
                )
            }
            Text(
                "当前",
                style = AppTypography.caption.strong,
                color = accent.accent,
                modifier =
                    Modifier
                        .glass(
                            shape = AppShapes.thumb,
                            fill = accent.container,
                            border = accent.border,
                        ).noRippleClickable(onUseCurrent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
