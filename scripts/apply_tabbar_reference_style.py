from pathlib import Path

APP = Path("composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt")
TOKENS = Path("composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Tokens.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


app = APP.read_text(encoding="utf-8")

app = replace_once(
    app,
    '    val selectedIndex = tabs.indexOfFirst { it.tab == active }.coerceAtLeast(0)\n',
    '    val selectedIndex = tabs.indexOfFirst { it.tab == active }.coerceAtLeast(0)\n'
    '    // Reference-style shell: almost-opaque light glass with a quiet neutral selected island.\n'
    '    // The accent stays on the icon/label so theme colours remain expressive without tinting\n'
    '    // the whole selected cell. Dark mode keeps the same hierarchy with a stronger dark glass.\n'
    '    val barFill = palette.glassStrong.copy(alpha = if (palette.isDark) 0.86f else 0.92f)\n'
    '    val selectionFill = palette.text.copy(alpha = if (palette.isDark) 0.12f else 0.08f)\n',
    "insert tab bar fills",
)

app = replace_once(
    app,
    '            .height(Dimens.tabBarHeight)\n'
    '            .shadow(Shadows.tabBar, GlassShapes.tabBar)\n'
    '            // Before the fill, so the 0.72–0.76 glass sits on the blur rather than under\n'
    '            // it. §8.1\'s material is the two together; the bar has only ever had the fill.\n'
    '            .backdropBlur(backdrop, GlassShapes.tabBar)\n'
    '            .overlayGlass(GlassShapes.tabBar, palette.glassStrong, palette.tabbarBorder)\n',
    '            .height(Dimens.tabBarHeight)\n'
    '            // The reference uses a true capsule rather than a rounded rectangle: the shell\n'
    '            // stays soft even after increasing the bar height.\n'
    '            .shadow(Shadows.tabBar, CircleShape)\n'
    '            .backdropBlur(backdrop, CircleShape)\n'
    '            .overlayGlass(CircleShape, barFill, palette.tabbarBorder)\n',
    "restyle tab bar shell",
)

app = replace_once(
    app,
    '                val pillWidth = cell * 0.72f\n'
    '                val pillHeight = size.height * 0.72f\n'
    '                drawRoundRect(\n'
    '                    color = accent.container,\n',
    '                // The selected region nearly fills its cell, matching the broad soft island\n'
    '                // in the reference instead of reading as a small Material indicator.\n'
    '                val pillWidth = cell * 0.92f\n'
    '                val pillHeight = size.height * 0.88f\n'
    '                drawRoundRect(\n'
    '                    color = selectionFill,\n',
    "enlarge neutral selected island",
)

app = replace_once(
    app,
    '        targetValue = if (selected) accent.accent else palette.sub2,\n',
    '        targetValue = if (selected) accent.accent else palette.text.copy(alpha = 0.72f),\n',
    "strengthen unselected tab tint",
)

app = replace_once(
    app,
    '            .fillMaxHeight()\n'
    '            .heightIn(min = MinTouchTarget)\n'
    '            .clip(GlassShapes.tabBar)\n',
    '            .fillMaxHeight()\n'
    '            .heightIn(min = MinTouchTarget)\n'
    '            .clip(CircleShape)\n',
    "make tab cells capsule-shaped",
)

app = replace_once(
    app,
    '        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(20.dp))\n'
    '        Spacer(Modifier.height(3.dp))\n'
    '        Text(item.label, style = AppTypography.caption.medium, color = tint)\n',
    '        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))\n'
    '        Spacer(Modifier.height(4.dp))\n'
    '        Text(\n'
    '            item.label,\n'
    '            style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,\n'
    '            color = tint,\n'
    '        )\n',
    "increase tab icon and selected label emphasis",
)

APP.write_text(app, encoding="utf-8")

tokens = TOKENS.read_text(encoding="utf-8")
tokens = replace_once(
    tokens,
    '    val contentBottom = 124.dp\n',
    '    val contentBottom = 138.dp\n',
    "increase content bottom inset",
)
tokens = replace_once(
    tokens,
    '    /** 悬浮 Tab Bar — 与迷你播放器共用材质、圆角与左右边距（§3）. */\n'
    '    val tabBarHeight = 54.dp\n'
    '    val tabBarInset = 14.dp\n',
    '    /** 悬浮 Tab Bar — 参考大胶囊导航，保留 14dp 左右悬浮边距. */\n'
    '    val tabBarHeight = 68.dp\n'
    '    val tabBarInset = 14.dp\n',
    "increase tab bar height",
)
TOKENS.write_text(tokens, encoding="utf-8")

print("Applied reference-style bottom tab bar")
