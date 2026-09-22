from edit import read, write, replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
replace(C+'app/App.kt','package com.yfuse.app\n','package com.yfuse.app\n\nimport com.yfuse.core.designsystem.SearchDockOrigin\nimport com.yfuse.core.designsystem.searchDockSource\n')
replace(C+'feature/player/WatchTogetherDialogs.kt','.width(108.dp).motionAwareItem()', '.width(108.dp).then(motionAwareItem())')
replace(A+'feature/player/PlayerWatchSyncEffects.kt','playbackState.currentIndex','playbackState.value.currentIndex')
p=A+'feature/player/PlayerWatchSyncEffects.kt'
replace(p,'val latestPlayer by rememberUpdatedState(player)','val latestItems by rememberUpdatedState(items)\n    val latestPlayer by rememberUpdatedState(player)')
replace(p,'mediaMatcher.resolve(items, timeline.mediaKey)','mediaMatcher.resolve(latestItems, timeline.mediaKey)')
write(A+'feature/player/PlayerActivityMotion.android.kt','''package com.yfuse.feature.player

import android.app.Activity
import android.os.Build
import android.provider.Settings
import com.yfuse.R
import com.yfuse.core.data.ThemePreferences
import org.koin.core.context.GlobalContext

private fun Activity.playerWindowMotionEnabled(): Boolean {
    val reduced = runCatching { GlobalContext.get().get<ThemePreferences>().reduceMotion.value }.getOrDefault(false)
    val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return !reduced && scale > 0f && !isInPictureInPictureMode
}

/** Window animations include SurfaceView layers; no bitmap capture or delayed player release. */
@Suppress("DEPRECATION")
internal fun Activity.configurePlayerWindowMotion() {
    val moving = playerWindowMotionEnabled()
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,
            if (moving) R.anim.player_enter else 0, if (moving) R.anim.player_hold_enter else 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0, if (moving) R.anim.player_exit else 0)
    } else {
        overridePendingTransition(if (moving) R.anim.player_enter else 0, if (moving) R.anim.player_hold_enter else 0)
    }
}

@Suppress("DEPRECATION")
internal fun Activity.finishPlayerWindowMotion(wasPictureInPicture: Boolean) {
    val moving = !wasPictureInPicture && playerWindowMotionEnabled()
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(if (moving) R.anim.player_hold_exit else 0, if (moving) R.anim.player_exit else 0)
    } else {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0, if (moving) R.anim.player_exit else 0)
    }
}
''')
# Resolve actual package instead of assuming the preference storage module.
s=read(A+'feature/player/PlayerActivity.kt')
import re
pref=re.search(r'import ([\w.]+ThemePreferences)',s).group(1)
replace(A+'feature/player/PlayerActivityMotion.android.kt','com.yfuse.core.data.ThemePreferences',pref)
s=s.replace('        super.onCreate(savedInstanceState)','        super.onCreate(savedInstanceState)\n        configurePlayerWindowMotion()',1)
s=s.replace('    private fun closePlayerAndReturn() {','''    override fun finish() {
        // Apply the close policy before finish on API 34+, then support the legacy API afterward.
        if (android.os.Build.VERSION.SDK_INT >= 34) finishPlayerWindowMotion(pipWasVisible)
        super.finish()
        if (android.os.Build.VERSION.SDK_INT < 34) finishPlayerWindowMotion(pipWasVisible)
    }

    private fun closePlayerAndReturn() {''')
write(A+'feature/player/PlayerActivity.kt',s)
R='composeApp/src/androidMain/res/'
write(R+'values/player_motion.xml','''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Window equivalents of Motion.EMPHASIZED and the compact dialog exit. -->
    <integer name="player_enter_duration">280</integer>
    <integer name="player_exit_duration">240</integer>
</resources>
''')
for name,duration,y0,y1,a0,a1 in [('player_enter','enter','5%','0%','0','1'),('player_exit','exit','0%','5%','1','0')]:
    write(R+'anim/'+name+'.xml',f'''<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android" android:shareInterpolator="true"
    android:interpolator="@android:interpolator/fast_out_slow_in">
    <translate android:fromYDelta="{y0}" android:toYDelta="{y1}" android:duration="@integer/player_{duration}_duration" />
    <alpha android:fromAlpha="{a0}" android:toAlpha="{a1}" android:duration="@integer/player_{duration}_duration" />
</set>
''')
for duration in ['enter','exit']:
    write(R+'anim/player_hold_'+duration+'.xml',f'''<?xml version="1.0" encoding="utf-8"?>
<alpha xmlns:android="http://schemas.android.com/apk/res/android" android:fromAlpha="1" android:toAlpha="1"
    android:duration="@integer/player_{duration}_duration" />
''')
# Draw-only spatial feedback; it has no gesture recognizer and never controls playback.
write(C+'feature/player/SeekBurstFeedback.kt','''package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion

@Composable
internal fun SeekBurstFeedback(revision: Int, position: Offset, itemKey: Any) {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val pulse = remember(itemKey) { Animatable(1f) }
    LaunchedEffect(revision, moving, pulse) {
        if (revision == 0 || !moving) pulse.snapTo(1f)
        else {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(Motion.PLAYER_SEEK_FEEDBACK, easing = Motion.Curve))
        }
    }
    if (moving) Canvas(Modifier.fillMaxSize()) {
        val remaining = 1f - pulse.value
        if (remaining > 0f) {
            val radius = (24.dp.toPx() + 56.dp.toPx() * pulse.value).coerceAtMost(size.minDimension / 2f)
            drawCircle(Color.White.copy(alpha = 0.10f * remaining), radius, position)
            drawCircle(Color.White.copy(alpha = 0.45f * remaining), radius, position, style = Stroke(1.5.dp.toPx()))
        }
    }
}
''')
p=C+'feature/player/PlayerControls.kt';s=read(p)
s=s.replace('    var seekBurstDirection by', '''    var seekPulseRevision by remember(state.currentIndex) { mutableIntStateOf(0) }
    var seekPulsePosition by remember { mutableStateOf(Offset.Zero) }
    var seekBurstDirection by''',1)
