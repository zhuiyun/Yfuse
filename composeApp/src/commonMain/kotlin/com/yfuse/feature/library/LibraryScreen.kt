package com.yfuse.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.app.LocalWideWindow
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.history.PlaybackHistoryScreen
import com.yfuse.feature.history.WatchingEntrySection
import com.yfuse.feature.home.WatchingCenterScreen
import com.yfuse.feature.player.PlayerScreen

/** Renders the media-library navigation stack: Home -> Grid -> Detail. */
@Composable
fun LibraryScreen(component: LibraryComponent) {
    val stack by component.stack.subscribeAsState()
    OfficialNavDisplay(
        backStack = stack.items,
        onBack = component::navigateBack,
        contentKey = { routeKey(it.configuration) },
        modifier = Modifier.fillMaxSize(),
        listPane = { it.instance !is LibraryComponent.Child.Detail },
        isLauncher = { it.instance is LibraryComponent.Child.Player },
        isDetail = { it.instance is LibraryComponent.Child.Detail },
    ) { entry ->
        val instance = entry.instance
        when (instance) {
            LibraryComponent.Child.Unified ->
                UnifiedLibraryScreen(
                    repository = component.repo,
                    registry = component.registry,
                    onBack = component::navigateBack,
                    onOpenItem = { serverId, itemId -> component.selectUnifiedItem(serverId, itemId) },
                )
            is LibraryComponent.Child.Home ->
                LibraryHomeScreen(instance.component)
            is LibraryComponent.Child.History ->
                PlaybackHistoryScreen(
                    instance.component,
                    rootTab = component.historyIsRoot,
                    contentEntries =
                        if (component.historyIsRoot) {
                            {
                                WatchingEntrySection(
                                    onOpenDiscovery = component::openDiscovery,
                                    onOpenCalendar = component::openCalendar,
                                )
                            }
                        } else {
                            null
                        },
                )
            is LibraryComponent.Child.WatchingCenter ->
                WatchingCenterScreen(
                    component = instance.component,
                    onBack = instance.onBack,
                    onOpenHistory = instance.onOpenHistory,
                    onOpenDiscovery = instance.onOpenDiscovery,
                )
            is LibraryComponent.Child.Grid ->
                LibraryGridScreen(
                    instance.component,
                    selectedItemId =
                        if (LocalWideWindow.current) {
                            (stack.active.instance as? LibraryComponent.Child.Detail)?.component?.itemId
                        } else {
                            null
                        },
                )
            is LibraryComponent.Child.Detail -> DetailScreen(instance.component)
            is LibraryComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}

/** Includes autoPlay, playback position, media source, and every other Config field. */
private fun routeKey(configuration: LibraryComponent.Config): String = "library:$configuration"
