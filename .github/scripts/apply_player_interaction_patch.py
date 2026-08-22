from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Long-press seek: the playback engine follows the held target continuously.
controls_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt")
controls = controls_path.read_text()
controls = replace_once(
    controls,
    "private const val HOLD_SEEK_TICK_MS = 150L",
    "private const val HOLD_SEEK_TICK_MS = 300L",
    "hold seek cadence",
)
controls = replace_once(
    controls,
    "private const val HOLD_SEEK_STEP_MS = 3_000L",
    "private const val HOLD_SEEK_STEP_MS = 6_000L",
    "initial hold seek step",
)
controls = replace_once(
    controls,
    "private const val HOLD_SEEK_FAST_STEP_MS = 9_000L",
    "private const val HOLD_SEEK_FAST_STEP_MS = 18_000L",
    "ramped hold seek step",
)
controls = replace_once(
    controls,
    "    // is held. [holdSeekTarget] is where the timeline has run to, committed on release.\n",
    "    // is held. [holdSeekTarget] is the last position already sent to the playback engine.\n",
    "hold seek target comment",
)
seek_line = "            holdSeekTarget = (holdSeekTarget + direction * step).coerceIn(0L, span)\n"
controls = replace_once(
    controls,
    seek_line,
    seek_line
    + "            // Real seek while held: decoded video follows the timeline instead of waiting for release.\n"
    + "            latestOnSeek(holdSeekTarget)\n",
    "live hold seek dispatch",
)
old_release = '''                        onPress = {
                            // Fires on every press; only a press that turned into a hold
                            // has a seek to land. `tryAwaitRelease` also returns after the
                            // long-press path consumes its way to the up event.
                            tryAwaitRelease()
                            if (holdSeekDirection != 0) {
                                val target = holdSeekTarget
                                holdSeekDirection = 0
                                latestOnSeek(target)
                                poke()
                            }
                        },
'''
new_release = '''                        onPress = {
                            // The engine is already following every held tick; release only stops it.
                            tryAwaitRelease()
                            if (holdSeekDirection != 0) {
                                holdSeekDirection = 0
                                poke()
                            }
                        },
'''
controls = replace_once(controls, old_release, new_release, "hold release behavior")
controls = controls.replace(
    " * Like the horizontal drag, this previews: the HUD tracks the target and the engine is\n"
    " * only asked to seek once, on release. Seeking every tick would mean twenty seeks a\n"
    " * second at a remote server that answers each one by rebuilding the stream.\n",
    " * The engine is now sought every 300ms while held, so the decoded picture visibly follows\n"
    " * the HUD without hammering a remote direct-play stream with frame-rate-frequency seeks.\n",
)
controls_path.write_text(controls)


# -----------------------------------------------------------------------------
# Skip editor: intro gets start/end; credits gets one absolute timeline point.
settings_path = Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt")
settings = settings_path.read_text()
settings = replace_once(
    settings,
    "import androidx.compose.foundation.rememberScrollState\n",
    "import androidx.compose.foundation.rememberScrollState\n"
    "import androidx.compose.foundation.text.BasicTextField\n"
    "import androidx.compose.foundation.text.KeyboardOptions\n",
    "skip text field imports",
)
settings = replace_once(
    settings,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.SolidColor\n",
    "skip cursor import",
)
settings = replace_once(
    settings,
    "import androidx.compose.ui.text.style.TextAlign\n",
    "import androidx.compose.ui.text.input.KeyboardType\nimport androidx.compose.ui.text.style.TextAlign\n",
    "skip keyboard import",
)

start = settings.index("                SettingsPanelKind.Skip -> {")
end = settings.index("                SettingsPanelKind.More -> {", start)
new_skip = '''                SettingsPanelKind.Skip -> {
                    val enabled = skip.mode != SkipMode.Off
                    val here = (state.positionMs / 1000).coerceAtLeast(0L)
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
                            introStartInput = formatSkipTimestamp(here)
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
                            introEndInput = formatSkipTimestamp(here)
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
                            creditsInput = formatSkipTimestamp(here)
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

'''
settings = settings[:start] + new_skip + settings[end:]

helper_marker = "@Composable\nprivate fun PopupBackLabel("
helper = '''@Composable
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

@Composable
private fun PopupBackLabel('''
settings = replace_once(settings, helper_marker, helper, "skip time field helper")
settings_path.write_text(settings)
