from edit import read, write, replace

base = 'composeApp/src/commonMain/kotlin/com/yfuse/'
p = base + 'core/designsystem/OrbProgress.kt'
s = read(p).replace('import androidx.compose.runtime.getValue', 'import androidx.compose.runtime.rememberUpdatedState')
s = s.replace('val transition = rememberInfiniteTransition(label = "orb")', 'val transition = if (reduceMotion) null else rememberInfiniteTransition(label = "orb")')
s = s.replace('val cometTurn by transition.animateFloat(', 'val cometTurn = transition?.animateFloat(')
s = s.replace('val breathPhase by transition.animateFloat(', 'val breathPhase = transition?.animateFloat(')
s = s.replace('label = "orbComet",\n    )', 'label = "orbComet",\n    ) ?: rememberUpdatedState(0f)')
s = s.replace('label = "orbBreath",\n    )', 'label = "orbBreath",\n    ) ?: rememberUpdatedState(0f)')
s = s.replace('    val turn = if (reduceMotion) 0f else cometTurn\n    val coreScale = if (reduceMotion) 1f else orbCoreScale(breathPhase)\n', '')
s = s.replace('        val radius = this.size.minDimension / 2f', '        val turn = cometTurn.value\n        val coreScale = orbCoreScale(breathPhase.value)\n        val radius = this.size.minDimension / 2f')
write(p, s)
p = base + 'feature/library/LibraryHomeScreen.kt'
s = read(p)
if 'import androidx.compose.runtime.derivedStateOf' not in s:
    s = s.replace('import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.derivedStateOf\nimport androidx.compose.runtime.Composable')
s = s.replace('val carouselVisible = listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress', '''val carouselVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress }
    }''')
write(p, s)
p = 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt'
replace(p, 'episodes = activeItems.toEpisodeCards(),', 'episodes = remember(activeItems) { activeItems.toEpisodeCards() },')
p = base + 'feature/player/PlayerControls.kt'
replace(p, 'progressMarkers = playbackProgressMarkers(skip, state.durationMs),', '''progressMarkers = remember(skip, state.durationMs) {
                    playbackProgressMarkers(skip, state.durationMs)
                },''')
p = base + 'feature/player/PlayerChromeRefined.kt'
s = read(p)
old = '''        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(AppShapes.track)
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction.coerceIn(shownFraction, 1f))
                    .drawBehind { drawRect(lerp(accent(), Color.Gray, 0.62f).copy(alpha = 0.50f)) },
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shownFraction)
                    .drawBehind {
                        val color = accent()
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(lerp(color, Color.Black, 0.14f), lerp(color, Color.White, 0.24f)),
                            ),
                        )
                    },
            )
        }'''
new = '''        // Both rails keep fixed geometry; progress invalidates paint, not child measurement.
        val played = rememberUpdatedState(shownFraction)
        val buffered = rememberUpdatedState(bufferedFraction)
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(AppShapes.track)
                .drawBehind {
                    val color = accent()
                    val playedWidth = size.width * played.value
                    drawRect(Color.White.copy(alpha = 0.16f))
                    drawRect(
                        lerp(color, Color.Gray, 0.62f).copy(alpha = 0.50f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width * buffered.value.coerceIn(played.value, 1f), size.height,
                        ),
                    )
                    if (playedWidth > 0f) {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(lerp(color, Color.Black, 0.14f), lerp(color, Color.White, 0.24f)),
                                endX = playedWidth,
                            ),
                            size = androidx.compose.ui.geometry.Size(playedWidth, size.height),
                        )
                    }
                },
        )'''
assert old in s
write(p, s.replace(old, new))

# Keep ASS's frame cadence inside its render side effect. Only changed cue sets/bitmaps notify Compose.
p = 'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/Core2Surface.kt'
s = read(p)
start = s.index('    fun hasActiveAss(')
end = s.index('    // Android Lint', start)
s = s[:start] + s[end:]
start = s.index('    var frameClock by remember')
end = s.index('    BoxWithConstraints(modifier)', start)
s = s[:start] + s[end:]
s = s.replace('positionMs = subtitlePositionMs,', 'clock = clock,')
start = s.index('private fun Core2SubtitleChannel(')
before, tail = s[:start], s[start:]
tail = tail.replace('    positionMs: Long,', '    clock: YSubtitleClockAnchor,', 1)
tail = tail.replace('''    val activeCues =
        remember<List<YSubtitleCue>>(timeline, positionMs, offsetMs) {
            timeline.activeAt(positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND)
        }''', '''    var activeCues by remember(timeline, clock, offsetMs) {
        mutableStateOf(timeline.activeAt(clock.positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND))
    }''')
old = '''    LaunchedEffect(assRenderer, cues, positionMs, timelineGeneration, offsetMs, canvasSize, assOverrides) {
        assRenderer.submit(
            cues,
            (positionMs - offsetMs) * MICROS_PER_MILLISECOND,
            canvasSize.width,
            canvasSize.height,
            assOverrides,
            timelineGeneration = timelineGeneration,
        )
    }'''
new = '''    LaunchedEffect(assRenderer, cues, clock, timelineGeneration, offsetMs, canvasSize, assOverrides) {
        fun submit(positionMs: Long) {
            activeCues = timeline.activeAt(positionMs * MICROS_PER_MILLISECOND, offsetMs * MICROS_PER_MILLISECOND)
            assRenderer.submit(
                cues,
                (positionMs - offsetMs) * MICROS_PER_MILLISECOND,
                canvasSize.width,
                canvasSize.height,
                assOverrides,
                timelineGeneration = timelineGeneration,
            )
        }
        submit(clock.positionMs)
        // Start with the same active-ASS gate as before; the next engine tick handles a new cue.
        if (clock.advancing && activeCues.any { it.payload is YSubtitlePayload.AssEvent }) {
            while (true) withFrameNanos { submit(clock.positionAt(it)) }
        }
    }'''
assert old in tail
tail = tail.replace(old, new)
write(p, before + tail)
