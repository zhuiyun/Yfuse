package com.yfuse.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.router.stack.ChildStack
import com.yfuse.app.BindBackgroundServices
import com.yfuse.app.RootComponent
import com.yfuse.app.effectiveGlassStyle
import com.yfuse.app.rememberAppAccessibilityOptions
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalDialogBackdrop
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.ParticleLight
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.network.LocalNetworkAccessNotice
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.library.UnifiedLibraryScreen
import com.yfuse.feature.personal.PersonalDiscoveryGuard
import com.yfuse.feature.player.PlaybackReportingWarning
import com.yfuse.feature.player.PlayerScreen
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent
import com.yfuse.tv.focus.requestFocusWhenAttached
import kotlinx.coroutines.launch

private data class TvDestination(
    val tab: RootComponent.Tab,
    val label: String,
    val icon: ImageVector,
)

private val tvDestinations =
    listOf(
        TvDestination(RootComponent.Tab.Home, "首页", AppIcons.TabHome),
        TvDestination(RootComponent.Tab.Browse, "媒体库", AppIcons.TabLibrary),
        TvDestination(RootComponent.Tab.Search, "搜索", AppIcons.SearchTab),
        TvDestination(RootComponent.Tab.Servers, "服务器", AppIcons.TabServers),
        TvDestination(RootComponent.Tab.Profile, "我的与设置", AppIcons.TabProfile),
    )

/** Public Android-TV entry point used by TvMainActivity. */
@Composable
fun TvApp(component: RootComponent) {
    // The phone's builder: the person's switches plus the system's 「移除动画」, which the
    // television never heard — its reel kept turning and its focus kept scaling with animations
    // off for the whole device.
    val accessibility = rememberAppAccessibilityOptions(component.themePreferences)
    val dialogAnimation by component.themePreferences.dialogAnimation.collectAsState()
    val glassStyle by component.themePreferences.glassStyle.collectAsState()
    val loadingAnimation by component.themePreferences.loadingAnimation.collectAsState()
    val glassMaterials by component.themePreferences.glassMaterials.collectAsState()

    // Always dark. The shell paints [TvBackground] whatever the phone's 界面模式 says, and that
    // shared preference used to hand the four shared dialogs and the unified library light
    // tokens — a grey or white panel over a dark room. A television has one theme.
    YfuseTheme(
        dark = true,
        accessibility = accessibility,
        glassStyle = effectiveGlassStyle(glassStyle, accessibility.reduceTransparency),
        dialogAnimation = dialogAnimation.onTv(),
        // A set-top GPU pays for no decoration it does not have to: the phone's default 轻柔
        // particles lit on every focus of a shared control, and there is no setting for them here.
        particleLight = ParticleLight.Off,
        loadingAnimation = loadingAnimation,
        glassMaterials = glassMaterials,
    ) {
        // Dialog panels stay opaque, like every other plate on the television (see TvTokens):
        // with no page backdrop to sample, the shared dialog paints its solid body instead of
        // blurring the whole page behind it for as long as it is open.
        CompositionLocalProvider(LocalDialogBackdrop provides null) {
            com.yfuse.app.BindProductServices(component)
            val savedServers by component.dependencies.serverRegistry.data
                .collectAsState()
            val permissionScope = rememberCoroutineScope()
            LocalNetworkAccessNotice(hasServers = savedServers.servers.isNotEmpty()) {
                permissionScope.launch { component.dependencies.serverHealthMonitor.refreshAll() }
            }
            TvRoot(component)
            PlaybackReportingWarning(component.dependencies.playbackReportingCoordinator)
        }
    }
}

