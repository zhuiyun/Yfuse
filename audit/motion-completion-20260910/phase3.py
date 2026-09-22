from edit import read,write
import re
base='composeApp/src/commonMain/kotlin/com/yfuse/';ds=base+'core/designsystem/'
def imports(s,*names):
    added=''.join('import '+n+'\n' for n in names if 'import '+n+'\n' not in s)
    return s.replace('\n\n','\n\n'+added,1)

p=base+'feature/calendar/CalendarScreen.kt';s=read(p)
s=imports(s,*['com.yfuse.core.designsystem.'+x for x in ['contentHandoff','contentPhase','rememberSegmentIndicator','selectionColor','rememberDisclosureProgress','DisclosureContent','animateRotationAsState','motionAwareItem','SkeletonRail']], 'androidx.compose.ui.graphics.graphicsLayer')
s=s.replace('Column(Modifier.fillMaxSize().statusBarsPadding())', 'Column(Modifier.fillMaxSize().statusBarsPadding().contentHandoff(state.section to contentPhase(state.loading, days.isNotEmpty(), state.error != null)))',1)
s=s.replace('                if (filtersExpanded) {\n                    LazyRow(', '                val filterProgress = rememberDisclosureProgress(filtersExpanded)\n                DisclosureContent(filtersExpanded, filterProgress) {\n                    LazyRow(',1)
s=s.replace('color = if (active) accent.onAccent else palette.body,','color = selectionColor(if (active) accent.onAccent else palette.body),')
s=s.replace('.background(if (active) accent.accent else Color.Transparent)', '.background(selectionColor(if (active) accent.accent else Color.Transparent))')
s=s.replace('                            OrbProgress(size = OrbProgressDefaults.Page, color = accent.accent)', '                            SkeletonRail(Modifier.fillMaxWidth())',1)
start=s.index('private fun CalendarSectionBar(');end=s.index('\n@Composable',start)
part=s[start:end].replace('    Row(', '    val indicator = rememberSegmentIndicator(CalendarSection.entries.indexOf(selected), accent.accent, underline = true)\n    Row(',1)
part=part.replace('.selectableGroup(),','.selectableGroup().then(indicator.container),',1)
part=part.replace('.height(44.dp)', '.height(44.dp).then(indicator.item(CalendarSection.entries.indexOf(section)))')
part=part.replace('color = if (active) accent.accent else palette.sub,','color = selectionColor(if (active) accent.accent else palette.sub),')
a=part.index('                if (active) {');b=part.index('\n            }',a)
part=part[:a]+part[b:]
s=s[:start]+part+s[end:]
start=s.index('private fun CalendarDayHeader(');end=s.index('\n@Composable',start)
part=s[start:end].replace('    val isToday =', '    val rotation = animateRotationAsState(if (expanded) 180f else 0f)\n    val isToday =',1)
part=part.replace('modifier = Modifier.size(16.dp),','modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation.value },')
part=part.replace('.background(if (expanded) accent.container.copy(alpha = 0.24f) else Color.Transparent)', '.background(selectionColor(if (expanded) accent.container.copy(alpha = 0.24f) else Color.Transparent))')
s=s[:start]+part+s[end:]
s=s.replace('                    ) { display ->\n                        Column {','                    ) { display ->\n                        Column(motionAwareItem()) {')
# This private predecessor has no call sites; the flattened, keyed lazy timeline is the live path.
start=s.index('@Composable\nprivate fun AccordionCalendarDay(');end=s.index('\n@Composable',start+12)
s=s[:start]+s[end:]
write(p,s)

for filename,old,new in [
 ('feature/home/HomeScreen.kt','.testTag("home-feed")','.testTag("home-feed").contentHandoff(contentPhase(state.loading, !state.content.isEmpty, state.error != null))'),
 ('feature/library/LibraryHomeScreen.kt','modifier = Modifier.fillMaxSize().skeletonSweep(),','modifier = Modifier.fillMaxSize().skeletonSweep().contentHandoff(contentPhase(state.loading, !state.content.isEmpty, state.error != null)),'),
 ('feature/library/LibraryGridScreen.kt','            Box(Modifier.fillMaxSize()) {','            Box(Modifier.fillMaxSize().contentHandoff(contentPhase(state.loading, state.loadedCount > 0, state.error != null))) {'),
 ('feature/servers/ServersTabScreen.kt','modifier = Modifier.fillMaxSize().statusBarsPadding(),','modifier = Modifier.fillMaxSize().statusBarsPadding().contentHandoff(layout to visibleServers.isEmpty()),'),
 ]:
 p=base+filename;s=read(p);assert old in s,filename
 s=s.replace(old,new,1);s=imports(s,'com.yfuse.core.designsystem.contentHandoff','com.yfuse.core.designsystem.contentPhase');write(p,s)

