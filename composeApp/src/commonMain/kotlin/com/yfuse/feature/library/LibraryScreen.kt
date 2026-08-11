package com.yfuse.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.OfficialNavDisplay
import com.yfuse.feature.detail.DetailScreen
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
    ) { entry ->
        val instance = entry.instance
        when (instance) {
            is LibraryComponent.Child.Home -> LibraryHomeScreen(instance.component)
            is LibraryComponent.Child.Grid -> LibraryGridScreen(instance.component)
            is LibraryComponent.Child.Detail -> DetailScreen(instance.component)
            is LibraryComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}

/** Includes autoPlay, playback position, media source, and every other Config field. */
private fun routeKey(configuration: LibraryComponent.Config): String = "library:$configuration"