/** Kept as a separate API so previews and TV shell tests can host the root without rebuilding theme. */
@Composable
fun TvRoot(component: RootComponent) {
    BindBackgroundServices(component)
    val activeTab by component.activeTab.subscribeAsState()
    val homeStack by component.home.stack.subscribeAsState()
    val libraryStack by component.browse.stack.subscribeAsState()
    val searchStack by component.search.stack.subscribeAsState()
    val profileStack by component.profile.stack.subscribeAsState()
    val focusMemory = remember { TvUiFocusMemory() }
    val navRequesters = remember { RootComponent.Tab.entries.associateWith { FocusRequester() } }
    val contentRequesters = remember { RootComponent.Tab.entries.associateWith { FocusRequester() } }

    // A pushed page keeps its saveable state — its list's scroll position above all — for as long
    // as it is in its tab's stack, so detail A → related B → back finds A where the viewer left it.
    // The bare `when` this replaces rebuilt A from the top, carrying B's scroll position into it.
    val pageStates = rememberSaveableStateHolder()
    val stackedPageKeys =
        buildSet {
            homeStack.items.forEach { add(tvPageKey(RootComponent.Tab.Home, it.key)) }
            libraryStack.items.forEach { add(tvPageKey(RootComponent.Tab.Browse, it.key)) }
            searchStack.items.forEach { add(tvPageKey(RootComponent.Tab.Search, it.key)) }
        }
    val knownPageKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(stackedPageKeys) {
        // Popped pages forget, so opening the same title again starts at its top.
        (knownPageKeys - stackedPageKeys).forEach(pageStates::removeState)
        knownPageKeys.clear()
        knownPageKeys.addAll(stackedPageKeys)
    }

    val atRoot =
        when (activeTab) {
            RootComponent.Tab.Home -> homeStack.active.instance is HomeTabComponent.Child.Home
            RootComponent.Tab.Browse -> libraryStack.active.instance is LibraryComponent.Child.Home
            RootComponent.Tab.Servers -> true
            RootComponent.Tab.Search -> searchStack.active.instance is SearchComponent.Child.Home
            RootComponent.Tab.Profile -> profileStack.active.instance is ProfileTabComponent.Child.Home
        }

    // Back walks the hierarchy: out of a sub-screen, then to Home, and only from Home's root
    // out of the app — the television quality checklist's expectation, and every other TV
    // client's behaviour.
    BackHandler(enabled = !atRoot || activeTab != RootComponent.Tab.Home) {
        if (atRoot) {
            component.selectTab(RootComponent.Tab.Home)
            return@BackHandler
        }
        when (activeTab) {
            RootComponent.Tab.Home -> component.home.navigateBack()
            RootComponent.Tab.Browse -> component.browse.navigateBack()
            RootComponent.Tab.Search -> component.search.navigateBack()
            RootComponent.Tab.Profile -> component.profile.navigateBack()
            RootComponent.Tab.Servers -> Unit
        }
    }

    LaunchedEffect(Unit) {
        navRequesters.getValue(activeTab).requestFocusWhenAttached()
        component.dependencies.playbackReportingCoordinator.flushPending()
    }

    val page =
        if (atRoot) {
            TvPage.Root
        } else {
            when (activeTab) {
                RootComponent.Tab.Home -> TvPage.pushed(activeTab, homeStack)
                RootComponent.Tab.Browse -> TvPage.pushed(activeTab, libraryStack)
                RootComponent.Tab.Search -> TvPage.pushed(activeTab, searchStack)
                RootComponent.Tab.Profile -> TvPage.pushed(activeTab, profileStack)
                RootComponent.Tab.Servers -> TvPage.Root
            }
        }
    val currentPage by rememberUpdatedState(page)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val travel = with(LocalDensity.current) { TvPageMotion.travel.roundToPx() }

    Box(Modifier.fillMaxSize().background(TvBackground)) {
        // Each page renders from the target it was handed, never from the live stacks: the page
        // that is leaving has to keep drawing itself, not the one that replaced it.
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Deeper arrives from the right, and back arrives from the left.
                val direction = if (targetState.depth >= initialState.depth) 1 else -1
                TvPageMotion.transform(reduceMotion, travel * direction) using Motion.sizeTransform(reduceMotion)
            },
            label = "tv-route",
        ) { shown ->
            // The page on its way out keeps focus until the new one takes it; a second press of
            // 确定 in that moment must not open the same title again from the page that is leaving.
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { currentPage != shown }) {
                TvRoutePage(
                    shown = shown,
                    component = component,
                    activeTab = activeTab,
                    focusMemory = focusMemory,
                    navRequesters = navRequesters,
                    contentRequesters = contentRequesters,
                    pageStates = pageStates,
                )
            }
        }
    }
}

@Composable
private fun TvRoutePage(
    shown: TvPage,
    component: RootComponent,
    activeTab: RootComponent.Tab,
    focusMemory: TvUiFocusMemory,
    navRequesters: Map<RootComponent.Tab, FocusRequester>,
    contentRequesters: Map<RootComponent.Tab, FocusRequester>,
    pageStates: SaveableStateHolder,
) {
    when (shown) {
        TvPage.Root ->
            Row(Modifier.fillMaxSize()) {
                TvNavigationRail(
                    selected = activeTab,
                    navRequesters = navRequesters,
                    contentRequesters = contentRequesters,
                    focusMemory = focusMemory,
                    onSelected = component::selectTab,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(end = TvSafeHorizontal),
                ) {
                    TvRootTabContent(
                        component = component,
                        activeTab = activeTab,
                        focusMemory = focusMemory,
                        navRequesters = navRequesters,
                        contentRequesters = contentRequesters,
                    )
                }
            }
        is TvPage.Pushed ->
            pageStates.SaveableStateProvider(shown.stateKey) {
                TvPushedPage(component = component, child = shown.child, focusMemory = focusMemory)
            }
    }
}

