from edit import read, write

base='composeApp/src/commonMain/kotlin/com/yfuse/'
player=base+'feature/player/'
android='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/'

write(player+'PlaybackRuntimeContent.kt', '''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.StateFlow

/** Engine setup does not subscribe to playback ticks; only this runtime subtree reads them. */
@Composable
internal fun PlaybackRuntimeContent(
    owner: Any,
    source: StateFlow<PlaybackState>,
    content: @Composable (PlaybackState) -> Unit,
) {
    // Reset only the collector on handover. Runtime remembers (including timeline memory)
    // deliberately survive so a replacement backend can retain the last valid timeline.
    val current = key(owner, source) { source.collectAsState() }
    content(current.value)
}
''')
p=android+'PlayerRoot.kt'
s=read(p)
start=s.index('    // collectAsState keeps its previous slot value')
end=s.index('    var timelineMemory',start)
s=s[:start]+'''    PlaybackRuntimeContent(owner = engine, source = presentationState) { reportedLocalState ->
'''+s[end:]
end=s.index('\n}\n\nprivate const val HDR_DEFAULT_SUBTITLE_BRIGHTNESS')
s=s[:end]+'\n    }'+s[end:]
s=s.replace('BindPlaybackDiagnostics(state, kind, enginesTried, core2NativeOnlyActive)', 'BindPlaybackDiagnostics(rememberUpdatedState(state), kind, enginesTried, core2NativeOnlyActive)')
write(p,s)

p=android+'PlayerDiagnosticBinding.kt'
s=read(p).replace('import androidx.compose.runtime.SideEffect\n','import androidx.compose.runtime.State\nimport androidx.compose.runtime.snapshotFlow\n')
s=s.replace('    state: PlaybackState,\n    kind: PlayerEngine,\n    enginesTried:', '    state: State<PlaybackState>,\n    kind: PlayerEngine,\n    enginesTried:')
start=s.index('    SideEffect {')
end=s.index('\n}\n',start)
s=s[:start]+'''    LaunchedEffect(state, kind, fallback, nativeOnly) {
        snapshotFlow { state.value }.collect { snapshot ->
            pending.trySend(
                PendingDiagnostic(snapshot, kind, fallback, nativeOnly, PlaybackDiagnosticReportRegistry.nextSequence()),
            )
        }
    }'''+s[end:]
write(p,s)

