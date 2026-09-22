from edit import read, write
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackRuntimeContent.kt'
s=read(p).replace('    val live = remember { mutableStateOf(source.value) }\n','')
s=s.replace('    val memory = remember { arrayOf(PlaybackTimelineMemory()) }', '''    val memory = remember { arrayOf(PlaybackTimelineMemory()) }
    val live = remember(owner, source) {
        val reported = source.value
        val item = items.getOrNull(reported.currentIndex)
        // A new backend must never render the previous backend's ended/error flags, even for
        // its first composition. Only the timeline is retained across a same-item handover.
        mutableStateOf(
            stabilizePlaybackTimeline(
                memory[0],
                item?.let { PlaybackTimelineIdentity(reported.currentIndex, it.serverId, it.id) },
                reported,
            ).state,
        )
    }''')
write(p,s)
p='composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlaybackRuntimeContentTest.kt'
s=read(p).replace('import androidx.compose.runtime.SideEffect','import androidx.compose.runtime.SideEffect\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.setValue\nimport androidx.compose.runtime.mutableStateOf')
s=s.replace('val source = MutableStateFlow(PlaybackState(positionMs = 1_000L, durationMs = 60_000L))', 'var source by mutableStateOf(MutableStateFlow(PlaybackState(positionMs = 1_000L, durationMs = 60_000L)))\n            val routedStates = mutableListOf<PlaybackState>()')
s=s.replace('                            controls = structural','                            controls = structural\n                            routedStates += structural')
s=s.replace('                assertEquals(0L, controls.positionMs)', '''                assertEquals(0L, controls.positionMs)
                source.value = source.value.copy(ended = true, playing = false)
                settle()
                routedStates.clear()
                source = MutableStateFlow(PlaybackState(buffering = true, durationMs = 0L))
                settle()
                assertTrue(routedStates.isNotEmpty())
                assertTrue(routedStates.all { it.buffering && !it.ended }, "A new backend must not see old terminal flags")
                assertEquals(60_000L, timeline.durationMs, "Same-item handover preserves the established timeline")''')
write(p,s)
