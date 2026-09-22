from pathlib import Path
import subprocess
p=Path('harmonyApp/entry/src/main/cangjie/src/entry_view.cj')
s=subprocess.check_output(['git','-c','safe.directory=D:/Demo/Yfuse','show','HEAD:harmonyApp/entry/src/main/cangjie/src/entry_view.cj']).decode('utf-8')
Path('audit/project-implementation-20260910/entry-view-before.cj.txt').write_text(s,encoding='utf-8')
start=s.index('        if (selectedTab == 0) {',s.index('    func RootContent()'));end=s.index('            ProfileScreen(',start)
s=s[:start]+'''        if (selectedTab == 0) {
            ConnectedMediaScreen(mode: 0, session: homeSession,
                onOpenTmdb: { => homeRoute = 3 }, onOpenCalendar: { => homeRoute = 4 },
                onPlayingChanged: { value => connectedPlaying = value })
        } else if (selectedTab == 1) {
            ConnectedMediaScreen(mode: 1, session: librarySession,
                onPlayingChanged: { value => connectedPlaying = value })
        } else if (selectedTab == 2) {
            ConnectedMediaScreen(mode: 2, session: serversSession,
                onPlayingChanged: { value => connectedPlaying = value })
        } else if (selectedTab == 3) {
            ConnectedMediaScreen(mode: 3, session: searchSession,
                onPlayingChanged: { value => connectedPlaying = value })
        } else {
'''+s[end:]
s=s.replace('    @State var selectedTab: Int64 = 0','''    @State var connectedPlaying: Bool = false
    let homeSession = createConnectedMediaSession()
    let librarySession = createConnectedMediaSession()
    let serversSession = createConnectedMediaSession()
    let searchSession = createConnectedMediaSession()
    @State var selectedTab: Int64 = 0''')
s=s.replace('            if (atRoot()) {\n                Row {','            if (atRoot() && !connectedPlaying) {\n                Row {')
# Previously unreachable legacy playback routes are preserved along with per-tab navigation.
p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/ui/connected_media_screen.cj');s=p.read_text(encoding='utf-8');s=s.replace('    var mode: Int64 = 0','''    var mode: Int64 = 0
    var onOpenTmdb: () -> Unit = { => }
    var onOpenCalendar: () -> Unit = { => }''')
s=s.replace('''    let session = ConnectedMediaSession(NativeStringPreferenceStore(), AssetStoreSecretStore(),
        NativeSecretReferenceFactory(), HarmonyHttpTransport())''','''    var session: ConnectedMediaSession = createConnectedMediaSession()''')
s=s.replace('''        session.restore()
        if (mode != 2) { session.load("") }''','''        if (!session.ready) {
            session.restore()
            if (mode != 2) { session.load("") }
        }
        session.changed()''')
s=s.replace('''                            if (!session.error.isEmpty())''','''                            if (mode == 0 && session.route == 0) {
                                Row {
                                    Button("影视资料").onClick({ => onOpenTmdb() })
                                    Button("追剧日历").onClick({ => onOpenCalendar() })
                                }
                            }
                            if (!session.error.isEmpty())''')
s+='''
func createConnectedMediaSession(): ConnectedMediaSession {
    return ConnectedMediaSession(NativeStringPreferenceStore(), AssetStoreSecretStore(),
        NativeSecretReferenceFactory(), HarmonyHttpTransport())
}
'''
p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/provider/connected_session.cj');s=p.read_text(encoding='utf-8').replace('public func leave(): Unit { generation++; source = ""; changed = { => } }','public func leave(): Unit { generation++; if (route == 2) { route = 1 }; loading = false; source = ""; changed = { => } }');p.write_text(s,encoding='utf-8')
p=Path('scripts/validate-cangjie-sources.py');s=p.read_text(encoding='utf-8').replace('"storage/secure_store.cj"}', '"storage/secure_store.cj", "storage/native_preferences.cj"}');p.write_text(s,encoding='utf-8')
