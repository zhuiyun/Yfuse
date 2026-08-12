from pathlib import Path
from textwrap import dedent


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count}")
    p.write_text(text.replace(old, new, expected))


# 1) 毛玻璃必须覆盖 flatGlass。设置页大量通过 flatGlass 绘制，之前这里完全
# 忽略 GlassStyle，导致切换视觉效果在设置页看起来像没有生效。
glass = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Glass.kt"
replace_exact(
    glass,
    dedent('''\
    /**
     * Liquid-glass surface without a directional colour ramp.
     *
     * Profile and form controls use this variant when hierarchy should come from
     * translucency, a single fill and the luminous edge.
     */
    '''),
    dedent('''\
    /**
     * Quiet glass surface used by dense forms and settings.
     *
     * It still participates in the user's material choice: Liquid keeps a restrained
     * directional sheen, while Frosted is a single translucent pane. This distinction
     * matters most on settings pages, which intentionally use flatGlass almost everywhere.
     */
    '''),
)
replace_exact(
    glass,
    dedent('''\
        val resolvedBorder = if (accessibility.reduceTransparency) {
            reducedTransparencyBorder(border, palette)
        } else {
            border
        }
        return this
            .clip(shape)
            .background(resolvedFill)
            .let { modifier ->
    '''),
    dedent('''\
        val resolvedBorder = if (accessibility.reduceTransparency) {
            reducedTransparencyBorder(border, palette)
        } else {
            border
        }
        val surface = if (accessibility.reduceTransparency || frostedGlass()) {
            Brush.linearGradient(listOf(resolvedFill, resolvedFill))
        } else {
            cssLinearGradient(
                145f,
                0f to Color.White.copy(alpha = if (palette.isDark) 0.13f else 0.58f),
                0.26f to resolvedFill.copy(
                    alpha = (resolvedFill.alpha * 0.92f).coerceIn(0f, 1f),
                ),
                0.72f to resolvedFill,
                1f to resolvedFill.copy(
                    alpha = (resolvedFill.alpha * 0.80f).coerceIn(0f, 1f),
                ),
            )
        }
        return this
            .clip(shape)
            .background(surface)
            .let { modifier ->
    '''),
)

# 2) APP 图标选择改为带真实预览的专用面板，同时把文案说清楚。
icon_variant = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AppIconVariant.kt"
replace_exact(
    icon_variant,
    dedent('''\
     * The mark is the same in all of them and only its ground changes: an alternate icon has to
     * remain recognisable as this app on a home screen the user already knows, so this is a
     * choice of colour, not of logo.
    '''),
    dedent('''\
     * The current water-fire mark is available on light and graphite grounds, and the previous
     * cloud-player mark remains a real alternate for people who recognise the app by that shape.
    '''),
)
replace_exact(
    icon_variant,
    '    Default("当前标志", "随包附带的水火标志，浅色底"),\n    Graphite("当前标志 · 石墨", "同一标志，深灰底，适合深色主屏"),\n',
    '    Default("当前 Logo", "当前水火标志，浅色底"),\n    Graphite("当前 Logo · 石墨", "当前水火标志，深灰底，适合深色主屏"),\n',
)
replace_exact(
    icon_variant,
    '    CloudPlayer("云朵播放器", "旧版云朵标志，与「折带展开」开屏配套"),\n',
    '    CloudPlayer("旧版云朵播放器", "旧版云朵播放器 Logo，与「折带展开」开屏配套"),\n',
)

profile = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt"
replace_exact(
    profile,
    dedent('''\
                Sheet.AppIcon -> OptionSheet(
                    title = "APP 图标",
                    subtitle = "更换后启动器可能需要几秒才会刷新",
                    options = AppIconVariant.entries.map { it.label to (it == appIcon) },
                    descriptions = AppIconVariant.entries.map { it.description },
                    onSelect = { index ->
                        val chosen = AppIconVariant.entries[index]
                        setAppIconVariant(chosen)
                        appIcon = chosen
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )
    '''),
    dedent('''\
                Sheet.AppIcon -> AppIconSheet(
                    current = appIcon,
                    onSelect = { chosen ->
                        setAppIconVariant(chosen)
                        appIcon = chosen
                        sheet = null
                    },
                    onDismiss = { sheet = null },
                )
    '''),
)

# 下载项原本只有一枚裸 glyph，和现在的彩色设置图标体系不一致。
replace_exact(
    profile,
    dedent('''\
        val label: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Download,
                    null,
                    tint = accent.accent,
                    modifier = Modifier.size(16.dp),
                )
                Text("下载与离线库", style = AppTypography.body.medium, color = palette.text)
            }
        }
    '''),
    dedent('''\
        val label: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingIconTile(AppIcons.Download, SettingTint.downloads)
                Text("下载与离线库", style = AppTypography.body.medium, color = palette.text)
            }
        }
    '''),
)

