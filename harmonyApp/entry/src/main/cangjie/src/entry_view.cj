package com.yfuse.harmony.entry

import ohos.base.*
import ohos.component.*
import ohos.state_manage.*
import ohos.state_macro_manage.*

@Entry
@Component
class EntryView {
    @State var connectedPlaying: Bool = false
    let homeSession = createConnectedMediaSession()
    let librarySession = createConnectedMediaSession()
    let serversSession = createConnectedMediaSession()
    let searchSession = createConnectedMediaSession()
    @State var selectedTab: Int64 = 0
    @State var homeRoute: Int64 = 0
    @State var libraryRoute: Int64 = 0
    @State var serversRoute: Int64 = 0
    @State var searchRoute: Int64 = 0
    @State var profileRoute: Int64 = 0
    @State var playerSource: String = ""

    func build() {
        Column {
            if (atRoot()) {
                RootContent()
            } else {
                SecondaryContent()
            }

            if (atRoot() && !connectedPlaying) {
                Row {
                    NavButton("首页", 0)
                    NavButton("库", 1)
                    NavButton("服务器", 2)
                    NavButton("搜索", 3)
                    NavButton("我的", 4)
                }
                .height(56)
            }
        }
        .width(100.percent)
        .height(100.percent)
    }

    @Builder
    func RootContent() {
        if (selectedTab == 0) {
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
            ProfileScreen(
                onOpenDownloads: () => { profileRoute = 1 },
                onOpenAccount: () => { profileRoute = 2 },
                onOpenSessions: () => { profileRoute = 3 }
            )
        }
    }

    @Builder
    func SecondaryContent() {
        Column {
            Row {
                Button("‹ 返回").onClick(() => { popToRoot() })
                Blank()
            }.height(52).padding(left: 12, right: 12)

            if (selectedTab == 0) {
                if (homeRoute == 1) {
                    DetailScreen(
                        onPlay: () => { playerSource = ""; homeRoute = 2 },
                        onOpenEpisodes: () => { homeRoute = 5 }
                    )
                } else if (homeRoute == 2) {
                    PlayerScreen(source: playerSource, onBack: () => { homeRoute = 0 })
                } else if (homeRoute == 3) {
                    TmdbInfoScreen(onOpenDetail: () => { homeRoute = 1 })
                } else if (homeRoute == 4) {
                    CalendarScreen(onOpenDetail: () => { homeRoute = 1 })
                } else {
                    SeasonEpisodesScreen(onPlay: () => { playerSource = ""; homeRoute = 2 })
                }
            } else if (selectedTab == 1) {
                if (libraryRoute == 1) {
                    LibraryGridScreen(onOpenDetail: () => { libraryRoute = 2 })
                } else if (libraryRoute == 2) {
                    DetailScreen(
                        onPlay: () => { playerSource = ""; libraryRoute = 3 },
                        onOpenEpisodes: () => { libraryRoute = 4 }
                    )
                } else if (libraryRoute == 3) {
                    PlayerScreen(source: playerSource, onBack: () => { libraryRoute = 0 })
                } else {
                    SeasonEpisodesScreen(onPlay: () => { playerSource = ""; libraryRoute = 3 })
                }
            } else if (selectedTab == 2) {
                ServerEditorScreen()
            } else if (selectedTab == 3) {
                if (searchRoute == 1) {
                    DetailScreen(
                        onPlay: () => { playerSource = ""; searchRoute = 2 },
                        onOpenEpisodes: () => { searchRoute = 3 }
                    )
                } else if (searchRoute == 2) {
                    PlayerScreen(source: playerSource, onBack: () => { searchRoute = 0 })
                } else {
                    SeasonEpisodesScreen(onPlay: () => { playerSource = ""; searchRoute = 2 })
                }
            } else {
                if (profileRoute == 1) {
                    DownloadsScreen()
                } else if (profileRoute == 2) {
                    AccountSettingsScreen(onOpenSessions: () => { profileRoute = 3 })
                } else if (profileRoute == 3) {
                    AccountSessionsScreen()
                }
            }
        }.width(100.percent).height(100.percent)
    }

    @Builder
    func NavButton(label: String, index: Int64) {
        Button(label)
            .onClick { event =>
                if (selectedTab == index) { popToRoot() } else { selectedTab = index }
            }
            .layoutWeight(1)
            .height(48)
    }

    private func atRoot(): Bool {
        if (selectedTab == 0) { return homeRoute == 0 }
        if (selectedTab == 1) { return libraryRoute == 0 }
        if (selectedTab == 2) { return serversRoute == 0 }
        if (selectedTab == 3) { return searchRoute == 0 }
        return profileRoute == 0
    }

    private func popToRoot(): Unit {
        if (selectedTab == 0) { homeRoute = 0 }
        else if (selectedTab == 1) { libraryRoute = 0 }
        else if (selectedTab == 2) { serversRoute = 0 }
        else if (selectedTab == 3) { searchRoute = 0 }
        else { profileRoute = 0 }
    }
}
