package com.yfuse.harmony.entry.app

public enum RootTab {
    | Home
    | Library
    | Servers
    | Search
    | Profile
}

public enum AppTheme {
    | System
    | Light
    | Dark
}

public class AppState {
    public var activeTab: RootTab = RootTab.Home
    public var theme: AppTheme = AppTheme.System
    public var hasServers: Bool = false
    public var miniPlayerVisible: Bool = false
    public var currentTitle: String = ""

    public func selectTab(tab: RootTab): Unit {
        activeTab = tab
    }
}
