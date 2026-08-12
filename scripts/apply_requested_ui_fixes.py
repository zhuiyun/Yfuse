from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1) -> None:
    file = Path(path)
    text = file.read_text()
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f"{path}: expected {count} matches, found {actual}")
    file.write_text(text.replace(old, new))


# 1) Make 毛玻璃 materially different from 液态玻璃 instead of only removing a faint sheen.
glass = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Glass.kt"
replace_exact(
    glass,
    '''private fun reducedTransparencyBorder(border: Color?, palette: Palette): Color? = when {
    border == null -> null
    border == palette.border || border == palette.tabbarBorder ->
        if (palette.isDark) Color.White.copy(alpha = 0.24f) else Color(0xFFD3DBE7)
    else -> border
}
''',
    '''private fun reducedTransparencyBorder(border: Color?, palette: Palette): Color? = when {
    border == null -> null
    border == palette.border || border == palette.tabbarBorder ->
        if (palette.isDark) Color.White.copy(alpha = 0.24f) else Color(0xFFD3DBE7)
    else -> border
}

/**
 * A visibly diffused pane for 毛玻璃.
 *
 * Simply removing the liquid specular left the same translucent fill underneath, which made
 * the two settings nearly indistinguishable on real artwork. Frosted glass keeps the source
 * colour but adds a restrained mist and a little more body; liquid glass keeps the clearer
 * pane and directional reflection. Reduced transparency still wins before this is consulted.
 */
private fun frostedSurfaceFill(fill: Color, palette: Palette): Color {
    val composited = fill.compositeOver(palette.background)
    val pale = composited.luminance() >= 0.48f
    val mist = if (pale) Color.White else Color(0xFF182235)
    val mistAmount = if (pale) 0.18f else 0.12f
    val minimumAlpha = if (pale) 0.68f else 0.52f
    return lerp(fill, mist, mistAmount).copy(
        alpha = maxOf(fill.alpha, minimumAlpha).coerceAtMost(0.94f),
    )
}
''',
)
replace_exact(
    glass,
    '''    val surface = if (accessibility.reduceTransparency || frostedGlass()) {
        Brush.linearGradient(listOf(resolvedFill, resolvedFill))
    } else {
''',
    '''    val surface = when {
        accessibility.reduceTransparency -> Brush.linearGradient(listOf(resolvedFill, resolvedFill))
        frostedGlass() -> {
            val frost = frostedSurfaceFill(resolvedFill, palette)
            Brush.linearGradient(listOf(frost, frost))
        }
        else -> {
''',
    count=3,
)
replace_exact(
    glass,
    '''    if (frostedGlass()) {
        return this.clip(shape).background(fill).border(Dimens.hairline, border, shape)
    }
''',
    '''    if (frostedGlass()) {
        val frost = frostedSurfaceFill(fill, palette)
        return this.clip(shape).background(frost).border(Dimens.hairline, border, shape)
    }
''',
)

# 2) Changing the launcher Logo also selects its paired splash choreography.
profile = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt"
replace_exact(
    profile,
    '''    onSelect = { chosen ->
        setAppIconVariant(chosen)
        appIcon = chosen
        sheet = null
''',
    '''    onSelect = { chosen ->
        setAppIconVariant(chosen)
        appIcon = chosen
        prefs.setSplashVariant(
            if (chosen == AppIconVariant.CloudPlayer) SplashAnimation.One else SplashAnimation.Two,
        )
        sheet = null
''',
)

# 3) Reuse the normal settings row so the compact two-line value starts exactly under title.
replace_exact(
    profile,
    '''@Composable
private fun DownloadRow(value: String, embedded: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val rowModifier = Modifier
        .fillMaxWidth()
        .let {
            if (embedded) it else {
                it.glass(AppShapes.control, palette.card2, palette.border)
            }
        }
        .pressable(onClick = onClick)
        .heightIn(min = MinTouchTarget)
        .padding(horizontal = 16.dp, vertical = 13.dp)
    val label: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIconTile(AppIcons.Download, SettingTint.downloads)
            Text("下载与离线库", style = AppTypography.body.medium, color = palette.text)
        }
    }
    BoxWithConstraints(rowModifier) {
        val stacked = largeText || windowWidthTier(maxWidth) == WindowWidthTier.Compact
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                label()
                Text(value, style = AppTypography.body.regular, color = palette.sub2, maxLines = 2)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { label() }
                Text(
                    value,
                    style = AppTypography.body.regular,
                    color = palette.sub2,
                    maxLines = 2,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
''',
    '''@Composable
private fun DownloadRow(value: String, embedded: Boolean = false, onClick: () -> Unit) {
    SettingRow(
        title = "下载与离线库",
        value = value,
        embedded = embedded,
        onClick = onClick,
        icon = AppIcons.Download,
        iconTint = SettingTint.downloads,
    )
}
''',
)

# 4) Splash preview and real splash must use the logo that belongs to the selected animation.
preview = "composeApp/src/androidMain/kotlin/com/yfuse/core/designsystem/SplashPreview.android.kt"
replace_exact(
    preview,
    '''    val mark = ImageBitmap.imageResource(R.drawable.yfuse_mark)
''',
    '''    val mark = ImageBitmap.imageResource(
        when (variant) {
            SplashAnimation.One -> R.drawable.cloud_player_logo
            SplashAnimation.Two -> R.drawable.yfuse_mark
        },
    )
''',
)

splash = "composeApp/src/androidMain/kotlin/com/yfuse/app/AnimatedSplashApp.kt"
replace_exact(
    splash,
    '''            // The ribbon without its own motion lines: 折带展开 animates the streak
            // separately, and a mark that already carried one would draw two.
            val mark = ImageBitmap.imageResource(R.drawable.yfuse_mark_ribbon)
''',
    '''            // Keep launcher Logo and splash artwork paired. 折带展开 belongs to the
            // legacy cloud-player mark; 水火交接 belongs to the current water-fire mark.
            val mark = ImageBitmap.imageResource(
                if (choreography === SplashOne) {
                    R.drawable.cloud_player_logo
                } else {
                    R.drawable.yfuse_mark_ribbon
                },
            )
''',
)

# 5) Player transport and danmaku join the same transparent outlined control family.
player = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt"
replace_exact(
    player,
    '''                enabled = !watchLocked,
                filled = true,
                interactive = false,
''',
    '''                enabled = !watchLocked,
                interactive = false,
''',
)
replace_exact(
    player,
    '''                enabled = !locked,
                filled = true,
                onClick = onPlayPause,
''',
    '''                enabled = !locked,
                onClick = onPlayPause,
''',
)
replace_exact(
    player,
    '''                    26.dp,
                    12.dp,
                    filled = danmakuEnabled,
                    onClick = onOpenDanmaku,
''',
    '''                    26.dp,
                    12.dp,
                    onClick = onOpenDanmaku,
''',
)
replace_exact(
    player,
    '''    /** Filled rather than outlined. The one control that earns it is 播放/暂停. */
    filled: Boolean = false,
''',
    '''    /** Filled emphasis is reserved for transient notification state such as unread chat. */
    filled: Boolean = false,
''',
)

print("Requested UI fixes applied successfully")
