from edit import read, write, replace

p = 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt'
s = read(p).replace('import androidx.compose.runtime.setValue', 'import androidx.compose.runtime.snapshotFlow\nimport androidx.compose.runtime.setValue')
s = s.replace('                    reportHealth = false,', '                    power = current.power.copy(measuredMilliwatts = null),\n                    reportHealth = false,')
start = s.index('        LaunchedEffect(\n            activeDolbyVersion?.id,\n            kind,\n            runtimeAssessment.health.grade,')
end = s.index('        val danmaku =', start)
part = s[start:end]
body = part.index('        ) {') + len('        ) {')
part = part[:body] + '\n            val assessment = runtimeAssessmentState.value' + part[body:].replace('runtimeAssessment.', 'assessment.')
s = s[:start] + part + s[end:]
write(p, s)

p = 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/YCorePlayerRuntime.android.kt'
s = read(p).replace('                                assessment.value = observed,', '                                assessment = observed,')
s = s.replace('import androidx.compose.runtime.setValue', 'import androidx.compose.runtime.setValue\nimport androidx.compose.runtime.snapshots.Snapshot')
s = s.replace('            createYCorePlaybackSession(', '            val initial = Snapshot.withoutReadObservation { stateSource.value }\n            createYCorePlaybackSession(')
s = s.replace('stateSource.value.positionMs', 'initial.positionMs').replace('stateSource.value.diagnostics.bufferEvents', 'initial.diagnostics.bufferEvents').replace('stateSource.value.diagnostics.droppedFrames', 'initial.diagnostics.droppedFrames')
write(p, s)

replace('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSkipCoordinator.kt', 'PlaybackSegmentType.Intro -> activeSegment.endMs?', 'PlaybackSegmentType.Intro -> activeSegment?.endMs?')
replace('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerBatteryStatus.kt', '(level.toLong() * 100 / scale).toInt().coerceIn(0, 100)', '(level.toLong() * 100 / scale).coerceIn(0L, 100L).toInt()')
replace('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerBatteryStatus.kt', '" +"', '" ⚡"')

p = 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ThemeColorConsumer.kt'
s = read(p).replace('import androidx.compose.runtime.setValue', 'import androidx.compose.runtime.setValue\nimport androidx.compose.runtime.snapshots.Snapshot')
s = s.replace('    var color: Color,', '    var color: Color,\n    var shown: State<Color>? = null,')
s = s.replace('                Animatable(memory.color)', '                Animatable(Snapshot.withoutReadObservation { memory.shown?.value ?: memory.color })')
s = s.replace('    SideEffect {\n', '    val shown = if (!finished && animation != null) animation.asState() else rememberUpdatedState(target)\n    SideEffect {\n')
s = s.replace('        memory.color = target', '        memory.color = target\n        memory.shown = shown')
s = s.replace('    return if (!finished && animation != null) animation.asState() else rememberUpdatedState(target)', '    return shown')
write(p, s)

# The former global Palette animator has no production consumers after moving to paint consumers.
p = 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ThemeCrossfade.kt'
write(p, '''package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable

/** Stable destination colours, published once per theme change. Consumers own their paint clocks. */
@Immutable
data class ThemeColors(
    val palette: Palette,
    val accent: AccentColors,
)

const val THEME_CROSSFADE_MS = Motion.THEME_CROSSFADE
''')

p = 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/SubtitleBitmapCanvas.android.kt'
s = read(p).replace('import androidx.compose.ui.unit.IntSize', 'import androidx.compose.ui.unit.IntSize\nimport androidx.compose.ui.unit.constrainWidth\nimport androidx.compose.ui.unit.constrainHeight')
s = s.replace('val width = constraints.maxWidth', 'val width = constraints.constrainWidth(viewport.width.roundToPx())')
s = s.replace('        val child = measurables.single().measure(Constraints.fixed(width, height))\n        layout(width, height)', '        val measuredHeight = constraints.constrainHeight(height)\n        val child = measurables.single().measure(Constraints.fixed(width, measuredHeight))\n        layout(width, measuredHeight)')
write(p, s)
