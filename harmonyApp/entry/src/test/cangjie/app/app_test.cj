package tests.app

import std.collection.*
import std.unittest.*
import std.unittest.testmacro.*
import com.yfuse.harmony.entry.app.*
import com.yfuse.harmony.entry.data.*

private func routeName(value: AppRoute): String {
    return match (value) {
        case AppRoute.Root(_) => "root"
        case AppRoute.TmdbInfo(_) => "tmdb"
        case AppRoute.Calendar => "calendar"
        case AppRoute.LibraryGrid(_) => "grid"
        case AppRoute.Detail(_, _) => "detail"
        case AppRoute.SeasonEpisodes(_, _) => "episodes"
        case AppRoute.ServerEditor(_) => "editor"
        case AppRoute.Downloads => "downloads"
        case AppRoute.AccountSettings => "account"
        case AppRoute.AccountSessions => "sessions"
        case AppRoute.Player(_, _) => "player"
    }
}

@Test
func each_tab_keeps_its_own_stack(): Unit {
    let navigation = NavigationState()
    navigation.selectTab(RootTab.Home)
    navigation.push(AppRoute.Detail("s1", "i1"))
    @Expect(routeName(navigation.current()), "detail")

    // Switching tabs must not destroy what the previous tab was showing.
    navigation.selectTab(RootTab.Search)
    @Expect(routeName(navigation.current()), "root")

    navigation.selectTab(RootTab.Home)
    @Expect(routeName(navigation.current()), "detail")
}

@Test
func back_unwinds_only_the_active_tab_and_stops_at_its_root(): Unit {
    let navigation = NavigationState()
    navigation.selectTab(RootTab.Library)
    navigation.push(AppRoute.LibraryGrid("lib1"))
    navigation.push(AppRoute.Detail("s1", "i1"))

    @Expect(navigation.pop(), true)
    @Expect(routeName(navigation.current()), "grid")
    @Expect(navigation.pop(), true)
    @Expect(routeName(navigation.current()), "root")
    // At the tab root there is nothing left to unwind, and the shell decides what happens next.
    @Expect(navigation.pop(), false)
}

@Test
func replace_swaps_the_top_without_growing_the_stack(): Unit {
    let navigation = NavigationState()
    navigation.selectTab(RootTab.Home)
    navigation.push(AppRoute.Detail("s1", "i1"))
    navigation.replace(AppRoute.Player("s1", "i1"))
    @Expect(routeName(navigation.current()), "player")
    @Expect(navigation.pop(), true)
    @Expect(routeName(navigation.current()), "root")
}

@Test
func resetting_a_tab_returns_it_to_its_root(): Unit {
    let navigation = NavigationState()
    navigation.selectTab(RootTab.Profile)
    navigation.push(AppRoute.Downloads)
    navigation.push(AppRoute.AccountSessions)
    navigation.resetTab(RootTab.Profile)
    @Expect(routeName(navigation.current()), "root")
    @Expect(navigation.pop(), false)
}

private func group(serverId: String): SearchResultGroup {
    let items = ArrayList<MediaItemModel>()
    items.add(MediaItemModel(serverId, "item-1", "结果"))
    return SearchResultGroup(serverId, "服务器", items)
}

@Test
func a_stale_search_response_is_discarded(): Unit {
    let state = SearchViewState()
    let first = state.beginQuery("阿甘")
    let second = state.beginQuery("阿甘正传")

    let stale = ArrayList<SearchResultGroup>()
    stale.add(group("s1"))
    @Expect(state.accept(first, "阿甘", stale), false)
    // The screen still shows nothing from the abandoned request.
    @Expect(state.groups.size, 0)

    let fresh = ArrayList<SearchResultGroup>()
    fresh.add(group("s1"))
    @Expect(state.accept(second, "阿甘正传", fresh), true)
    @Expect(state.groups.size, 1)
    @Expect(state.submittedQuery, "阿甘正传")
}

@Test
func an_empty_result_set_reports_empty_rather_than_content(): Unit {
    let state = SearchViewState()
    let generation = state.beginQuery("找不到的片名")
    @Expect(state.accept(generation, "找不到的片名", ArrayList<SearchResultGroup>()), true)
    let empty = match (state.phase) {
        case LoadPhase.Empty => true
        case _ => false
    }
    @Expect(empty, true)
}

@Test
func clearing_the_query_returns_to_idle(): Unit {
    let state = SearchViewState()
    state.beginQuery("阿甘")
    state.beginQuery("")
    let idle = match (state.phase) {
        case LoadPhase.Idle => true
        case _ => false
    }
    @Expect(idle, true)
    @Expect(state.groups.size, 0)
}

@Test
func history_is_deduplicated_newest_first_and_capped(): Unit {
    let state = SearchViewState()
    var generation = state.beginQuery("a")
    state.accept(generation, "a", ArrayList<SearchResultGroup>())
    generation = state.beginQuery("b")
    state.accept(generation, "b", ArrayList<SearchResultGroup>())
    generation = state.beginQuery("a")
    state.accept(generation, "a", ArrayList<SearchResultGroup>())

    @Expect(state.history.size, 2)
    @Expect(state.history[0], "a")
    @Expect(state.history[1], "b")

    for (index in 0..15) {
        let g = state.beginQuery("q${index}")
        state.accept(g, "q${index}", ArrayList<SearchResultGroup>())
    }
    @Expect(state.history.size <= 10, true)
}
