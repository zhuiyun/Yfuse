from pathlib import Path
from textwrap import dedent
import re


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} exact matches, found {count}")
    p.write_text(text.replace(old, new, expected))


def replace_regex(path: str, pattern: str, replacement: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    updated, count = re.subn(pattern, replacement, text, count=expected, flags=re.S)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} regex matches, found {count}")
    p.write_text(updated)


# 1) 毛玻璃必须覆盖 flatGlass。设置页大量通过 flatGlass 绘制；0.2.52 里它完全
# 忽略 LocalGlassStyle，所以在这个页面切换“毛玻璃 / 液态玻璃”几乎看不到变化。
glass = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Glass.kt"
flat_glass = dedent('''\
@Composable
fun Modifier.flatGlass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = LocalPalette.current.border,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    val resolvedFill = if (accessibility.reduceTransparency) {
        reducedTransparencyFill(fill, palette)
    } else {
        fill
    }
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
            if (resolvedBorder != null) {
                modifier.border(Dimens.hairline, resolvedBorder, shape)
            } else {
                modifier
            }
        }
}
''')
replace_regex(
    glass,
    r'@Composable\nfun Modifier\.flatGlass\(.*?\n}\n\n/\*\*\n \* Liquid-glass surface with a single-colour edge\.',
    flat_glass + '\n/**\n * Liquid-glass surface with a single-colour edge.',
)
replace_exact(
    glass,
    ' * Liquid-glass surface without a directional colour ramp.\n *\n * Profile and form controls use this variant when hierarchy should come from\n * translucency, a single fill and the luminous edge.\n',
    ' * Quiet glass surface used by dense forms and settings.\n *\n * Liquid keeps a restrained directional sheen; Frosted removes that sheen and keeps a\n * single translucent pane. Settings use this variant heavily, so it must honor GlassStyle.\n',
)

# 2) APP 图标选择改为带真实预览的专用面板，同时把文案说清楚。
icon_variant = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AppIconVariant.kt"
replace_exact(
    icon_variant,
    ' * The mark is the same in all of them and only its ground changes: an alternate icon has to\n * remain recognisable as this app on a home screen the user already knows, so this is a\n * choice of colour, not of logo.\n',
    ' * The current water-fire mark is available on light and graphite grounds, and the previous\n * cloud-player mark remains a real alternate for people who recognise the app by that shape.\n',
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
replace_regex(
    profile,
    r'            Sheet\.AppIcon -> OptionSheet\(.*?                onDismiss = \{ sheet = null \},\n            \)',
    dedent('''\
            Sheet.AppIcon -> AppIconSheet(
                current = appIcon,
                onSelect = { chosen ->
                    setAppIconVariant(chosen)
                    appIcon = chosen
                    sheet = null
                },
                onDismiss = { sheet = null },
            )'''),
)

# 下载项原本只有一枚裸 glyph，和现在的彩色设置图标体系不一致。
replace_regex(
    profile,
    r'            Icon\(\n                AppIcons\.Download,\n                null,\n                tint = accent\.accent,\n                modifier = Modifier\.size\(16\.dp\),\n            \)',
    '            SettingIconTile(AppIcons.Download, SettingTint.downloads)',
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
    '            SettingRow(\n                title = "检测升级",\n                value = "暂不可用",\n            )\n',
    '            SettingRow(\n                title = "检测升级",\n                value = "暂不可用",\n                icon = AppIcons.Refresh,\n                iconTint = SettingTint.sync,\n            )\n',
)
replace_exact(
    update_tools,
    '            SettingRow(\n                title = "检测升级",\n                value = value,\n                embedded = true,\n                onClick = onClick,\n            )\n',
    '            SettingRow(\n                title = "检测升级",\n                value = value,\n                embedded = true,\n                onClick = onClick,\n                icon = AppIcons.Refresh,\n                iconTint = SettingTint.sync,\n            )\n',
)

# 4) 服务器布局切换。
# 0.2.52 的 Grid 最小卡片 158dp，在部分手机的实际内容宽度里只够 1 列，因此
# “网格”和“列表”最终都是 1 列，看起来完全没反应。降低到 146dp，窄手机也能明确
# 得到两列；列表仍固定 1 列。顶部两枚圆形按钮视觉收至 42dp，但点击区仍保持 48dp。
servers = "composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabScreen.kt"
replace_exact(
    servers,
    'private val ServerCardMinWidth = 158.dp\n',
    'private val ServerCardMinWidth = 146.dp\nprivate val ServerHeaderCircleSize = 42.dp\n',
)
replace_exact(
    servers,
    '                        .size(MinTouchTarget),\n',
    '                        .size(ServerHeaderCircleSize),\n',
    expected=2,
)
replace_exact(
    servers,
    '                        .pressable(\n                            onClickLabel = if (layout == ServerLayout.Grid) {\n',
    '                        .pressable(\n                            haptic = HapticSignal.Select,\n                            onClickLabel = if (layout == ServerLayout.Grid) {\n',
)

# 5) 图标预览面板：直接显示当前 launcher Logo 与旧云朵播放器 Logo。
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
            subtitle = "直接预览当前 Logo 与旧版 Logo；启动器可能需要几秒刷新",
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            AppIconVariant.entries.forEach { option ->
                val isSelected = option == current
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
                        .flatGlass(
                            GlassShapes.chip,
                            if (isSelected) accent.container else palette.card2,
                            if (isSelected) accent.border else palette.border,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconPreview(option, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            option.label,
                            style = if (isSelected) AppTypography.body.strong else AppTypography.body.medium,
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
                    Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) {
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
import com.yfuse.core.designsystem.GlassShapes

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
            .clip(GlassShapes.appIcon)
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

# 6) 主题色合同：每个选项在浅/深主题都必须解析成不同交互色。根 App 已经收集 accent
# StateFlow 并把它传给 YfuseTheme；这个测试固定“十个选项确实会产生十个不同结果”。
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

# 7) 服务器布局持久化合同：切换后重建 ThemePreferences（等价于进程重启）仍保持选择。
prefs_test = "composeApp/src/commonTest/kotlin/com/yfuse/core/data/ThemePreferencesTest.kt"
replace_exact(
    prefs_test,
    'import com.yfuse.core.model.PlayerEngine\n',
    'import com.yfuse.core.model.PlayerEngine\nimport com.yfuse.core.model.ServerLayout\n',
)
replace_exact(
    prefs_test,
    '            setSplashVariant(SplashAnimation.Two)\n',
    '            setSplashVariant(SplashAnimation.Two)\n            setServerLayout(ServerLayout.List)\n',
)
replace_exact(
    prefs_test,
    '        assertEquals(SplashAnimation.Two, restored.splashVariant.value)\n',
    '        assertEquals(SplashAnimation.Two, restored.splashVariant.value)\n        assertEquals(ServerLayout.List, restored.serverLayout.value)\n',
)

# Helper transport files are removed from the resulting PR diff.
Path(".github/workflows/agent-apply-ui-fixes.yml").unlink()
Path(".github/agent_apply_ui_fixes.py").unlink()
