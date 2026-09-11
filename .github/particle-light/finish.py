from pathlib import Path

root = Path('.')
base = 'composeApp/src/commonMain/kotlin/com/yfuse/'

def edit(path, old, new, count=1):
    file = root / path
    text = file.read_text()
    assert text.count(old) == count, (path, old[:80], text.count(old))
    file.write_text(text.replace(old, new))

def imports(path, names):
    file = root / path
    text = file.read_text()
    insert = text.index('\nimport ')
    added = ''.join('\nimport com.yfuse.core.designsystem.' + name for name in names if 'import com.yfuse.core.designsystem.' + name + '\n' not in text)
    file.write_text(text[:insert] + added + text[insert:])

# Compiler findings from the real Android branch build.
for path, symbol in [(base + 'app/App.kt', 'androidx.compose.runtime.derivedStateOf'), (base + 'feature/player/PlayerChrome.kt', 'androidx.compose.runtime.rememberUpdatedState')]:
    file = root / path
    text = file.read_text()
    assert 'import ' + symbol not in text
    file.write_text(text.replace('\nimport ', '\nimport ' + symbol + '\nimport ', 1))
file = root / 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt'
text = file.read_text()
assert '@file:' not in text[:200]
file.write_text('@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)\n\n' + text)

# The completed native build is reusable only if the existing source-input diff gate passes.
edit('.github/workflows/sign-android-branch.yml', 'native_source=806db9befd069aa83d50090261c2387fcf7f3905', 'native_source=033aff7328dd1e0b4dbeb9c2c00436aaafdfd1a6')
edit('.github/workflows/sign-android-branch.yml', '"34166656470"', '"34624144758"')

# An attachment consumes its appearance even if accessibility/policy suppresses it.
file = root / (base + 'core/designsystem/LightParticles.kt')
file.write_text(file.read_text() + '''
internal class LightAppearanceGate {
    private var consumed = false

    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

/** For newly created notices/previews, never for recycled media rows. */
@Composable
internal fun Modifier.lightOnAppear(
    effect: LightEffect = LightEffect.Edge,
    enabled: Boolean = true,
    enhancedOnly: Boolean = false,
): Modifier {
    val light = rememberLightFeedback(enabled, enhancedOnly)
    val gate = remember { LightAppearanceGate() }
    return lightFeedback(light).onSizeChanged { size ->
        light.resize(size.width, size.height)
        if (gate.consume()) light.emit(effect)
    }
}
''')

# Actual library-based home, including manual indicator navigation; auto-advance is silent.
path = base + 'feature/library/LibraryHomeScreen.kt'
imports(path, ['LightEffect', 'lightFeedback', 'rememberLightFeedback'])
edit(path, '    val carouselScope = rememberCoroutineScope()', '''    val carouselScope = rememberCoroutineScope()
    val carouselLight = rememberLightFeedback(enhancedOnly = true)
    LaunchedEffect(carouselDragging, carouselLight) {
        if (carouselDragging) carouselLight.emit(LightEffect.Dust)
    }''')
edit(path, ').carouselTouchPause(carouselTouched),', ').carouselTouchPause(carouselTouched).lightFeedback(carouselLight),')
edit(path, 'onPageSelected = { targetIndex ->\n                                                            interaction++', '''onPageSelected = { targetIndex ->
                                                            if (targetIndex != loopingCarouselItemIndex(pagerState.currentPage, slides.size)) {
                                                                carouselLight.emit(LightEffect.Dust)
                                                            }
                                                            interaction++''')

# Download boundaries, never download progress or initial completed rows.
path = base + 'feature/profile/DownloadsScreen.kt'
imports(path, ['LightEffect', 'lightOnChange'])
edit(path, '                    downloadStatusText(item),\n                    style', '''                    downloadStatusText(item),
                    modifier = Modifier.lightOnChange(
                        item.status,
                        if (item.status == DownloadStatus.Completed) LightEffect.Converge else LightEffect.Node,
                        emitWhen = item.status != DownloadStatus.Failed,
                    ),
                    style''')

# Common operation notices cover async server/account/copy/sync/cleanup outcomes.
# Neutral light does not infer success from arbitrary message text.
path = base + 'core/designsystem/Toast.kt'
edit(path, '    var dragging by remember { mutableStateOf(false) }', '''    var dragging by remember { mutableStateOf(false) }
    val exitLight = rememberLightFeedback(enhancedOnly = true)
    val currentExitLight by rememberUpdatedState(exitLight)''')
edit(path, '                    .padding(horizontal = Dimens.pageHorizontal)\n                    .graphicsLayer', '''                    .padding(horizontal = Dimens.pageHorizontal)
                    .lightOnAppear()
                    .lightFeedback(exitLight)
                    .graphicsLayer''')
edit(path, '                            ) {\n                                latestClose()\n                            } else {', '''                            ) {
                                currentExitLight.emit(LightEffect.Dissolve, directionX = if (offset < 0f) -1f else 1f)
                                latestClose()
                            } else {''')

