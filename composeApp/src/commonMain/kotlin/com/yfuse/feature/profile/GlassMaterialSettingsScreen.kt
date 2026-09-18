package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassMaterial
import com.yfuse.core.designsystem.GlassMaterialPreview
import com.yfuse.core.designsystem.GlassMaterials
import com.yfuse.core.designsystem.GlassSlider
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.SettingSegmentControl
import com.yfuse.core.designsystem.supportsBackdropBlur
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun GlassMaterialSettingsScreen(
    materials: GlassMaterials,
    onChange: (Boolean, GlassMaterial) -> Unit,
    onBack: () -> Unit,
    firstControlRequester: FocusRequester? = null,
) {
    val palette = LocalPalette.current
    var dark by rememberSaveable { mutableStateOf(palette.isDark) }
    val material = materials.forTheme(dark)
    val inset = Modifier.padding(horizontal = Dimens.pageHorizontal)
    SettingsPage("玻璃材质", "弹窗的光影与透感，由你决定", onBack) {
        item(key = "glass-preview") {
            Column(
                inset.then(
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
                SettingSegmentControl(
                    options = listOf("浅色玻璃", "深色玻璃"),
                    selectedIndex = if (dark) 1 else 0,
                    expanded = true,
                    onSelect = { dark = it == 1 },
                )
                GlassMaterialPreview(dark, materials)
                Text(
                    "实时预览 · 自动保存 · 浅深色分别记忆",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
                if (LocalAccessibilityOptions.current.reduceTransparency || !supportsBackdropBlur) {
                    Text(
                        "当前已减少透明度或设备不支持背景模糊，实际玻璃显示为实色；参数仍会保存。",
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
            }
        }
        item(key = "glass-controls") {
            Column(
                inset.fillMaxWidth().background(palette.card, AppShapes.sheet).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                MaterialSlider(
                    title = "玻璃底色",
                    valueText = material.tint(dark).hexRgb(),
                    hint = "深灰 → 浅灰",
                    value = material.tone,
                    swatch = material.tint(dark).copy(alpha = 1f),
                    onChange = { onChange(dark, material.copy(tone = it)) },
                )
                MaterialSlider(
                    title = "底色不透明度",
                    valueText = "${(material.opacity * 100).roundToInt()}%",
                    hint = "通透 → 厚实",
                    value = material.opacity,
                    onChange = { onChange(dark, material.copy(opacity = it)) },
                )
                MaterialSlider(
                    title = "背景遮罩不透明度",
                    valueText = "${(material.scrim * 100).roundToInt()}%",
                    hint = "背景明亮 → 背景暗淡",
                    value = material.scrim,
                    onChange = { onChange(dark, material.copy(scrim = it)) },
                )
            }
        }
        item(key = "glass-reset") {
            Column(inset, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!dark) {
                    OverlayButton("使用改动前效果 · 52% / 16%", onClick = { onChange(false, GlassMaterial.PreviousLight) })
                }
                OverlayButton(
                    "恢复${if (dark) "深色" else "浅色"}默认",
                    onClick = { onChange(dark, GlassMaterial.defaults(dark)) },
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

@Composable
private fun MaterialSlider(
    title: String,
    valueText: String,
    hint: String,
    value: Float,
    onChange: (Float) -> Unit,
    swatch: Color? = null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    var focused by remember { mutableStateOf(false) }
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
        GlassSlider(
            value = value,
            onValueChange = onChange,
            steps = 99,
            modifier =
                Modifier
                    .border(1.dp, if (focused) accent else Color.Transparent, AppShapes.control)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent {
                        if (it.key != Key.DirectionLeft && it.key != Key.DirectionRight) {
                            false
                        } else {
                            if (it.type == KeyEventType.KeyDown) {
                                onChange((value + if (it.key == Key.DirectionRight) 0.01f else -0.01f).coerceIn(0f, 1f))
                            }
                            true
                        }
                    }.focusable()
                    .semantics { contentDescription = title },
        )
        Text(hint, style = AppTypography.caption.regular, color = palette.sub2)
    }
}

private fun Color.hexRgb(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
