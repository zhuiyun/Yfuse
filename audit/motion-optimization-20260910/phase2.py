import re
from edit import read, write, replace
base = 'composeApp/src/commonMain/kotlin/com/yfuse/'

p = base + 'core/designsystem/ThemeCrossfade.kt'
s = read(p).replace('import androidx.compose.runtime.Immutable', 'import androidx.compose.runtime.Immutable\nimport androidx.compose.runtime.State')
s = s.replace('): ThemeColors {\n    val state = remember', '): State<ThemeColors> {\n    val state = remember', 1)
s = s.replace('    return state.shown()', '    return state', 1)
s = s.replace('    initial: ThemeColors,\n) {', '    initial: ThemeColors,\n) : State<ThemeColors> {', 1)
s = s.replace('    fun shown(): ThemeColors {', '    override val value: ThemeColors get() = shown()\n\n    fun shown(): ThemeColors {', 1)
write(p, s)
p = base + 'core/designsystem/Theme.kt'
s = read(p).replace('import androidx.compose.runtime.Immutable', 'import androidx.compose.runtime.Immutable\nimport androidx.compose.runtime.State\nimport androidx.compose.runtime.compositionLocalOf')
s = s.replace('val LocalPalette = staticCompositionLocalOf', 'val LocalPalette = compositionLocalOf')
s = s.replace('val LocalAccentColors =\n    staticCompositionLocalOf', 'val LocalAccentColors =\n    compositionLocalOf')
s = s.replace('val LocalArtworkAccent = staticCompositionLocalOf', 'val LocalArtworkAccent = compositionLocalOf')
s = s.replace('    val (palette, accentColors) =\n        rememberThemeCrossfade(', '    val animatedColors =\n        rememberThemeCrossfade(')
s = s.replace('''        LocalPalette provides palette,
        LocalAccentColors provides accentColors,
        LocalAccessibilityOptions provides accessibility,''', '''        LocalAccessibilityOptions provides accessibility,''')
old = '''        MaterialTheme(
            colorScheme = if (dark) darkScheme(accentColors) else lightScheme(accentColors),
            typography = AppTypography.material,
            shapes = AppShapes.material,
            content = { DialogBackdropHost(content) },
        )'''
new = '''        AnimatedThemeColors(dark, animatedColors) { DialogBackdropHost(content) }'''
assert old in s
s = s.replace(old, new)
s += '''
/** Animation reads live below density/accessibility/glass providers and notify colour readers only. */
@Composable
private fun AnimatedThemeColors(
    dark: Boolean,
    colors: State<ThemeColors>,
    content: @Composable () -> Unit,
) {
    val shown = colors.value
    val scheme = remember(dark, shown.accent) {
        if (dark) darkScheme(shown.accent) else lightScheme(shown.accent)
    }
    CompositionLocalProvider(
        LocalPalette provides shown.palette,
        LocalAccentColors provides shown.accent,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography.material,
            shapes = AppShapes.material,
            content = content,
        )
    }
}
'''
write(p, s)

p = base + 'core/designsystem/DominantColor.kt'
s = read(p).replace('import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.Stable\nimport androidx.compose.runtime.State')
start = s.index('    val reduceMotion =', s.index('fun rememberAnimatedDominantColor('))
end = s.index('\n}', start)
s = s[:start] + '    return rememberAnimatedColorState(target, durationMillis)' + s[end:]
start = s.index('    val reduceMotion =', s.index('fun rememberAnimatedArtworkAccent('))
end = s.index('\n}', start)
s = s[:start] + '    return rememberAnimatedColorState(target, durationMillis)' + s[end:]
s = s.replace('durationMillis: Int = Motion.ACCENT,\n): Color {', 'durationMillis: Int = Motion.ACCENT,\n): AnimatedColorState {')
s += '''
/** The semantic target changes once; consumers opt into the intermediate paint values. */
@Stable
class AnimatedColorState internal constructor(
    val target: Color,
    private val animated: State<Color>,
) : State<Color> {
    override val value: Color get() = animated.value
}

@Composable
fun rememberAnimatedColorState(
    target: Color,
    durationMillis: Int = Motion.ACCENT,
): AnimatedColorState {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val animated = animateColorAsState(
        targetValue = target,
        animationSpec = tween(if (reduceMotion) 0 else durationMillis, easing = Motion.Curve),
        label = "localArtworkAccent",
    )
    return remember(target, animated) { AnimatedColorState(target, animated) }
}

/** Restart boundary for text/material controls whose paint API takes a Color instead of a lambda. */
@Composable
fun AnimatedColorContent(
    color: State<Color>,
    content: @Composable (Color) -> Unit,
) {
    content(color.value)
}
'''
write(p, s)

def call_end(s, start):
    # Find the matching call parenthesis without counting quoted strings or comments.
    i = s.index('(', start)
    depth = 0
    while i < len(s):
        if s.startswith('//', i):
            i = s.index('\n', i)
            continue
        if s.startswith('/*', i):
            i = s.index('*/', i) + 2
            continue
        if s[i] in '\"\'':
            q = s[i]
            i += 1
            while i < len(s):
                if s[i] == '\\': i += 2; continue
                if s[i] == q: i += 1; break
                i += 1
            continue
        if s[i] == '(': depth += 1
        if s[i] == ')':
            depth -= 1
            if depth == 0: return i + 1
        i += 1
    raise AssertionError('Unclosed call')

