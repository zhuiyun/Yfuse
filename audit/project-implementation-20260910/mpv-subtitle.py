from pathlib import Path
root=Path('composeApp/src/androidMain/kotlin/com/yfuse/feature/player')
p=root/'MpvVideoEngine.kt'
s=p.read_text(encoding='utf-8')
pos=s.index('    private val observer =')
s=s[:pos]+'''    private val mutableSubtitleText = MutableStateFlow(MpvSubtitleText())
    internal val subtitleText = mutableSubtitleText.asStateFlow()

    private fun updateSubtitleLayout(instance: MPVLib, tracks: List<EngineTrack>, primary: String?, secondary: String?) {
        val stack = mpvCanStackSubtitles(tracks, primary, secondary)
        if (mutableSubtitleText.value.stacked != stack) {
            instance.setPropertyString("sub-visibility", if (stack) "no" else "yes")
            instance.setPropertyString("secondary-sub-visibility", if (stack) "no" else "yes")
            mutableSubtitleText.update { it.copy(stacked = stack) }
        }
    }

'''+s[pos:]
s=s.replace('            override fun eventProperty(property: String) {', '''            override fun eventProperty(property: String) {
                when (property) {
                    "sub-text" -> mutableSubtitleText.update { it.copy(primary = "") }
                    "secondary-sub-text" -> mutableSubtitleText.update { it.copy(secondary = "") }
                }''')
s=s.replace('                    "aid", "sid" -> readTracks()', '''                    "aid", "sid", "secondary-sid" -> readTracks()
                    "sub-text" -> mutableSubtitleText.update { it.copy(primary = value) }
                    "secondary-sub-text" -> mutableSubtitleText.update { it.copy(secondary = value) }''')
s=s.replace('                    MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {', '''                    MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                        mutableSubtitleText.update { it.copy(primary = "", secondary = "") }''')
s=s.replace('            instance.observeProperty("secondary-sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)', '''            instance.observeProperty("secondary-sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
            instance.observeProperty("secondary-sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)''')
s=s.replace('            val selectedSubtitle = instance.getPropertyString("sid")', '            val selectedSubtitle = instance.getPropertyString("sid")\n            val secondarySubtitle = instance.getPropertyString("secondary-sid")?.takeUnless { it == "no" }')
s=s.replace('            _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles) }', '''            updateSubtitleLayout(instance, subtitles, selectedSubtitle, secondarySubtitle)
            _state.update { it.copy(audioTracks = audio, subtitleTracks = subtitles, secondarySubtitleTrackId = secondarySubtitle) }''')
# MPVLib is Kotlin interface? inspect compiler and correct actual instance type if different
p.write_text(s,encoding='utf-8')
p=root/'MpvSurface.kt'
s=p.read_text(encoding='utf-8').replace('    ambientSampler: AmbientFrameSampler? = null,', '''    ambientSampler: AmbientFrameSampler? = null,
    subtitleControls: SubtitleControlState = SubtitleControlState(),''')
s=s.replace('    val playbackState by engine.state.collectAsState()', '    val playbackState by engine.state.collectAsState()\n    val subtitles by engine.subtitleText.collectAsState()')
s=s.replace('        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)', '''        if (subtitles.stacked) {
            val appearance = subtitleControls.appearance.withBrightness(subtitleControls.brightness)
            BottomSubtitleStack(subtitleControls.position,
                primary = { BottomSubtitleText(subtitles.primary, subtitleControls.scale, appearance) },
                secondary = { BottomSubtitleText(subtitles.secondary, subtitleControls.secondaryScale, appearance) },
            )
        }
        DiscNavigationOverlay(engine = engine, layoutSize = layoutSize)''')
p.write_text(s,encoding='utf-8')
p=root/'PlayerRoot.kt'
s=p.read_text(encoding='utf-8').replace('MpvSurface(engine, Modifier.fillMaxSize(), ambientSampler = ambientSampler)', 'MpvSurface(engine, Modifier.fillMaxSize(), ambientSampler = ambientSampler, subtitleControls = presentationSubtitleControls)')
s=s.replace('independentScaleAvailable = engine is YPlayerVideoEngineAdapter || engine is ExoVideoEngine || engine is MpvVideoEngine,', '''independentScaleAvailable = engine is YPlayerVideoEngineAdapter || engine is ExoVideoEngine ||
                            (engine is MpvVideoEngine && mpvCanStackSubtitles(state.subtitleTracks, state.subtitleTracks.firstOrNull { it.selected }?.id, secondarySubtitleTrackId)),
                        dualLayoutNote = when (engine) {
                            is MpvVideoEngine -> "文本双字幕在底部排列；图片字幕保留原排版，可切换 YCore 或 Exo 调整。"
                            is MdkVideoEngine -> "此内核保留字幕原排版；底部双字幕与独立字号请切换 YCore 或 Exo。"
                            else -> null
                        },''')
p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerPanelState.kt')
s=p.read_text(encoding='utf-8').replace('    val independentScaleAvailable: Boolean = false,', '    val independentScaleAvailable: Boolean = false,\n    val dualLayoutNote: String? = null,')
p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt')
s=p.read_text(encoding='utf-8').replace('                        GroupLabel("副字幕")', '                        GroupLabel("副字幕")\n                        subtitleControls.dualLayoutNote?.let { UnsupportedSubtitleControl(it) }')
p.write_text(s,encoding='utf-8')