# Player thumbnail and unread indicator are local; video pixels are untouched.
path = base + 'feature/player/PlayerChromeRefined.kt'
imports(path, ['lightOnAppear', 'lightOnChange'])
edit(path, '                    targetState = unreadChat,\n                    contentKey', '''                    targetState = unreadChat,
                    modifier = Modifier.lightOnChange(unreadChat, emitWhen = unreadChat),
                    contentKey''')
edit(path, '                                            y = 0,\n                                        )\n                                    },', '''                                            y = 0,
                                        )
                                    }.lightOnAppear(enabled = !reduceMotion),''')

# Enhanced detail artwork edge, without changing shared transition geometry.
path = base + 'feature/detail/DetailScreen.kt'
imports(path, ['lightOnAppear'])
edit(path, '                                motionItem(key = "hero") {\n                                    Box {', '''                                motionItem(key = "hero") {
                                    Box(Modifier.lightOnAppear(enhancedOnly = true)) {''')

# Existing logo clocks, no startup delay and no permanent frame loop.
path = 'composeApp/src/androidMain/kotlin/com/yfuse/core/designsystem/SplashPreview.android.kt'
edit(path, '    Canvas(modifier) { with(choreography) { drawMark(clock.value, mark) } }', '''    val lightCount = rememberPhaseLightCount(playing && visible && !reduceMotion, enhancedOnly = true)
    Canvas(modifier) {
        with(choreography) { drawMark(clock.value, mark) }
        drawPhaseLight(
            androidx.compose.ui.geometry.Rect(
                size.width * 0.2f, size.height * 0.2f, size.width * 0.8f, size.height * 0.8f,
            ),
            (clock.value / choreography.fadeStartMs).coerceIn(0f, 1f),
            lightCount,
            androidx.compose.ui.graphics.Color.White,
        )
    }''')
path = 'composeApp/src/androidMain/kotlin/com/yfuse/app/AnimatedSplashApp.kt'
imports(path, ['LightParticleBudget', 'LocalParticleBudget', 'LocalParticleLight', 'drawPhaseLight', 'rememberPhaseLightCount'])
edit(path, '        if (splashVisible) {\n            AnimatedSplashScreen(', '''        if (splashVisible) {
            val particleLight by root.themePreferences.particleLight.collectAsState()
            CompositionLocalProvider(
                LocalParticleLight provides particleLight,
                LocalParticleBudget provides remember { LightParticleBudget() },
            ) {
            AnimatedSplashScreen(''')
edit(path, '                onFinished = { splashVisible = false },\n            )\n        }', '''                onFinished = { splashVisible = false },
            )
            }
        }''')
edit(path, '    val entryColor = splashBackground(entryDark)', '''    val lightCount = rememberPhaseLightCount(!stillFrame, enhancedOnly = true)
    val entryColor = splashBackground(entryDark)''')
edit(path, '                with(choreography) { drawMark(clock.value, mark) }\n            }', '''                with(choreography) { drawMark(clock.value, mark) }
                drawPhaseLight(
                    androidx.compose.ui.geometry.Rect(
                        size.width * 0.25f, size.height * 0.3f, size.width * 0.75f, size.height * 0.7f,
                    ),
                    (clock.value / choreography.fadeStartMs).coerceIn(0f, 1f),
                    lightCount,
                    if (dark) Color.White else Color.Black,
                )
            }''')

# Shade-origin double tap/long press also cannot invoke seek/boost.
path = base + 'feature/player/PlayerControls.kt'
edit(path, '                        onDoubleTap = { offset ->\n                            if (latestWatchLocked)', '''                        onDoubleTap = { offset ->
                            if (!allowsPlayerDrag(offset.y, currentSystemGestureTop)) return@detectTapGestures
                            if (latestWatchLocked)''')
edit(path, '                        onLongPress = { offset ->\n                            // Thirds', '''                        onLongPress = { offset ->
                            if (!allowsPlayerDrag(offset.y, currentSystemGestureTop)) return@detectTapGestures
                            // Thirds''')
file = root / 'composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/LightParticlesTest.kt'
text = file.read_text()
end = text.rindex('\n}')
file.write_text(text[:end] + '''
    @Test
    fun appearanceCannotReplayAfterFocusOrSettingChange() {
        val gate = LightAppearanceGate()
        assertTrue(gate.consume())
        repeat(100) { assertFalse(gate.consume()) }
    }
''' + text[end:])
edit('release-notes.txt', '• 接入导航、弹窗、滑条、进度拖动、收藏、选中标记、按钮、遥控器焦点与手动轮播光感。', '• 接入导航、弹窗、滑条、进度拖动、收藏、选中标记、按钮、遥控器焦点与当前首页手动轮播光感。\n• 补齐下载状态、操作提示、缩略图预览和聊天未读反馈；增强档增加详情边缘、提示消散和启动 Logo 光点。')
print('Applied remaining Android particle surfaces and compile fixes')
