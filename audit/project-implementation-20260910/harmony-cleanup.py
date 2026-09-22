from pathlib import Path
p=Path('harmonyApp/entry/src/test/cangjie/app/app_test.cj');s=p.read_text(encoding='utf-8').replace('        case AppRoute.MediaDiscoverySettings => "discovery"\n','');p.write_text(s,encoding='utf-8')
p=Path('scripts/verify-cangjie-host.py');s=p.read_text(encoding='utf-8').replace('    "discovery",\n','');p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/entry_view.cj');p.write_text('''package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

@Entry
@Component
class EntryView {
    @State var selectedTab: Int64 = 0
    @State var profileRoute: Int64 = 0
    @State var playing: Bool = false

    func build() {
        Column {
            if (selectedTab == 0) {
                ConnectedMediaScreen(mode: 0, onPlayingChanged: { value => playing = value })
            } else if (selectedTab == 1) {
                ConnectedMediaScreen(mode: 1, onPlayingChanged: { value => playing = value })
            } else if (selectedTab == 2) {
                ConnectedMediaScreen(mode: 2, onPlayingChanged: { value => playing = value })
            } else if (selectedTab == 3) {
                ConnectedMediaScreen(mode: 3, onPlayingChanged: { value => playing = value })
            } else if (profileRoute == 0) {
                ProfileScreen(onOpenDownloads: { => profileRoute = 1 },
                    onOpenAccount: { => profileRoute = 2 }, onOpenSessions: { => profileRoute = 3 })
            } else {
                Column {
                    Button("‹ 返回").onClick({ => profileRoute = 0 })
                    if (profileRoute == 1) { DownloadsScreen() }
                    else if (profileRoute == 2) { AccountSettingsScreen(onOpenSessions: { => profileRoute = 3 }) }
                    else { AccountSessionsScreen() }
                }.layoutWeight(1)
            }
            if (!playing && profileRoute == 0) {
                Row {
                    NavButton("首页", 0); NavButton("库", 1); NavButton("服务器", 2)
                    NavButton("搜索", 3); NavButton("我的", 4)
                }.height(56)
            }
        }.width(100.percent).height(100.percent)
    }
    @Builder
    func NavButton(label: String, index: Int64) {
        Button(label).onClick({ => selectedTab = index; profileRoute = 0 }).layoutWeight(1).height(48)
    }
}
''',encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/ui/connected_media_screen.cj');s=p.read_text(encoding='utf-8').replace('    var mode: Int64 = 0','    var mode: Int64 = 0\n    var onPlayingChanged: (Bool) -> Unit = { _ => }').replace('session.changed = { => revision++ }','session.changed = { => revision++; onPlayingChanged(session.route == 2) }').replace('func aboutToDisappear(): Unit { session.leave() }','func aboutToDisappear(): Unit { session.leave(); onPlayingChanged(false) }');p.write_text(s,encoding='utf-8')
p=Path('harmonyApp/entry/src/main/cangjie/src/provider/connected_session.cj');s=p.read_text(encoding='utf-8').replace('case Success(server) => activeServer = Some(server); loading = false; load("")','case Success(server) => loading = false; chooseServer(server)');p.write_text(s,encoding='utf-8')
