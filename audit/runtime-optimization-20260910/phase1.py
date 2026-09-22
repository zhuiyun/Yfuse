from edit import *

p='composeApp/src/commonMain/kotlin/com/yfuse/core2/legacy/YPlayerVideoEngineAdapter.kt'
replace(p, 'ReverseMappedStateFlow(state, YPlayerState::toLegacyPlaybackState)', 'ReverseMappedStateFlow(state, LegacyPlaybackStateMapper()::map)')
replace(p, '    override val value: Target get() = transform(source.value)', '''    private data class Mapping<S, T>(val source: S, val target: T)
    private val mappingLock = Any()
    private var mapping: Mapping<Source, Target>? = null

    private fun mapped(value: Source): Target = synchronized(mappingLock) {
        mapping?.takeIf { it.source == value }?.let { return@synchronized it.target }
        transform(value).also { mapping = Mapping(value, it) }
    }

    override val value: Target get() = mapped(source.value)''')
replace(p, 'collector.emit(transform(value))', 'collector.emit(mapped(value))')
replace(p, 'private fun YPlayerState.toLegacyPlaybackState(): PlaybackState =', '''private class LegacyPlaybackStateMapper {
    private var audioSource: List<YTrack>? = null
    private var subtitleSource: List<YTrack>? = null
    private var audio: List<EngineTrack> = emptyList()
    private var subtitles: List<EngineTrack> = emptyList()

    fun map(state: YPlayerState): PlaybackState {
        if (audioSource != state.audioTracks) {
            audioSource = state.audioTracks
            audio = state.audioTracks.map(YTrack::toEngineTrack)
        }
        if (subtitleSource != state.subtitleTracks) {
            subtitleSource = state.subtitleTracks
            subtitles = state.subtitleTracks.map(YTrack::toEngineTrack)
        }
        return state.toLegacyPlaybackState(audio, subtitles)
    }
}

private fun YPlayerState.toLegacyPlaybackState(
    audio: List<EngineTrack>,
    subtitles: List<EngineTrack>,
): PlaybackState =''')
replace(p, 'audioTracks = audioTracks.map(YTrack::toEngineTrack),', 'audioTracks = audio,')
replace(p, 'subtitleTracks = subtitleTracks.map(YTrack::toEngineTrack),', 'subtitleTracks = subtitles,')

p='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackDiagnosticReportRegistry.kt'
replace(p, '    fun update(\n', '''    private data class ReportInput(
        val diagnostics: PlaybackDiagnostics,
        val subtitleSelection: String,
        val selectedEngine: PlayerEngine,
        val fallbackChain: List<PlayerEngine>,
        val nativeOnly: Boolean,
    )
    private var lastReportInput: ReportInput? = null

    @Synchronized
    fun update(
''')
replace(p, '''        val evidence = diagnostics.outputEvidence
        val mpv = diagnostics.mpvDolbyRuntimeEvidence()
        recordTimeline(state, selectedEngine, nativeOnly)''', '''        recordTimeline(state, selectedEngine, nativeOnly)
        val subtitleSelection = playbackSubtitleDiagnosticSelection(state)
        val input = ReportInput(diagnostics, subtitleSelection, selectedEngine, fallbackChain.toList(), nativeOnly)
        if (input == lastReportInput) return
        val evidence = diagnostics.outputEvidence
        val mpv = diagnostics.mpvDolbyRuntimeEvidence()''')
replace(p, 'appendLine("subtitle.selection=${playbackSubtitleDiagnosticSelection(state)}")', 'appendLine("subtitle.selection=$subtitleSelection")')
replace(p, '''        val previous = latest.get()
        if (previous.currentProcess''', '''        lastReportInput = input
        val previous = latest.get()
        if (previous.currentProcess''')

p='composeApp/src/commonMain/kotlin/com/yfuse/feature/player/DanmakuOverlay.kt'
replace(p, 'import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.derivedStateOf\nimport androidx.compose.ui.graphics.graphicsLayer')
replace(p, '            val timeBucket = renderedPositionMs.floorDiv(WINDOW_BUCKET_MS)', '''            val timeBucket by remember {
                derivedStateOf { renderedPositionMs.floorDiv(WINDOW_BUCKET_MS) }
            }''')
t=read(p); start=t.index('                val elapsed = renderedPositionMs - comment.timeMs'); end=t.index('                    val y = laneHeight', start)
t=t[:start]+'''                key(placement.input.index, comment.timeMs, comment.displayText) {
                    val measuredWidth = placement.input.width.dp
'''+t[end:]
t=t.replace('modifier = Modifier.offset { IntOffset(x.roundToPx(), y.roundToPx()) },', '''modifier = Modifier
                            .offset {
                                val elapsed = renderedPositionMs - comment.timeMs
                                val x = when (comment.kind) {
                                    DanmakuKind.Scroll -> {
                                        val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                                        maxWidth - (maxWidth + measuredWidth) * progress
                                    }
                                    else -> (maxWidth - measuredWidth).coerceAtLeast(0.dp) / 2f
                                }
                                IntOffset(x.roundToPx(), y.roundToPx())
                            }
                            .graphicsLayer {
                                alpha = if (renderedPositionMs - comment.timeMs in 0..duration) 1f else 0f
                            },''')
write(p,t)

p='composeApp/src/commonMain/kotlin/com/yfuse/core/data/LibraryCache.kt'
replace(p, 'import kotlinx.serialization.json.Json', 'import kotlinx.serialization.json.Json\nimport kotlinx.serialization.json.decodeFromJsonElement')
replace(p, 'json.decodeFromString(PersistedLibraryCache.serializer(), raw)', 'json.decodeFromJsonElement(PersistedLibraryCache.serializer(), root)')
replace(p, 'json.decodeFromString(HomeContent.serializer(), raw)', 'json.decodeFromJsonElement(HomeContent.serializer(), root)')

p='composeApp/src/commonMain/kotlin/com/yfuse/di/AppModule.kt'
replace(p, 'import kotlinx.coroutines.Dispatchers', 'import kotlinx.coroutines.Dispatchers\nimport org.koin.core.qualifier.named')
replace(p, '    single { settings }', '    single { settings }\n    single(named("account-http")) { createAccountClient() }')
replace(p, 'client = createAccountClient(),', 'client = get(named("account-http")),')
replace(p, 'OfficialAiringScheduleCatalog(createAccountClient(), get())', 'OfficialAiringScheduleCatalog(get(named("account-http")), get())')
replace(p, 'AccountApi(createAccountClient())', 'AccountApi(get(named("account-http")))')
replace(p, 'PlaybackCloudApi(createAccountClient())', 'PlaybackCloudApi(get(named("account-http")))')

p='.gitignore'
write(p, read(p)+'''\n# Local build caches and linked checkouts (reports under audit remain reviewable)
/.gradle-tmp/
/.worktrees/
/.codex-calendar-optimization/
/audit/**/before/
''')