def wrap_call(s, name, state, alias):
    matches = list(re.finditer(r'(?m)^( +)' + name + r'\(', s))
    assert len(matches) == 1, (name, len(matches))
    m = matches[0]
    start, indent = m.start(), m[1]
    end = call_end(s, m.end() - 1)
    original = s[start:end]
    body = '\n'.join('    ' + line for line in original.splitlines())
    return s[:start] + indent + f'AnimatedColorContent({state}) {{ {alias} ->\n' + body + '\n' + indent + '}' + s[end:]

p = base + 'feature/detail/DetailScreen.kt'
s = read(p).replace('import com.yfuse.core.designsystem.ArtworkPageTheme', 'import com.yfuse.core.designsystem.ArtworkPageTheme\nimport com.yfuse.core.designsystem.AnimatedColorContent\nimport com.yfuse.core.designsystem.rememberAnimatedColorState')
s = s.replace('    val detailAccent =\n        rememberAnimatedArtworkAccent(', '    val detailAccentState =\n        rememberAnimatedArtworkAccent(')
s = s.replace('    var seasonPickerOpen', '    val detailAccent = detailAccentState.target\n    var seasonPickerOpen', 1)
for name in ['TitleBlock', 'OverviewSection', 'EpisodeSection', 'VersionSection', 'TrackSection', 'SourceSection', 'RelatedSection', 'SeasonPickerOverlay', 'SourceListDialog', 'AllEpisodesDialog', 'ProgressEpisodesDialog', 'ActionToast']:
    # These two dialog names are resolved by their call containing the relevant accent argument below.
    if re.search(r'(?m)^ +' + name + r'\(', s):
        s = wrap_call(s, name, 'detailAccentState', 'detailAccent')
# Cover any differently named episode dialogs by looking at the actual call whose argument is accent.
for match in list(re.finditer(r'(?m)^ +accent = detailAccent,', s))[::-1]:
    # Already wrapped calls have the state boundary; find the preceding call line for remaining dialogs.
    prefix = s[:match.start()]
    opening = list(re.finditer(r'(?m)^( +)([A-Z][A-Za-z0-9]+)\(', prefix))
    if not opening: continue
    last = opening[-1]
    name = last[2]
    if name in ['ArtworkPageTheme', 'AnimatedColorContent']: continue
    # If this call has already been wrapped, its preceding nonempty line is the wrapper.
    if s[:last.start()].rstrip().endswith('detailAccent ->'): continue
    if name in ['EpisodeGridDialog', 'EpisodeProgressDialog']:
        s = wrap_call(s, name, 'detailAccentState', 'detailAccent')
s = s.replace('''            ArtworkPageTheme(
                background = detailSurface,''', '''            val detailPlayColorState = rememberAnimatedColorState(detailPlayColor)
            ArtworkPageTheme(
                background = detailSurface,''')
for name in ['DetailActionDock', 'DetailTopBar']:
    s = wrap_call(s, name, 'detailPlayColorState', 'detailPlayColor')
write(p, s)

p = base + 'feature/home/TmdbInfoScreen.kt'
s = read(p).replace('rememberAnimatedArtworkAccent', 'rememberArtworkAccentTarget')
s = s.replace('import com.yfuse.core.designsystem.ArtworkPageTheme', 'import com.yfuse.core.designsystem.ArtworkPageTheme\nimport com.yfuse.core.designsystem.AnimatedColorContent\nimport com.yfuse.core.designsystem.rememberAnimatedColorState')
s = s.replace('val themeAccent = LocalAccentColors.current.accent', 'val themeAccentState = rememberAnimatedColorState(LocalAccentColors.current.accent)')
for name in ['TmdbPlayDock', 'TmdbSourceStrip']:
    s = wrap_call(s, name, 'themeAccentState', 'themeAccent')
# The remaining consumers are individual icon/loader calls, not whole rows/screens.
for marker in ['tint = themeAccent,', 'OrbProgress(size = 18.dp, color = themeAccent)']:
    at = s.index(marker)
    m = list(re.finditer(r'(?m)^( +)(Icon|OrbProgress)\(', s[:at + len(marker)]))[-1]
    start, indent = m.start(), m[1]
    end = call_end(s, m.end() - 1)
    original = s[start:end]
    s = s[:start] + indent + 'AnimatedColorContent(themeAccentState) { themeAccent ->\n' + '\n'.join('    ' + line for line in original.splitlines()) + '\n' + indent + '}' + s[end:]
write(p, s)
p = base + 'feature/player/PlayerChromeRefined.kt'
replace(p, '{ ambientSeekAccent(ambientLight?.value, artworkAccent) }', '{ ambientSeekAccent(ambientLight?.value, artworkAccent.value) }')
