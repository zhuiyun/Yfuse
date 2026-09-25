package com.yfuse.feature.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassAdjustment
import com.yfuse.core.designsystem.GlassInk
import com.yfuse.core.designsystem.GlassMaterial
import com.yfuse.core.designsystem.GlassMaterialPreset
import com.yfuse.core.designsystem.GlassMaterialPreview
import com.yfuse.core.designsystem.GlassMaterials
import com.yfuse.core.designsystem.GlassSlider
import com.yfuse.core.designsystem.LightPalette
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.SettingSegmentControl
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.supportsBackdropBlur
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun GlassMaterialSettingsScreen(
    materials: GlassMaterials,
    onChange: (Boolean, GlassMaterial) -> Unit,
    onBack: () -> Unit,
    firstControlRequester: FocusRequester? = null,
    /** Non-null on a shell with one fixed theme (TV): edits that recipe and hides the switch. */
    lockedTheme: Boolean? = null,
) {
    val palette = LocalPalette.current
    var dark by rememberSaveable { mutableStateOf(lockedTheme ?: palette.isDark) }
    // A slider reports every step of a drag. The draft drives this page and its preview;
    // storage, and every panel open elsewhere, hear about it once the hand has paused.
    val committed = materials
    var draft by remember { mutableStateOf(committed) }
    val latestCommitted by rememberUpdatedState(committed)
    val latestOnChange by rememberUpdatedState(onChange)

    fun commit(value: GlassMaterials) {
        if (value.light != latestCommitted.light) latestOnChange(false, value.light)
        if (value.dark != latestCommitted.dark) latestOnChange(true, value.dark)
    }
    val latestDraft by rememberUpdatedState(draft)
    LaunchedEffect(draft) {
        if (draft == latestCommitted) return@LaunchedEffect
        delay(COMMIT_QUIET_MS)
        commit(draft)
    }
    DisposableEffect(Unit) { onDispose { commit(latestDraft) } }
    val edit: (Boolean, GlassMaterial) -> Unit = { forDark, value ->
        draft = if (forDark) draft.copy(dark = value) else draft.copy(light = value)
    }
    var section by rememberSaveable { mutableStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var advancedGroup by rememberSaveable { mutableStateOf(1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun showSection(value: Int) {
        section = value
        scope.launch { listState.scrollToItem(1) }
    }
    val material = draft.forTheme(dark)
    val inset = Modifier.padding(horizontal = Dimens.pageHorizontal)
    val density = LocalDensity.current
    val height =
        with(density) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val pinPreview = height >= 600.dp && density.fontScale < 1.3f
    val preview: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().background(palette.background).padding(vertical = 8.dp).then(inset).then(
                if (firstControlRequester !=
                    null
                ) {
                    Modifier.focusRequester(firstControlRequester)
                } else {
                    Modifier
                },
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lockedTheme == null) {
                SettingSegmentControl(
                    options = listOf("浅色玻璃", "深色玻璃"),
                    selectedIndex = if (dark) 1 else 0,
                    expanded = true,
                    onSelect = { dark = it == 1 },
                )
            }
            GlassMaterialPreview(dark, draft, height = (172 * density.fontScale).dp)
            SettingSegmentControl(
                options = listOf("材质预设", "调节效果"),
                selectedIndex = section,
                expanded = true,
                onSelect = { showSection(it) },
            )
            Text(
                "实时预览 · 自动保存 · 浅深色分别记忆",
                style = AppTypography.caption.regular,
                color = palette.sub2,
            )
            val opaqueGlass = LocalAccessibilityOptions.current.reduceTransparency || !supportsBackdropBlur
            if (opaqueGlass) {
                Text(
                    "当前已减少透明度或设备不支持背景模糊，玻璃显示为实色：底色、亮边、遮罩与文字颜色生效，" +
                        "雾化、透景与纹理不显示；参数仍会保存。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            val themePalette = if (dark) DarkPalette else LightPalette
            val resolvedInk = material.resolvedInk(themePalette, opaqueGlass)
            if (material.ink == GlassInk.Theme && resolvedInk != GlassInk.Theme) {
                Text(
                    "这个底色上“跟随主题”的文字不够清晰，已自动改用" +
                        (if (resolvedInk == GlassInk.Light) "浅色" else "深色") + "文字。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            val contrast = material.textContrast(themePalette, opaqueGlass)
            if (contrast < GlassMaterial.MIN_TEXT_CONTRAST) {
                Text(
                    "正文对比度约 ${(contrast * 10).roundToInt() / 10f}:1（按纯色页面估算），低于 4.5:1。" +
                        "提高不透明度、加深背景遮罩或更换文字颜色会更清晰。",
                    style = AppTypography.caption.regular,
                    color = Semantic.Warning,
                )
            }
        }
    }
    SettingsPage("玻璃材质", "作用于弹窗、面板与遮罩 · 选一款喜欢的，再轻轻调整", onBack, state = listState) {
        if (pinPreview) {
            stickyHeader(key = "glass-preview") { preview() }
        } else {
            item(key = "glass-preview") { preview() }
        }
        if (section == 0) {
            item(key = "glass-presets") {
                Column(inset, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("材质预设", Modifier.weight(1f), style = AppTypography.body.medium, color = palette.text)
                        val modified = material != material.preset.material(dark)
                        Text(
                            material.preset.label + if (modified) " · 已微调" else "",
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns =
                            if (maxWidth < 320.dp) {
                                1
                            } else if (maxWidth < 600.dp) {
                                2
                            } else {
                                3
                            }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassMaterialPreset.selectable.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { preset ->
                                        MaterialPresetCard(
                                            preset = preset,
                                            chosen = material.preset == preset,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                edit(dark, preset.material(dark))
                                                showSection(1)
                                            },
                                        )
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Text(
                        "选择预设后可继续微调；再次点击该预设可恢复其推荐参数。",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
            }
        }
        if (section != 0) {
            item(key = "glass-controls-$section") {
                Column(
                    inset.fillMaxWidth().background(palette.card, AppShapes.sheet).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        material.preset.label + " · 常用调节",
                        style = AppTypography.body.medium,
                        color = palette.text,
                    )
                    CommonAdjustments.forEach { adjustment ->
                        AdjustmentSlider(adjustment, material, dark, edit)
                    }
                    OverlayButton(if (advanced) "收起高级调节" else "高级调节 · 颜色与特殊质感", onClick = { advanced = !advanced })
                }
            }
            if (advanced) {
                item(key = "glass-advanced") {
                    Column(
                        inset.fillMaxWidth().background(palette.card, AppShapes.sheet).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        SettingSegmentControl(
                            options = listOf("底色", "透景", "光泽", "纹理"),
                            selectedIndex = advancedGroup - 1,
                            expanded = true,
                            onSelect = { advancedGroup = it + 1 },
                        )
                        if (advancedGroup == 1) {
                            Text(
                                "玻璃底色 · ${material.tint(dark).hexRgb()}",
                                style = AppTypography.body.medium,
                                color = palette.text,
                            )
                        }
                        GlassAdjustment.entries
                            .filter { it.group == advancedGroup && it !in CommonAdjustments }
                            .forEach {
                                AdjustmentSlider(it, material, dark, edit)
                            }
                        if (advancedGroup == 1) {
                            Text("文字颜色", style = AppTypography.body.medium, color = palette.text)
                            SettingSegmentControl(
                                options = listOf("跟随主题", "浅色文字", "深色文字"),
                                selectedIndex = material.ink.ordinal,
                                expanded = true,
                                onSelect = { edit(dark, material.copy(ink = GlassInk.entries[it])) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "glass-reset") {
            Column(inset, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OverlayButton(
                    "重置当前预设参数 · ${material.preset.label}",
                    onClick = { edit(dark, material.preset.material(dark)) },
                )
                if (!dark) {
                    OverlayButton("使用改动前效果 · 52% / 16%", onClick = { edit(false, GlassMaterial.PreviousLight) })
                }
                OverlayButton(
                    "恢复${if (dark) "深色" else "浅色"}默认",
                    onClick = { edit(dark, GlassMaterial.defaults(dark)) },
                )
                Text(
                    "调整应用于弹窗玻璃与弹窗遮罩。预览切换不会改变应用主题。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private val CommonAdjustments = listOf(GlassAdjustment.Blur, GlassAdjustment.Opacity, GlassAdjustment.Rim)

/** How long a drag has to rest before the draft is stored and published to the app. */
private const val COMMIT_QUIET_MS = 250L

/** One D-pad press on a 0-255 colour channel; 255 single steps is no way to pick a colour. */
private const val COLOR_KEY_STEPS = 5f

@Composable
private fun AdjustmentSlider(
    adjustment: GlassAdjustment,
    material: GlassMaterial,
    dark: Boolean,
    onChange: (Boolean, GlassMaterial) -> Unit,
) {
    val value = adjustment.value(material, dark)
    val intervals = ((adjustment.max - adjustment.min) / adjustment.step).roundToInt()
    val inverted = adjustment == GlassAdjustment.Opacity
    val fraction = (value - adjustment.min) / (adjustment.max - adjustment.min)
    MaterialSlider(
        title =
            when (adjustment) {
                GlassAdjustment.Blur -> "雾化"
                GlassAdjustment.Opacity -> "通透度"
                GlassAdjustment.Rim -> "亮边"
                else -> adjustment.label
            },
        valueText = adjustment.display(if (inverted) 1f - value else value),
        hint = if (inverted) "厚实 → 通透" else adjustment.hint,
        value = if (inverted) 1f - fraction else fraction,
        steps = intervals - 1,
        keyStep = if (adjustment.ordinal < 3) COLOR_KEY_STEPS / intervals else 1f / intervals,
        swatch = if (adjustment.ordinal < 3) material.tint(dark).copy(alpha = 1f) else null,
        onChange = {
            val normalized = if (inverted) 1f - it else it
            val adjusted = adjustment.min + normalized * (adjustment.max - adjustment.min)
            onChange(dark, adjustment.update(material, dark, adjusted))
        },
    )
}

@Composable
private fun MaterialPresetCard(
    preset: GlassMaterialPreset,
    chosen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    Column(
        modifier
            .pressable(focusShape = AppShapes.control, onClickLabel = "选择${preset.label}", onClick = onClick)
            .background(if (chosen) accent.copy(alpha = 0.10f) else palette.card, AppShapes.control)
            .border(1.dp, if (chosen) accent.copy(alpha = 0.65f) else palette.border, AppShapes.control)
            .semantics { selected = chosen }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(preset.number, Modifier.weight(1f), style = AppTypography.caption.strong, color = accent)
            if (chosen) Text("已选", style = AppTypography.caption.regular, color = accent)
        }
        Text(preset.label, style = AppTypography.body.medium, color = palette.text)
        Text(preset.description, style = AppTypography.caption.regular, color = palette.sub2)
    }
}

@Composable
private fun MaterialSlider(
    title: String,
    valueText: String,
    hint: String,
    value: Float,
    onChange: (Float) -> Unit,
    swatch: Color? = null,
    steps: Int = 99,
    keyStep: Float = 1f / (steps + 1),
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), style = AppTypography.body.medium, color = palette.text)
            if (swatch != null) {
                Box(
                    Modifier
                        .size(
                            14.dp,
                        ).background(swatch, AppShapes.chip)
                        .border(0.5.dp, palette.border, AppShapes.chip),
                )
            }
            Text(valueText, style = AppTypography.caption.strong, color = accent)
        }
        // The slider owns focus, its ring and the arrow keys now; only the stride is this page's.
        GlassSlider(
            value = value,
            onValueChange = onChange,
            steps = steps,
            keyStride = keyStep,
            modifier = Modifier.semantics { contentDescription = title },
        )
        Text(hint, style = AppTypography.caption.regular, color = palette.sub2)
    }
}

private fun Color.hexRgb(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