# Detail already has content-specific arrival; unify its skeleton and error host without layering two page trees.
p=base+'feature/detail/DetailScreen.kt';s=read(p)
s=imports(s,'com.yfuse.core.designsystem.contentHandoff','com.yfuse.core.designsystem.contentPhase')
old='                when {\n                    detail == null && state.error == null -> DetailSkeleton(heroHeight)'
new='''                Box(Modifier.fillMaxSize().contentHandoff(contentPhase(detail == null && state.error == null, detail != null, state.error != null))) {
                when {
                    detail == null && state.error == null -> DetailSkeleton(heroHeight)'''
assert old in s;s=s.replace(old,new,1)
# Close the wrapper before the next sibling after the main when branch.
start=s.index(new);marker=s.index('                DetailTopBar',start) if '                DetailTopBar' in s[start:] else -1
# Rather than guessing sibling placement, find the original when's closing brace with a Kotlin-aware scan below.
def block_end(text,start):
    depth=0;quote=None;line=False;comment=0;i=start
    while i<len(text):
        c=text[i];n=text[i:i+2]
        if line:
            if c=='\n':line=False
        elif comment:
            if n=='*/':comment-=1;i+=1
            elif n=='/*':comment+=1;i+=1
        elif quote:
            if c=='\\':i+=1
            elif c==quote:quote=None
        elif n=='//':line=True;i+=1
        elif n=='/*':comment=1;i+=1
        elif c in ['"',"'"]:quote=c
        elif c=='{':depth+=1
        elif c=='}':
            depth-=1
            if depth==0:return i+1
        i+=1
    raise AssertionError('unclosed block')
wh=s.index('                when {',start);end=block_end(s,s.index('{',wh));s=s[:end]+'\n                }'+s[end:];write(p,s)

p=base+'feature/search/SearchScreen.kt';s=read(p)
s=imports(s,'com.yfuse.core.designsystem.selectionColor')
s=s.replace('        if (loading) OrbProgress(size = SearchFieldOrbSize, contentDescription = "正在搜索")','''        Box(Modifier.size(SearchFieldOrbSize), contentAlignment = Alignment.Center) {
            if (loading) OrbProgress(size = SearchFieldOrbSize, contentDescription = "正在搜索")
        }''')
start=s.index('private fun TypeChip(');end=s.index('\n/**',start)
part=s[start:end]
for expr in ['if (selected) accent.accent else palette.body','if (selected) accent.container else palette.card2','if (selected) accent.border else palette.border']:
 part=part.replace(expr,'selectionColor('+expr+')')
s=s[:start]+part+s[end:];write(p,s)

# System scale changes must suspend decorative custom loops and allow them to resume.
for file in ['PageStates.kt','WaitingPulse.kt']:
 p=ds+file;s=read(p);s=imports(s,'androidx.compose.ui.MotionDurationScale','androidx.compose.runtime.snapshotFlow','kotlinx.coroutines.flow.first')
 if file=='PageStates.kt':
  s=s.replace('        while (true) {\n            withInfiniteAnimationFrameMillis', '''        val durationScale = coroutineContext[MotionDurationScale]
        while (true) {
            if (durationScale?.scaleFactor == 0f) {
                clock.alpha.floatValue = 1f
                clock.millis.longValue = -1L
                snapshotFlow { durationScale.scaleFactor }.first { it > 0f }
            }
            withInfiniteAnimationFrameMillis''',1)
 else:
  s=s.replace('        while (true) {','        val durationScale = coroutineContext[MotionDurationScale]\n        while (true) {',1)
  s=s.replace('if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f)', 'if (durationScale?.scaleFactor == 0f)')
  s=s.replace('                return@LaunchedEffect\n            }','                shown.value = false\n                snapshotFlow { durationScale.scaleFactor }.first { it > 0f }\n                shown.value = true\n            }',1)
 write(p,s)
