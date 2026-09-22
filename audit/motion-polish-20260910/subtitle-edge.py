from edit import read,write
p='composeApp/src/androidMain/kotlin/com/yfuse/feature/player/BottomSubtitleStack.kt'
s=read(p)
s=s.replace('''    if (cues.isEmpty()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        cues.forEach { cue ->''','''    // Keep the empty target in the handoff so the previous line can finish its QUICK exit.
    SubtitleHandoff(cues) { visibleCues ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        visibleCues.forEach { cue ->''')
s=s.rstrip()+'\n}\n'
write(p,s)
