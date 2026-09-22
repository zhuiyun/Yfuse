from edit import read,write
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
p=C+'core/designsystem/ThemeColorContent.kt';s=read(p).replace('onTextLayout = onTextLayout,','onTextLayout = onTextLayout ?: {},');write(p,s)
p=C+'core/designsystem/DolbyMark.kt';s=read(p).replace(''' */

/** The official double-D silhouette uses counters; two solid half-discs read as brackets. */''',''' * The silhouette uses counters; two solid half-discs read as brackets.
 */''');write(p,s)

p=C+'feature/player/PlayerControls.kt';s=read(p)
# The existing BackOverlay/ChromeContent lambda is already a restart scope for panel-only subscriptions.
s=s.replace('    castPosition: String? = null,','    castPosition: String? = null,\n    castPositionSource: (() -> String?)? = null,')
s=s.replace('                            castPosition = castPosition,','                            castPosition = castPositionSource?.invoke() ?: castPosition,')
s=s.replace('                            audioControls = audioControls,','                            audioControls = audioControls.copy(measuredAvOffsetMs = playback.value.diagnostics.avSyncOffsetMs),')
write(p,s)
p=A+'feature/player/PlayerRoot.kt';s=read(p)
s=s.replace('''                    state = castState,
                    fallbackPositionMs''','''                    state = liveCastState.value,
                    fallbackPositionMs''')
start=s.index('                    castPosition =\n');end=s.index('                    castCapabilities =',start)
chunk=s[start:end].replace('castPosition =','castPositionSource = {').replace('castState.', 'liveCastState.value.')
# turn the nullable let expression's trailing comma into the function's closing brace
last=chunk.rfind('                        },')
assert last>=0
chunk=chunk[:last]+chunk[last:].replace('                        },','                        }\n                    },',1)
s=s[:start]+chunk+s[end:]
s=s.replace('                                    castState.positionMs','                                    liveCastState.value.positionMs')
# Runtime diagnostics must not turn sampled numbers into routing changes.
write(p,s)
p=C+'feature/player/PlaybackRuntimeProjection.kt';s=read(p).replace('audioUnderrunCount = 0, mistimedFrameCount = 0','audioUnderrunCount = 0, mistimedFrameCount = 0, rendererDetail = ""');write(p,s)

# Resolve seconds before crossing the text restart boundary.
p=C+'feature/player/PlayerChromeRefined.kt';s=read(p)
s=s.replace('RefinedTimeText(shownPosition)','RefinedTimeText(shownPosition.coerceAtLeast(0L) / 1_000L)').replace('RefinedTimeText(state.durationMs)','RefinedTimeText(state.durationMs.coerceAtLeast(0L) / 1_000L)')
s=s.replace('private fun RefinedTimeText(timeMs: Long)', 'private fun RefinedTimeText(seconds: Long)').replace('formatTime(timeMs.coerceAtLeast(0L)),','formatTime(seconds * 1_000L),')
s=s.replace('            PlayerClock()','            PlayerClock()\n            PlayerBatteryStatus()')
write(p,s)

write(C+'feature/player/PlayerBatteryStatus.kt','''package com.yfuse.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ThemeText

internal data class PlayerBattery(val percent: Int, val charging: Boolean)

internal fun playerBatteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else (level.toLong() * 100 / scale).toInt().coerceIn(0, 100)

@Composable
internal expect fun rememberPlayerBattery(): State<PlayerBattery?>

/** Lives inside the same ChromeVisibility as the top controls; no receiver survives its dismissal. */
@Composable
internal fun PlayerBatteryStatus() {
    val battery by rememberPlayerBattery()
    val current = battery ?: return
    val color = Color.White.copy(alpha = 0.82f)
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = "电量 ${current.percent}%" + if (current.charging) "，正在充电" else ""
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(21.dp, 11.dp)) {
            val stroke = 1.dp.toPx()
            val bodyWidth = size.width - 3.dp.toPx()
            drawRoundRect(color, Offset(stroke / 2, stroke / 2), Size(bodyWidth - stroke, size.height - stroke),
                CornerRadius(2.dp.toPx()), style = Stroke(stroke))
            drawRoundRect(color, Offset(bodyWidth + 1.dp.toPx(), size.height * 0.3f),
                Size(2.dp.toPx(), size.height * 0.4f), CornerRadius(0.6.dp.toPx()))
            val inset = 2.5.dp.toPx()
            drawRoundRect(color, Offset(inset, inset),
                Size((bodyWidth - inset * 2) * current.percent / 100f, size.height - inset * 2), CornerRadius(0.6.dp.toPx()))
        }
        ThemeText("${current.percent}%${if (current.charging) " +" else ""}", style = AppTypography.caption.strong,
            color = color, maxLines = 1)
    }
}
''')
write(A+'feature/player/PlayerBatteryStatus.android.kt','''package com.yfuse.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun rememberPlayerBattery(): State<PlayerBattery?> {
    val context = LocalContext.current.applicationContext
    val status = remember(context) { mutableStateOf<PlayerBattery?>(null) }
    DisposableEffect(context) {
        fun update(intent: Intent?) {
            if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
                status.value = null
                return
            }
            val percent = playerBatteryPercent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            status.value = percent?.let { PlayerBattery(it, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
        }
        val sticky = ContextCompat.registerReceiver(context, receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        update(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}
''')
