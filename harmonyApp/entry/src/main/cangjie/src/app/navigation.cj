package com.yfuse.harmony.entry.app

import std.collection.*

public enum AppRoute {
    | Root(RootTab)
    | TmdbInfo(String)
    | Calendar
    | LibraryGrid(String)
    | Detail(String, String)
    | SeasonEpisodes(String, String)
    | ServerEditor(String)
    | Downloads
    | AccountSettings
    | AccountSessions
    | MediaDiscoverySettings
    | Player(String, String)
}

/** Root tab selection does not destroy nested state; back always unwinds the active tab stack. */
public class NavigationState {
    private let stacks = HashMap<String, ArrayList<AppRoute>>()
    public var activeTab: RootTab = RootTab.Home

    public init() {
        stacks[tabKey(RootTab.Home)] = initialStack(RootTab.Home)
        stacks[tabKey(RootTab.Library)] = initialStack(RootTab.Library)
        stacks[tabKey(RootTab.Servers)] = initialStack(RootTab.Servers)
        stacks[tabKey(RootTab.Search)] = initialStack(RootTab.Search)
        stacks[tabKey(RootTab.Profile)] = initialStack(RootTab.Profile)
    }

    private func initialStack(tab: RootTab): ArrayList<AppRoute> {
        let value = ArrayList<AppRoute>()
        value.append(AppRoute.Root(tab))
        return value
    }

    public func selectTab(tab: RootTab): Unit { activeTab = tab }

    public func push(route: AppRoute): Unit {
        if (let Some(stack) <- stacks.get(tabKey(activeTab))) { stack.append(route) }
    }

    public func replace(route: AppRoute): Unit {
        if (let Some(stack) <- stacks.get(tabKey(activeTab))) {
            if (!stack.isEmpty()) { stack.remove(stack.size - 1) }
            stack.append(route)
        }
    }

    public func pop(): Bool {
        if (let Some(stack) <- stacks.get(tabKey(activeTab))) {
            if (stack.size > 1) {
                stack.remove(stack.size - 1)
                return true
            }
        }
        return false
    }

    public func resetTab(tab: RootTab): Unit { stacks[tabKey(tab)] = initialStack(tab) }

    public func current(): AppRoute {
        if (let Some(stack) <- stacks.get(tabKey(activeTab))) {
            if (!stack.isEmpty()) { return stack[stack.size - 1] }
        }
        return AppRoute.Root(activeTab)
    }
}

private func tabKey(tab: RootTab): String {
    return match (tab) {
        case RootTab.Home => "home"
        case RootTab.Library => "library"
        case RootTab.Servers => "servers"
        case RootTab.Search => "search"
        case RootTab.Profile => "profile"
    }
}
