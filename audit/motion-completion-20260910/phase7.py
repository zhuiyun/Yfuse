from edit import read, write, replace
import re
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
# Remove the private rail subtree: all production call sites are guarded by a policy that always returns false.
# Keep the public policy seam and TV focus implementation (different sources and callers).
p=C+'app/App.kt';s=read(p)
s=s.replace('BoxWithConstraints(Modifier.fillMaxSize()) {\n                        val expandedNavigation = useNavigationRail(maxWidth, maxHeight)', 'Box(Modifier.fillMaxSize()) {')
start=s.index('                        // A rail reserves horizontal space')
end=s.index('                        val onSelectTab:',start)
s=s[:start]+s[end:]
s=s.replace('                                .padding(start = if (navigationRailActive) 104.dp else 0.dp)\n','')
s=s.replace('if (expandedNavigation || !showBottomBar)', 'if (!showBottomBar)')
start=s.index('                            if (expandedNavigation) {\n                                GlassNavigationRail(')
end=s.index('                                BottomNavigationDock(',start)
s=s[:start]+s[end:]
s=s.replace('''                                )
                            }
                            // One slot above the tab bar''','''                                )
                            // One slot above the tab bar''',1)
s=s.replace('                                    .padding(start = if (expandedNavigation) 104.dp else 0.dp)\n','')
s=s.replace('''                                            if (expandedNavigation) {
                                                Dimens.tabBarInset
                                            } else {
                                                Dimens.tabBarHeight + 22.dp
                                            },''','''                                            Dimens.tabBarHeight + 22.dp,''')
start=s.rfind('@Composable',0,s.index('private fun GlassNavigationRail('))
end=s.index('/**\n * A tab glyph in its optical box.',start)
s=s[:start]+s[end:]
s=s.replace('SearchDockOrigin.begin()','if (!selected) SearchDockOrigin.begin()')
write(p,s)
# Hide decorative clocks when retained routes are not visible.
replace(C+'core/designsystem/OrbProgress.kt','if (reduceMotion) null else rememberInfiniteTransition', 'if (reduceMotion || !LocalRouteVisible.current) null else rememberInfiniteTransition')
# Keep public constants constant for downstream source compatibility.
replace(C+'core/designsystem/WaitingPulse.kt','val WAITING_PULSE_DELAY_MS = Motion.STANDARD.toLong()', 'const val WAITING_PULSE_DELAY_MS = Motion.STANDARD * 1L')
replace(C+'core/designsystem/PageStates.kt','private val SKELETON_PULSE_MS = Motion.SKELETON_PULSE.toFloat()', 'private const val SKELETON_PULSE_MS = Motion.SKELETON_PULSE * 1f')
replace(C+'core/designsystem/PageStates.kt','internal val SKELETON_SWEEP_MS = Motion.SKELETON_SWEEP.toFloat()', 'internal const val SKELETON_SWEEP_MS = Motion.SKELETON_SWEEP * 1f')
replace(C+'core/designsystem/Tokens.kt','    const val PLAYER_SEEK_FEEDBACK = 420','''    const val PLAYER_SEEK_FEEDBACK = 420
    const val AMBIENT_LIGHT_FADE = 600
    const val CONTINUITY_ENTER = 140
    const val CONTINUITY_EXIT = 320
    const val OLED_PROTECTION = 450''')
replace(C+'core/designsystem/AmbientLight.kt','AMBIENT_LIGHT_FADE_MS = Motion.CAROUSEL_COLOR', 'AMBIENT_LIGHT_FADE_MS = Motion.AMBIENT_LIGHT_FADE')
p=C+'feature/player/PlaybackExperienceOverlay.kt';s=read(p)
s=s.replace('import com.yfuse.core.designsystem.OrbProgress','import com.yfuse.core.designsystem.OrbProgress\nimport com.yfuse.core.designsystem.Motion')
s=s.replace('CONTINUITY_ENTER_MS = 140','CONTINUITY_ENTER_MS = Motion.CONTINUITY_ENTER').replace('CONTINUITY_EXIT_MS = 320','CONTINUITY_EXIT_MS = Motion.CONTINUITY_EXIT')
s=re.sub(r'(\w+_MS) = 450\b',r'\1 = Motion.OLED_PROTECTION',s)
write(p,s)
p=C+'core/designsystem/BackOverlay.kt'
replace(p,'    val settling = remember', '    val overlayShape = remember { RoundedCornerShape(16.dp) }\n    val settling = remember')
replace(p,'shape = RoundedCornerShape(16.dp)','shape = overlayShape')
# A readiness note should not hard-cut into the video when chrome reappears.
p=C+'feature/player/PlayerControls.kt';s=read(p)
s=s.replace('''        if (watch.connected && visible) {''','''        ChromeVisibility(
            visible = watch.connected && visible,
            edge = ChromeEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 74.dp),
        ) {''',1)
s=s.replace('''                        .align(Alignment.TopCenter)
                        .padding(top = 74.dp)
                        .glass(''','''                        .glass(''',1)
write(p,s)
# Keep the last decoded cell as the next request's placeholder. Coil owns eviction; only its key is retained.
p=A+'feature/player/TrickplayImage.android.kt';s=read(p)
s=s.replace('import androidx.compose.runtime.Composable','''import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import coil3.memory.MemoryCache''')
s=s.replace('    val context = LocalContext.current','''    val context = LocalContext.current
    var lastFrameKey by remember(storyboard) { mutableStateOf<MemoryCache.Key?>(null) }''',1)
s=s.replace('    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion', '''    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion &&
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f''')
s=s.replace('.data(frame.url)','.data(frame.url)\n                .placeholderMemoryCacheKey(lastFrameKey)',1)
s=s.replace('        contentDescription = description,','        contentDescription = description,\n        onSuccess = { lastFrameKey = it.result.memoryCacheKey },',1)
write(p,s)