s=s.replace('                                    seekBurstDirection = direction','''                                    seekPulsePosition = offset
                                    seekPulseRevision++
                                    seekBurstDirection = direction''',1)
s=s.replace('        if (volumeSliderVisible) {','''        SeekBurstFeedback(seekPulseRevision, seekPulsePosition, state.currentIndex)
        ChromeVisibility(
            visible = volumeSliderVisible,
            edge = ChromeEdge.End,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp),
        ) {''',1)
s=s.replace('''                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 26.dp),''','''                modifier = Modifier,''',1)
write(p,s)
# Coil handles replacement/cancellation; no additional bitmap cache or full storyboard decode.
p=A+'feature/player/TrickplayImage.android.kt';s=read(p)
s=s.replace('import coil3.request.ImageRequest','import coil3.request.ImageRequest\nimport coil3.request.crossfade\nimport com.yfuse.core.designsystem.LocalAccessibilityOptions\nimport com.yfuse.core.designsystem.LocalRouteVisible\nimport com.yfuse.core.designsystem.Motion')
s=s.replace('    val context = LocalContext.current','    val context = LocalContext.current\n    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion',1)
s=s.replace('remember(context, frame, columns, rows, width, height)', 'remember(context, frame, columns, rows, width, height, moving)')
s=s.replace('.data(frame.url)', '.data(frame.url)\n                .crossfade(if (moving) Motion.QUICK else 0)',1)
write(p,s)
# Duration aliases preserve public constants and configuration compatibility.
changes={
 'core/designsystem/ArrivalReveal.kt': [('REVEAL_MS = 480','REVEAL_MS = Motion.ARRIVAL_REVEAL'),('ATTENTION_SWEEP_MS = 520','ATTENTION_SWEEP_MS = Motion.ATTENTION_SWEEP')],
 'core/designsystem/BurstIcon.kt':[('BURST_MS = 420','BURST_MS = Motion.BURST'),('RELEASE_MS = 200','RELEASE_MS = Motion.BURST_RELEASE')],
 'core/designsystem/WaitingPulse.kt':[('WAITING_PULSE_LEG_MS = 850','WAITING_PULSE_LEG_MS = Motion.WAIT_HALF_CYCLE'),('WAITING_PULSE_DELAY_MS = 180L','WAITING_PULSE_DELAY_MS = Motion.STANDARD.toLong()')],
 'core/designsystem/PageStates.kt':[('SKELETON_PULSE_MS_INT = 1_600','SKELETON_PULSE_MS_INT = Motion.SKELETON_PULSE'),('SKELETON_PULSE_MS = 1_600f','SKELETON_PULSE_MS = Motion.SKELETON_PULSE.toFloat()'),('SKELETON_SWEEP_MS = 2_800f','SKELETON_SWEEP_MS = Motion.SKELETON_SWEEP.toFloat()'),('SKELETON_PHASE_STEP_MS = 110','SKELETON_PHASE_STEP_MS = Motion.SKELETON_PHASE_STEP')],
 'core/designsystem/AmbientLight.kt':[('AMBIENT_LIGHT_FADE_MS = 600','AMBIENT_LIGHT_FADE_MS = Motion.CAROUSEL_COLOR')],
 'core/designsystem/Dialogs.kt':[('OVERLAY_EXIT_DURATION_MS = 240','OVERLAY_EXIT_DURATION_MS = Motion.Dialog.EXIT_QUICK')],
}
for path,edits in changes.items():
    s=read(C+path)
    for old,new in edits:
        assert old in s,old
        s=s.replace(old,new)
    # Kotlin const initializers cannot invoke numeric conversion.
    s=s.replace('const val WAITING_PULSE_DELAY_MS','val WAITING_PULSE_DELAY_MS').replace('const val SKELETON_PULSE_MS =','val SKELETON_PULSE_MS =').replace('const val SKELETON_SWEEP_MS =','val SKELETON_SWEEP_MS =')
    write(C+path,s)
