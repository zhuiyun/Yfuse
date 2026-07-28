package com.yfuse.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.SharedElementTransitionContainer
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

/** Renders the media-library navigation stack: Home -> Grid -> Detail. */
@Composable
fun LibraryScreen(component: LibraryComponent) {
    val stack by component.stack.subscribeAsState()
    SharedElementTransitionContainer(targetState = stack.active.instance) { instance ->
        when (instance) {
            is LibraryComponent.Child.Home -> LibraryHomeScreen(instance.component)
            is LibraryComponent.Child.Grid -> LibraryGridScreen(instance.component)
            is LibraryComponent.Child.Detail -> DetailScreen(instance.component)
            is LibraryComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}