/** What fills the window: the tabs with their rail, or one page pushed onto a tab's stack. */
private sealed interface TvPage {
    /** How deep the page sits in its tab's stack; the tabs themselves are 0. */
    val depth: Int

    data object Root : TvPage {
        override val depth: Int = 0
    }

    /** [stateKey] names the page in the saved-state holder: unique within the stacks, and a String. */
    data class Pushed(
        val stateKey: String,
        val child: Any,
        override val depth: Int,
    ) : TvPage

    companion object {
        fun pushed(
            tab: RootComponent.Tab,
            stack: ChildStack<*, Any>,
        ): TvPage = Pushed(tvPageKey(tab, stack.active.key), stack.active.instance, stack.backStack.size)
    }
}

/** A pushed page's key in the saved-state holder. */
private fun tvPageKey(
    tab: RootComponent.Tab,
    childKey: String,
): String = "${tab.name}:$childKey"

@Composable
private fun TvNavigationRail(
    selected: RootComponent.Tab,
    navRequesters: Map<RootComponent.Tab, FocusRequester>,
    contentRequesters: Map<RootComponent.Tab, FocusRequester>,
    focusMemory: TvUiFocusMemory,
    onSelected: (RootComponent.Tab) -> Unit,
) {
    Column(
        Modifier
            .width(TvRailWidth)
            .fillMaxHeight()
            .padding(start = TvSafeHorizontal, top = TvSafeVertical, bottom = TvSafeVertical),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Yfuse",
            color = TvOnSurface,
            fontSize = TvType.section,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp, bottom = 34.dp),
        )
        tvDestinations.forEach { destination ->
            val isSelected = destination.tab == selected
            TvFocusableSurface(
                stableId = "navigation:${destination.tab.name}",
                focusScope = "navigation",
                focusMemory = focusMemory,
                onClick = { onSelected(destination.tab) },
                selected = isSelected,
                selectable = true,
                focusRequester = navRequesters.getValue(destination.tab),
                scaleWhenFocused = 1.025f,
                modifier =
                    Modifier
                        .widthIn(min = 124.dp)
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionRight
                            ) {
                                if (!isSelected) {
                                    // Right into a tab that is not open opens it; the page's
                                    // entry restore brings focus in once it is there. The key
                                    // used to be consumed with nothing to move to.
                                    onSelected(destination.tab)
                                } else if (!focusMemory.requestLastForRoute(destination.tab.tvFocusRoute())) {
                                    runCatching { contentRequesters.getValue(destination.tab).requestFocus() }
                                }
                                true
                            } else {
                                false
                            }
                        },
            ) {
                // The white plate and black ink come in on the surface's focus clock; the open tab
                // is marked by the surface's own selected plate and edge, as everywhere else.
                val focus = LocalTvFocusAmount.current
                Row(
                    Modifier
                        .drawBehind { drawRect(Color.White.copy(alpha = 0.96f * focus.value.coerceIn(0f, 1f))) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvFocusIcon(
                        icon = destination.icon,
                        rest = if (isSelected) TvAccent else TvOnSurfaceMuted,
                        focused = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(13.dp))
                    TvFocusText(
                        text = destination.label,
                        rest = if (isSelected) TvOnSurface else TvOnSurfaceMuted,
                        focused = Color.Black,
                        fontSize = TvType.caption,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun RootComponent.Tab.tvFocusRoute(): String =
    when (this) {
        RootComponent.Tab.Home -> "home"
        RootComponent.Tab.Browse -> "library"
        RootComponent.Tab.Servers -> "servers"
        RootComponent.Tab.Search -> "search"
        RootComponent.Tab.Profile -> "settings"
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvRootTabContent(
    component: RootComponent,
    activeTab: RootComponent.Tab,
    focusMemory: TvUiFocusMemory,
    navRequesters: Map<RootComponent.Tab, FocusRequester>,
    contentRequesters: Map<RootComponent.Tab, FocusRequester>,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Tabs are one level: a crossfade, no travel. Each tab draws from the tab it was handed, so
    // the one leaving keeps its own requesters while it fades.
    AnimatedContent(
        targetState = activeTab,
        transitionSpec = {
            TvPageMotion.transform(reduceMotion, travelPx = 0) using Motion.sizeTransform(reduceMotion)
        },
        label = "tv-tab",
    ) { tab ->
        val navigationRequester = navRequesters.getValue(tab)
        // Left out of the page lands on its own tab in the rail, from wherever it leaves. Pages used
        // to guess which cards sat in their first column — search assumed six columns on a grid
        // that fits four — and every card they missed fell to whichever rail item was nearest.
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Left) navigationRequester else FocusRequester.Default
                    }
                }.focusGroup(),
        ) {
            TvRootTabPage(
                component = component,
                activeTab = tab,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                contentRequester = contentRequesters.getValue(tab),
            )
        }
    }
}

/**
 * One tab's root page. It is looked up anywhere in the stack rather than as the active child: the
 * tabs keep drawing while they fade out under a page just pushed on top of them.
 */
@Composable
private fun TvRootTabPage(
    component: RootComponent,
    activeTab: RootComponent.Tab,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    when (activeTab) {
        RootComponent.Tab.Home -> {
            val stack by component.home.stack.subscribeAsState()
            val child = stack.items.firstNotNullOfOrNull { it.instance as? HomeTabComponent.Child.Home }
            child?.let {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(contentRequester)
                        .focusProperties {
                            left = navigationRequester
                        }.focusGroup(),
                ) {
                    PersonalDiscoveryGuard(onOpenLibrary = { component.selectTab(RootComponent.Tab.Browse) }) {
                        TvHomeScreen(
                            component = it.component,
                            focusMemory = focusMemory,
                            navigationRequester = navigationRequester,
                            contentRequester = contentRequester,
                        )
                    }
                }
            }
        }
        RootComponent.Tab.Browse -> {
            val stack by component.browse.stack.subscribeAsState()
            val child = stack.items.firstNotNullOfOrNull { it.instance as? LibraryComponent.Child.Home }
            child?.let {
                TvLibraryHomeScreen(
                    component = it.component,
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    contentRequester = contentRequester,
                )
            }
        }
        RootComponent.Tab.Servers ->
            TvServersScreen(
                component = component.servers,
                focusMemory = focusMemory,
                navigationRequester = navigationRequester,
                contentRequester = contentRequester,
            )
        RootComponent.Tab.Search -> {
            val stack by component.search.stack.subscribeAsState()
            val child = stack.items.firstNotNullOfOrNull { it.instance as? SearchComponent.Child.Home }
            child?.let {
                TvSearchHomeScreen(
                    component = it.component,
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    contentRequester = contentRequester,
                )
            }
        }
        RootComponent.Tab.Profile -> {
            val stack by component.profile.stack.subscribeAsState()
            val child = stack.items.firstNotNullOfOrNull { it.instance as? ProfileTabComponent.Child.Home }
            child?.let {
                TvSettingsScreen(
                    component = it.component,
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    contentRequester = contentRequester,
                )
            }
        }
    }
}

