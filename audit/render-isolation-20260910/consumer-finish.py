from edit import read, write
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
p=C+'core/designsystem/ThemeColorConsumer.kt'
s=read(p)
s=s.replace('''        remember(theme) {
            if (theme != memory.theme &&
                memory.color.isSpecified &&
                target.isSpecified &&
                memory.color != target &&''','''        remember(theme) {
            val visible = Snapshot.withoutReadObservation { memory.shown?.value ?: memory.color }
            if (theme != memory.theme &&
                visible.isSpecified &&
                target.isSpecified &&
                visible != target &&''')
s=s.replace('Animatable(Snapshot.withoutReadObservation { memory.shown?.value ?: memory.color })', 'Animatable(visible)')
s=s.replace('''    LaunchedEffect(animation, target, reduced) {
        if (animation != null && !finished) {''','''    if (animation != null && !finished) {
        LaunchedEffect(animation, target, reduced) {''')
write(p,s)
p=C+'core/designsystem/ThemeColorContent.kt'
write(p,read(p).replace('onTextLayout = onTextLayout ?: {},', 'onTextLayout = onTextLayout,',1))
p=A+'feature/player/PlaybackBookmarkBinding.kt'
s=read(p).replace('onSave = { title, note -> update { save(it, positionMs().coerceAtLeast(0), title, note) } },', '''onSave = { title, note ->
                val capturedPosition = positionMs().coerceAtLeast(0)
                update { save(it, capturedPosition, title, note) }
            },''')
write(p,s)
p=A+'feature/player/PlayerDanmakuCoordinator.kt'
s=read(p).replace('''                if (activeSource != null && activeEpisodeId != null) {
                    sending = true''','''                if (activeSource != null && activeEpisodeId != null) {
                    val capturedPosition = positionMs()
                    sending = true''')
s=s.replace('positionMs = positionMs(),','positionMs = capturedPosition,')
write(p,s)
p='composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/ThemeCrossfadeStateTest.kt'
s=read(p).replace('import kotlinx.coroutines.test.runTest', 'import androidx.compose.ui.graphics.Color\nimport kotlinx.coroutines.test.runTest')
s=s.replace('class ThemeCrossfadeStateTest {','''class ThemeCrossfadeStateTest {
    @Test
    fun changing_accent_does_not_snap_an_in_progress_background_transition() = runTest {
        themeConsumerTest {
            theme.value = dark
            apply()
            advance(100)
            val before = shown.value
            theme.value = dark.copy(accent = resolveAccentColors(Color.Cyan, dark = true))
            apply()
            assertEquals(before, shown.value)
            assertNotEquals(dark.palette.background, shown.value)
            advance(500)
            assertEquals(dark.palette.background, shown.value)
        }
    }
''')
write(p,s)
