package com.yfuse.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.RootComponent
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.feature.home.HomeTabComponent
import com.yfuse.feature.library.LibraryComponent
import com.yfuse.feature.player.PlayerScreen
import com.yfuse.feature.profile.ProfileTabComponent
import com.yfuse.feature.search.SearchComponent
import com.yfuse.tv.focus.requestFocusWhenAttached

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
        TvDestination(RootComponent.Tab.Profile, "设置", AppIcons.TabProfile),
    )

/** Public Android-TV entry point used by TvMainActivity. */
@Composable
fun TvApp(component: RootComponent) {
    val mode by component.themePreferences.mode.collectAsState()
    val accent by component.themePreferences.accent.collectAsState()
    val reduceTransparency by component.themePreferences.reduceTransparency.collectAsState()
    val largeText by component.themePreferences.largeText.collectAsState()
    val reduceMotion by component.themePreferences.reduceMotion.collectAsState()
    val glassStyle by component.themePreferences.glassStyle.collectAsState()
    val dark = mode.resolveDark(isSystemInDarkTheme())

    YfuseTheme(
        dark = dark,
        accent = accent,
        accessibility =
            AccessibilityOptions(
                reduceTransparency = reduceTransparency,
                largeText = largeText,
                reduceMotion = reduceMotion,
            ),
        glassStyle = if (reduceTransparency) GlassStyle.Frosted else glassStyle,
    ) {
        TvRoot(component)
    }
}

/** Kept as a separate API so previews and TV shell tests can host the root without rebuilding theme. */
@Composable
fun TvRoot(component: RootComponent) {
    val activeTab by component.activeTab.subscribeAsState()
    val homeStack by component.home.stack.subscribeAsState()
    val libraryStack by component.browse.stack.subscribeAsState()
    val searchStack by component.search.stack.subscribeAsState()
    val profileStack by component.profile.stack.subscribeAsState()
    val focusMemory = remember { TvUiFocusMemory() }
    val navRequesters = remember { RootComponent.Tab.entries.associateWith { FocusRequester() } }
    val contentRequesters = remember { RootComponent.Tab.entries.associateWith { FocusRequester() } }

    val atRoot =
        when (activeTab) {
            RootComponent.Tab.Home -> homeStack.active.instance is HomeTabComponent.Child.Home
            RootComponent.Tab.Browse -> libraryStack.active.instance is LibraryComponent.Child.Home
            RootComponent.Tab.Servers -> true
            RootComponent.Tab.Search -> searchStack.active.instance is SearchComponent.Child.Home
            RootComponent.Tab.Profile -> profileStack.active.instance is ProfileTabComponent.Child.Home
        }

    BackHandler(enabled = !atRoot) {
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

    Box(Modifier.fillMaxSize().background(TvBackground)) {
        if (atRoot) {
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
                        navigationRequester = navRequesters.getValue(activeTab),
                        contentRequester = contentRequesters.getValue(activeTab),
                    )
                }
            }
        } else {
            TvSecondaryContent(
                component = component,
                activeTab = activeTab,
                focusMemory = focusMemory,
                homeChild = homeStack.active.instance,
                libraryChild = libraryStack.active.instance,
                searchChild = searchStack.active.instance,
            )
        }
    }
}

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
            fontSize = 25.sp,
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
                                val route = destination.tab.tvFocusRoute()
                                if (!focusMemory.requestLastForRoute(route)) {
                                    contentRequesters.getValue(destination.tab).requestFocus()
                                }
                                true
                            } else {
                                false
                            }
                        },
            ) { focused ->
                Row(
                    Modifier
                        .background(
                            if (focused || isSelected) {
                                Color.White.copy(alpha = if (focused) 0.96f else 0.12f)
                            } else {
                                Color.Transparent
                            },
                        ).padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        tint = if (focused) Color.Black else if (isSelected) TvAccent else TvOnSurfaceMuted,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(13.dp))
                    Text(
                        text = destination.label,
                        color = if (focused) Color.Black else if (isSelected) TvOnSurface else TvOnSurfaceMuted,
                        fontSize = 16.sp,
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

@Composable
private fun TvRootTabContent(
    component: RootComponent,
    activeTab: RootComponent.Tab,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    contentRequester: FocusRequester,
) {
    when (activeTab) {
        RootComponent.Tab.Home -> {
            val stack by component.home.stack.subscribeAsState()
            val child = stack.active.instance as? HomeTabComponent.Child.Home
            child?.let {
                TvHomeScreen(
                    component = it.component.classic,
                    focusMemory = focusMemory,
                    navigationRequester = navigationRequester,
                    contentRequester = contentRequester,
                )
            }
        }
        RootComponent.Tab.Browse -> {
            val stack by component.browse.stack.subscribeAsState()
            val child = stack.active.instance as? LibraryComponent.Child.Home
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
            val child = stack.active.instance as? SearchComponent.Child.Home
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
            val child = stack.active.instance as? ProfileTabComponent.Child.Home
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

@Composable
private fun TvSecondaryContent(
    component: RootComponent,
    activeTab: RootComponent.Tab,
    focusMemory: TvUiFocusMemory,
    homeChild: HomeTabComponent.Child,
    libraryChild: LibraryComponent.Child,
    searchChild: SearchComponent.Child,
) {
    when (activeTab) {
        RootComponent.Tab.Home ->
            when (homeChild) {
                is HomeTabComponent.Child.Detail -> TvDetailScreen(homeChild.component, focusMemory)
                is HomeTabComponent.Child.Player -> PlayerScreen(homeChild.component)
                is HomeTabComponent.Child.Info ->
                    TvTmdbInfoScreen(homeChild.component, focusMemory)
                is HomeTabComponent.Child.MediaDetail ->
                    TvMediaDiscoveryDetailScreen(homeChild.component, focusMemory)
                is HomeTabComponent.Child.Calendar ->
                    TvCalendarScreen(homeChild.component, focusMemory)
                is HomeTabComponent.Child.Home -> Unit
            }
        RootComponent.Tab.Browse ->
            when (libraryChild) {
                is LibraryComponent.Child.Grid ->
                    TvLibraryGridScreen(libraryChild.component, focusMemory)
                is LibraryComponent.Child.Detail -> TvDetailScreen(libraryChild.component, focusMemory)
                is LibraryComponent.Child.Player -> PlayerScreen(libraryChild.component)
                is LibraryComponent.Child.Home -> Unit
            }
        RootComponent.Tab.Search ->
            when (searchChild) {
                is SearchComponent.Child.Detail -> TvDetailScreen(searchChild.component, focusMemory)
                is SearchComponent.Child.Player -> PlayerScreen(searchChild.component)
                is SearchComponent.Child.Home -> Unit
            }
        RootComponent.Tab.Profile,
        RootComponent.Tab.Servers,
        -> Unit
    }
}