/** A page pushed onto a tab's stack, drawn from the child it was handed — see [TvPage]. */
@Composable
private fun TvPushedPage(
    component: RootComponent,
    child: Any,
    focusMemory: TvUiFocusMemory,
) {
    when (child) {
        is HomeTabComponent.Child.Detail -> TvDetailScreen(child.component, focusMemory)
        is HomeTabComponent.Child.Player -> PlayerScreen(child.component)
        is HomeTabComponent.Child.Info -> TvTmdbInfoScreen(child.component, focusMemory)
        is HomeTabComponent.Child.Calendar -> TvCalendarScreen(child.component, focusMemory)
        is LibraryComponent.Child.Grid -> TvLibraryGridScreen(child.component, focusMemory)
        LibraryComponent.Child.Unified -> TvUnifiedLibraryScreen(component.browse, focusMemory)
        is LibraryComponent.Child.Detail -> TvDetailScreen(child.component, focusMemory)
        is LibraryComponent.Child.Player -> PlayerScreen(child.component)
        is SearchComponent.Child.Detail -> TvDetailScreen(child.component, focusMemory)
        is SearchComponent.Child.Player -> PlayerScreen(child.component)
    }
}

@Composable
private fun TvUnifiedLibraryScreen(
    component: LibraryComponent,
    focusMemory: TvUiFocusMemory,
) {
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }
    Column(Modifier.fillMaxSize().padding(horizontal = TvSafeHorizontal, vertical = TvSafeVertical)) {
        TvSettingRow(
            title = "返回媒体库",
            value = "全部服务器",
            stableId = "library:unified:back",
            focusMemory = focusMemory,
            onClick = component::navigateBack,
            icon = AppIcons.ChevronLeft,
            focusScope = "library:unified",
            focusRequester = firstRequester,
        )
        Box(Modifier.weight(1f).focusGroup()) {
            UnifiedLibraryScreen(
                repository = component.repo,
                registry = component.registry,
                onBack = component::navigateBack,
                onOpenItem = { serverId, itemId -> component.openDetail(serverId, itemId) },
            )
        }
    }
}
