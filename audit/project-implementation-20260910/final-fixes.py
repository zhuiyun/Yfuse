from pathlib import Path
p=Path('harmonyApp/entry/src/main/cangjie/src/storage/server_registry.cj');s=p.read_text(encoding='utf-8').replace('let secretRef = if (existingIndex >= 0) { mutableServers[existingIndex].secretRef } else { references.create() }','''// Stage a fresh secret so a metadata failure cannot overwrite a working session.
        let secretRef = references.create()
        let previousDefault = mutableDefaultServerId''');s=s.replace('case Success(_) => ApiResult<SavedServer>.Success(saved)','''case Success(_) =>
                        if (existingIndex >= 0) { secrets.remove(previous[existingIndex].secretRef) }
                        ApiResult<SavedServer>.Success(saved)''');s=s.replace('if (existingIndex < 0) { secrets.remove(secretRef) }','mutableDefaultServerId = previousDefault\n                        secrets.remove(secretRef)');p.write_text(s,encoding='utf-8')
# Explicit paths touched by the current implementation, preserving unrelated dirty code.
files=[]
for prefix,names in {
'composeApp/src/androidMain/kotlin/com/yfuse/feature/player': ['BottomSubtitleStack','ExoDualSubtitleCueMerger','ExoSecondarySubtitleController','ExoVideoEngine','ExoSurface','MpvSubtitleText','MpvVideoEngine','MpvSurface','PlayerRoot','PlayerAmbientBinding','PlaybackBookmarkBinding','Core2Surface'],
'composeApp/src/commonMain/kotlin/com/yfuse/feature/player': ['DualSubtitlePreferences','PlaybackBookmarkPanel','PlayerControls','PlayerPanelState','PlayerSettingsPanel'],
'composeApp/src/commonMain/kotlin/com/yfuse/core/data': ['PlaybackBookmarks','PlaybackPreferences'],
'composeApp/src/commonMain/kotlin/com/yfuse/core/offline': ['OfflineMedia','OfflineDownloadBudget'],
'composeApp/src/commonMain/kotlin/com/yfuse/feature/profile': ['DownloadsScreen'],
'composeApp/src/androidMain/kotlin/com/yfuse/core/offline': ['OfflineMedia.android'],
'composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player': ['ExoDualSubtitleCueMergerTest','MpvSubtitleTextTest'],
'composeApp/src/commonTest/kotlin/com/yfuse/feature/player': ['DualSubtitlePreferencesTest'],
'composeApp/src/commonTest/kotlin/com/yfuse/core/data': ['PlaybackBookmarksTest'],
'composeApp/src/commonTest/kotlin/com/yfuse/core/offline': ['OfflineDownloadBudgetTest'],
}.items(): files.extend(str(Path(prefix,name+'.kt')) for name in names)
files+= [str(p) for p in Path('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui').glob('*.kt')]
files+= [str(p) for p in Path('tvApp/src/androidUnitTest').rglob('*.kt')]
files+=['gradle/diagnostic-build.gradle.kts','composeApp/build.gradle.kts','tvApp/build.gradle.kts']
Path('audit/project-implementation-20260910/format-files.txt').write_text('\n'.join(files),encoding='utf-8')
