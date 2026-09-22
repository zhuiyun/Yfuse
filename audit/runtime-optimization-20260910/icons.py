from edit import read, write, replace

p = 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/AppIcons.kt'
s = read(p)
start = s.index('    /** Episode drawer')
end = s.index('\n    /**', start + 6)
s = s[:start] + '''    /** Episode drawer — three bullet rows, matching the player control stroke. */
    val EpisodeList =
        strokeVector("episode-list") {
            moveTo(9f, 5.6f)
            horizontalLineTo(20.2f)
            moveTo(9f, 12f)
            horizontalLineTo(20.2f)
            moveTo(9f, 18.4f)
            horizontalLineTo(20.2f)
        }.andDots(4.6f to 5.6f, 4.6f to 12f, 4.6f to 18.4f).build()
''' + s[end:]
start = s.index('    val Danmaku =')
end = s.index('    val Chat =', start)
s = s[:start] + '''    /** Speech bubble with a vector 弹 glyph; independent of device fonts and font scaling. */
    val Danmaku =
        strokeVector("danmaku") {
            moveTo(6f, 3.8f)
            horizontalLineTo(18f)
            curveTo(19.55f, 3.8f, 20.8f, 5.05f, 20.8f, 6.6f)
            verticalLineTo(15f)
            curveTo(20.8f, 16.55f, 19.55f, 17.8f, 18f, 17.8f)
            horizontalLineTo(10.2f)
            lineTo(6.2f, 20.6f)
            verticalLineTo(17.8f)
            horizontalLineTo(6f)
            curveTo(4.45f, 17.8f, 3.2f, 16.55f, 3.2f, 15f)
            verticalLineTo(6.6f)
            curveTo(3.2f, 5.05f, 4.45f, 3.8f, 6f, 3.8f)
            close()
        }.andPath(width = 1.1f) {
            // 弓: keep open counters readable at the same control size as 字幕 and 音轨.
            moveTo(6.7f, 7.1f)
            horizontalLineTo(9.5f)
            verticalLineTo(9.5f)
            horizontalLineTo(6.9f)
            lineTo(6.6f, 11.9f)
            horizontalLineTo(9.5f)
            lineTo(9.2f, 14.8f)
            curveTo(9.1f, 15.5f, 8.5f, 15.7f, 7.6f, 15.3f)
            // 单.
            moveTo(12.1f, 6.7f)
            lineTo(12.8f, 7.8f)
            moveTo(16.7f, 6.7f)
            lineTo(16f, 7.8f)
            moveTo(11.9f, 9.1f)
            horizontalLineTo(17f)
            verticalLineTo(12.4f)
            horizontalLineTo(11.9f)
            close()
            moveTo(12f, 10.75f)
            horizontalLineTo(16.9f)
            moveTo(14.45f, 9.1f)
            verticalLineTo(15.8f)
            moveTo(11.3f, 14.3f)
            horizontalLineTo(17.6f)
        }.build()

    /** Room chat — a speech bubble with three dots. */
''' + s[end:]
write(p, s)
p = 'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerChromeRefined.kt'
replace(p, '''                ChromeLabeledAction(
                    icon = AppIcons.Danmaku,
                    label = "弹幕",
                    active = danmakuEnabled,''', '''                CircleControl(
                    icon = AppIcons.Danmaku,
                    description = if (danmakuEnabled) "弹幕，已开启" else "弹幕，已关闭",
                    size = 26.dp,
                    iconSize = 12.dp,
                    active = danmakuEnabled,''')
replace(p, '''                    ChromeLabeledAction(
                        icon = AppIcons.EpisodeList,
                        label = "选集",''', '''                    CircleControl(
                        icon = AppIcons.EpisodeList,
                        description = "选集",
                        size = 26.dp,
                        iconSize = 12.dp,''')
s = read(p)
start = s.index('@Composable\nprivate fun ChromeLabeledAction(')
end = s.index('@Composable\nprivate fun RefinedSpeedControl(', start)
write(p, s[:start] + s[end:])
p = 'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt'
replace(p, '    filled: Boolean = false,\n    onClick:', '    filled: Boolean = false,\n    active: Boolean = false,\n    onClick:')
replace(p, '                        it.border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape)', '''                        it
                            .background(
                                if (active) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                CircleShape,
                            ).border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape)''')
manifest = 'audit/runtime-optimization-20260910/format-files.txt'
s = read(manifest)
for p in ['composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/AppIcons.kt',
          'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerChromeRefined.kt']:
    if p not in s: s += p + '\n'
write(manifest, s)