write(player+'PlaybackTransportState.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.Immutable

/** Only fields consumed by the bottom timeline; diagnostic and track churn stay outside it. */
@Immutable
internal data class PlaybackTransportState(
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val speed: Float,
    val buttons: PlaybackButtonState,
)

/** Transport buttons have no position or buffer subscription. */
@Immutable
internal data class PlaybackButtonState(
    val playing: Boolean,
    val buffering: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val seekable: Boolean,
)

internal fun PlaybackState.transportState(): PlaybackTransportState = PlaybackTransportState(
    currentIndex = currentIndex,
    positionMs = positionMs,
    durationMs = durationMs,
    bufferedPositionMs = bufferedPositionMs,
    speed = speed,
    buttons = PlaybackButtonState(playing, buffering, hasPrevious, hasNext, durationMs > 0L),
)
''')
for name in ['PlayerChromeRefined.kt','PlayerChromeRefinedCompat.kt']:
    p=player+name
    s=read(p).replace('    state: PlaybackState,','    state: PlaybackTransportState,')
    if name=='PlayerChromeRefined.kt':
        s=s.replace('    val duration = state.durationMs.coerceAtLeast(1L)', '    val latestTransport by rememberUpdatedState(state)')
        s=s.replace('TransportRow(\n                state = state,','TransportRow(\n                state = state.buttons,')
        s=s.replace('onSeek((state.positionMs - REFINED_SEEK_STEP_MS).coerceAtLeast(0L))','onSeek((latestTransport.positionMs - REFINED_SEEK_STEP_MS).coerceAtLeast(0L))')
        s=s.replace('val target = state.positionMs + REFINED_SEEK_STEP_MS\n                    onSeek(if (state.durationMs > 0L) target.coerceAtMost(state.durationMs) else target)', 'val latest = latestTransport\n                    val target = latest.positionMs + REFINED_SEEK_STEP_MS\n                    onSeek(if (latest.durationMs > 0L) target.coerceAtMost(latest.durationMs) else target)')
    write(p,s)
p=player+'PlayerChrome.kt'
s=read(p)
start=s.index('internal fun TransportRow(')
end=s.index('\n}\n',start)
part=s[start:end].replace('state: PlaybackState','state: PlaybackButtonState').replace('state.durationMs > 0L','state.seekable')
s=s[:start]+part+s[end:]
write(p,s)
p=player+'PlayerControls.kt'
s=read(p).replace('RefinedBottomBar(\n                state = timelineState,','RefinedBottomBar(\n                state = timelineState.transportState(),')
write(p,s)

write(base+'feature/profile/DownloadTrackGeometry.kt','''package com.yfuse.feature.profile

import kotlin.math.roundToInt

internal data class DownloadTrackGeometry(
    val trackLeft: Float,
    val trackWidth: Float,
    val fillLeft: Float,
    val fillWidth: Float,
)

/** Matches fillMaxWidth's pixel rounding and start alignment without changing layout bounds. */
internal fun downloadTrackGeometry(width: Float, progress: Float, collapse: Float, rtl: Boolean): DownloadTrackGeometry {
    val available = width.coerceAtLeast(0f)
    val trackWidth = (available * (1f - collapse).coerceIn(0f, 1f)).roundToInt().toFloat()
    val fillWidth = (trackWidth * progress.coerceIn(0f, 1f)).roundToInt().toFloat()
    val trackLeft = if (rtl) available - trackWidth else 0f
    return DownloadTrackGeometry(trackLeft, trackWidth, if (rtl) trackLeft + trackWidth - fillWidth else trackLeft, fillWidth)
}
''')
p=base+'feature/profile/DownloadsScreen.kt'
s=read(p)
s=s.replace('import androidx.compose.ui.draw.drawWithContent\n','import androidx.compose.ui.draw.drawWithCache\n')
s=s.replace('import androidx.compose.ui.graphics.Color\n','''import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.LayoutDirection
''')
s=s.replace('    val progress by animateFloatAsState(', '    val progress = animateFloatAsState(').replace('    val collapse by animateFloatAsState(', '    val collapse = animateFloatAsState(')
start=s.index('    Box(Modifier.fillMaxWidth().height(4.dp))')
end=s.index('\n}\n\nprivate fun downloadStatusText',start)
s=s[:start]+'''    Box(
        Modifier.fillMaxWidth().height(4.dp).drawWithCache {
            var cachedWidth = Float.NaN
            var cachedOutline: Outline? = null
            val path = Path()
            onDrawBehind {
                val amount = collapse.value.coerceIn(0f, 1f)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val geometry = downloadTrackGeometry(size.width, progress.value, amount, rtl)
                if (geometry.trackWidth > 0f) {
                    if (cachedWidth != geometry.trackWidth) {
                        cachedWidth = geometry.trackWidth
                        val outline = AppShapes.track.createOutline(Size(cachedWidth, size.height), layoutDirection, this)
                        cachedOutline = outline
                        path.reset()
                        when (outline) {
                            is Outline.Generic -> path.addPath(outline.path)
                            is Outline.Rectangle -> path.addRect(outline.rect)
                            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                        }
                    }
                    translate(left = geometry.trackLeft) {
                        drawOutline(checkNotNull(cachedOutline), trackColor)
                        clipPath(path) {
                            val fillLeft = geometry.fillLeft - geometry.trackLeft
                            drawRect(accent, Offset(fillLeft, 0f), Size(geometry.fillWidth, size.height))
                            if (flowing && geometry.fillWidth > 0f) {
                                val band = geometry.fillWidth * 0.35f
                                val head = fillLeft - band + (geometry.fillWidth + 2f * band) * flow.value
                                clipRect(left = fillLeft, right = fillLeft + geometry.fillWidth) {
                                    drawRect(
                                        Brush.horizontalGradient(
                                            0f to Color.Transparent,
                                            0.5f to Color.White.copy(alpha = 0.38f),
                                            1f to Color.Transparent,
                                            startX = head - band,
                                            endX = head,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                // The original completion dot occupies the 4dp start box and scales about its centre.
                val radius = 2.dp.toPx()
                val centerX = if (rtl) size.width - radius else radius
                if (amount > 0f) drawCircle(Brand.Online, radius * amount, Offset(centerX, radius), alpha = amount)
            }
        },
    )'''+s[end:]
write(p,s)