# 3) 检测升级补齐对应设置图标。
update_tools = "composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/AppUpdateTools.android.kt"
replace_exact(
    update_tools,
    'import com.yfuse.core.designsystem.Dimens\n',
    'import com.yfuse.core.designsystem.AppIcons\nimport com.yfuse.core.designsystem.Dimens\nimport com.yfuse.core.designsystem.SettingTint\n',
)
replace_exact(
    update_tools,
    dedent('''\
        SettingRow(
            title = "检测升级",
            value = "暂不可用",
        )
    '''),
    dedent('''\
        SettingRow(
            title = "检测升级",
            value = "暂不可用",
            icon = AppIcons.Refresh,
            iconTint = SettingTint.sync,
        )
    '''),
)
replace_exact(
    update_tools,
    dedent('''\
        SettingRow(
            title = "检测升级",
            value = value,
            embedded = true,
            onClick = onClick,
        )
    '''),
    dedent('''\
        SettingRow(
            title = "检测升级",
            value = value,
            embedded = true,
            onClick = onClick,
            icon = AppIcons.Refresh,
            iconTint = SettingTint.sync,
        )
    '''),
)

# 4) 服务器布局切换：布局变化时重建 LazyGrid；顶部两枚圆形键视觉从 48dp 收到
# 42dp，点击区域仍由 touchTarget 保持 48dp。
servers = "composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabScreen.kt"
replace_exact(
    servers,
    'import androidx.compose.runtime.getValue\n',
    'import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.key\n',
)
replace_exact(
    servers,
    'private val ServerCardMinWidth = 158.dp\n',
    'private val ServerCardMinWidth = 158.dp\nprivate val ServerHeaderCircleSize = 42.dp\n',
)
replace_exact(
    servers,
    '        ) {\n            LazyVerticalGrid(\n',
    '        ) {\n            key(layout) {\n                LazyVerticalGrid(\n',
)
replace_exact(
    servers,
    dedent('''\
                    onMore = { actionsFor = server },
                )
            }
        }
    }

    actionsFor?.let { server ->
    '''),
    dedent('''\
                    onMore = { actionsFor = server },
                )
            }
        }
        }
    }

    actionsFor?.let { server ->
    '''),
)
replace_exact(
    servers,
    '                        .size(MinTouchTarget),\n',
    '                        .size(ServerHeaderCircleSize),\n',
    expected=2,
)
replace_exact(
    servers,
    dedent('''\
                .pressable(
                    onClickLabel = if (layout == ServerLayout.Grid) {
    '''),
    dedent('''\
                .pressable(
                    haptic = HapticSignal.Select,
                    onClickLabel = if (layout == ServerLayout.Grid) {
    '''),
)

# 5) 图标预览面板：直接显示当前 launcher 标志和旧云朵播放器标志。
Path("composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AppIconSheet.kt").write_text(dedent('''\
package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MinTouchTarget
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable

@Composable
internal fun AppIconSheet(
    current: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "APP 图标",
            subtitle = "选择时直接预览 Logo；启动器可能需要几秒才会刷新",
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            AppIconVariant.entries.forEach { option ->
                val isSelected = option == current
                val fill = if (isSelected) accent.container else palette.card2
                val border = if (isSelected) accent.border else palette.border
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(
                            haptic = HapticSignal.Select,
                            role = Role.RadioButton,
                            focusShape = GlassShapes.chip,
                            onClickLabel = option.label,
                            onClick = { onSelect(option) },
                        )
                        .semantics { selected = isSelected }
                        .heightIn(min = 72.dp)
                        .flatGlass(GlassShapes.chip, fill, border)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconPreview(option, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            option.label,
                            style = if (isSelected) {
                                AppTypography.body.strong
                            } else {
                                AppTypography.body.medium
                            },
                            color = if (isSelected) accent.accent else palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.material3.Text(
                            option.description,
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(MinTouchTarget),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(accent.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = accent.onAccent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal expect fun AppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier = Modifier,
)
'''))

Path("composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/AppIconSheet.android.kt").write_text(dedent('''\
package com.yfuse.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yfuse.R
import com.yfuse.core.designsystem.AppShapes

@Composable
internal actual fun AppIconPreview(variant: AppIconVariant, modifier: Modifier) {
    val background = when (variant) {
        AppIconVariant.Default -> Color(0xFFFCFBFC)
        AppIconVariant.Graphite -> Color(0xFF1B2333)
        AppIconVariant.CloudPlayer -> Color(0xFFFCFBFC)
    }
    val artwork = when (variant) {
        AppIconVariant.CloudPlayer -> R.drawable.cloud_player_logo
        else -> R.drawable.yfuse_mark
    }
    Box(
        modifier
            .clip(AppShapes.thumb)
            .background(background)
            .padding(if (variant == AppIconVariant.CloudPlayer) 4.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
'''))

# 6) 主题色合同：每个选项在浅/深主题都必须解析成不同的交互色。
Path("composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/AccentColorContractTest.kt").write_text(dedent('''\
package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentColorContractTest {
    @Test
    fun everyAccentProducesDistinctInteractiveColorInBothThemes() {
        listOf(false, true).forEach { dark ->
            val resolved = AccentColor.entries.map { it.resolveColors(dark).accent }
            assertEquals(
                AccentColor.entries.size,
                resolved.distinct().size,
                "Every visible accent choice must change the resolved interactive colour",
            )
        }
    }
}
'''))

# Helper transport files are removed from the resulting PR diff.
Path(".github/workflows/agent-apply-ui-fixes.yml").unlink()
Path(".github/agent_apply_ui_fixes.py").unlink()
