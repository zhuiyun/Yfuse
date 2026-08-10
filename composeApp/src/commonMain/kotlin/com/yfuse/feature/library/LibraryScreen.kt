package com.yfuse.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.SharedElementTransitionContainer
import com.yfuse.core.designsystem.detailRouteIdentity
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

/** Renders the media-library navigation stack: Home -> Grid -> Detail. */
@Composable
fun LibraryScreen(component: LibraryComponent) {
    val stack by component.stack.subscribeAsState()
    SharedElementTransitionContainer(
        targetState = stack.active.instance,
        routeKey = ::routeKey,
        depth = stack.items.size,
        previous = stack.backStack.lastOrNull()?.instance,
    ) { instance ->
        when (instance) {
            is LibraryComponent.Child.Home -> LibraryHomeScreen(instance.component)
            is LibraryComponent.Child.Grid -> LibraryGridScreen(instance.component)
            is LibraryComponent.Child.Detail -> DetailScreen(instance.component)
            is LibraryComponent.Child.Player -> PlayerScreen(instance.component)
        }
    }
}

/** Keeps each route's scrolled position while it waits in the back stack. */
private fun routeKey(child: LibraryComponent.Child): String = when (child) {
    is LibraryComponent.Child.Home -> "home"
    is LibraryComponent.Child.Grid -> "grid"
    is LibraryComponent.Child.Detail -> detailRouteIdentity(
        serverId = child.component.serverId,
        itemId = child.component.itemId,
    )
    is LibraryComponent.Child.Player -> "player"
}
