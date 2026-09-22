from pathlib import Path
p=Path('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvDownloadsScreen.kt')
s=p.read_text(encoding='utf-8')
for name in ('budget','charging','window'):
 s=s.replace(f'stableId = "downloads:{name}", focusMemory',f'stableId = "downloads:{name}", icon = AppIcons.Download, focusMemory')
p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt')
s=p.read_text(encoding='utf-8').replace('import androidx.compose.ui.unit.dp','import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp')
p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt')
s=p.read_text(encoding='utf-8').replace('scale = remembered?.subtitleScale ?: 1f,','scale = remembered?.subtitleScale ?: 1f,\n                secondaryScale = remembered?.secondarySubtitleScale ?: 1f,\n                secondaryOffsetMs = remembered?.secondarySubtitleOffsetMs ?: 0L,')
s=s.replace('subtitleScale = presentationSubtitleControls.scale,','subtitleScale = presentationSubtitleControls.scale,\n                    secondarySubtitleScale = presentationSubtitleControls.secondaryScale,')
s=s.replace('secondaryTrackId = secondarySubtitleTrackId,','secondaryTrackId = secondarySubtitleTrackId,\n                        independentScaleAvailable = engine is YPlayerVideoEngineAdapter || engine is ExoVideoEngine || engine is MpvVideoEngine,')
a=s.index('    LaunchedEffect(currentItem?.id, state.diagnostics.audioOutputRoute')
s=s[:a]+'''    fun applySubtitlePair(primary: EngineTrack, secondary: EngineTrack) {
        if (!backendExtensions.supportsSecondarySubtitleTrack) return
        val oldPrimary = state.subtitleTracks.firstOrNull { it.selected }
        val oldSecondary = secondarySubtitleTrackId
        backendExtensions.selectSecondarySubtitleTrack(EngineTrack.OFF)
        player.selectTrack(YTrackType.Subtitle, primary.id)
        if (!backendExtensions.selectSecondarySubtitleTrack(secondary.id)) {
            player.selectTrack(YTrackType.Subtitle, oldPrimary?.id ?: EngineTrack.OFF)
            oldSecondary?.let(backendExtensions::selectSecondarySubtitleTrack)
            Toast.makeText(context, "当前内核无法应用此双字幕方案", Toast.LENGTH_SHORT).show()
            return
        }
        handoverItemId = currentItem?.id
        subtitleRestore = state.subtitleTracks.restorePreferenceFor(primary)
        secondarySubtitleRestore = state.subtitleTracks.restorePreferenceFor(secondary)
        secondarySubtitleTrackId = secondary.id
        restoreSubtitlesOff = false
        rememberSeriesPlayback { it.copy(primarySubtitlesOff = false,
            primarySubtitle = primary.toRememberedPlaybackTrack(), secondarySubtitle = secondary.toRememberedPlaybackTrack()) }
    }

'''+s[a:]
s=s.replace('                    SubtitleControlActions(\n','''                    SubtitleControlActions(
                        onSecondaryScale = { value ->
                            subtitleControls = subtitleControls.copy(secondaryScale = value.coerceIn(0.6f, 1.8f))
                            rememberSeriesPlayback { it.copy(secondarySubtitleScale = subtitleControls.secondaryScale) }
                        },
                        onSwap = {
                            val primary = state.subtitleTracks.firstOrNull { it.selected }
                            val secondary = state.subtitleTracks.firstOrNull { it.id == secondarySubtitleTrackId }
                            if (primary != null && secondary != null) applySubtitlePair(secondary, primary)
                        },
                        onLanguagePair = { pair ->
                            val selected = selectDualSubtitleLanguagePair(state.subtitleTracks, pair)
                            if (selected == null) Toast.makeText(context, "当前视频缺少该语言组合的字幕", Toast.LENGTH_SHORT).show()
                            else applySubtitlePair(selected.first, selected.second)
                        },
''')
s=s.replace('subtitleControls = subtitleControls.copy(secondaryOffsetMs = offset)','subtitleControls = subtitleControls.copy(secondaryOffsetMs = offset)\n                                rememberSeriesPlayback { it.copy(secondarySubtitleOffsetMs = offset) }')
p.write_text(s,encoding='utf-8')
