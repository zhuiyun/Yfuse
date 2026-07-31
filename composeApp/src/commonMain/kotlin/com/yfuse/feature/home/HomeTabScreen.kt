package com.yfuse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.SharedElementTransitionContainer
import com.yfuse.feature.calendar.CalendarScreen
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun HomeTabScreen(component: HomeTabComponent) {
    val stack by component.stack.subscribeAsState()
    SharedElementTransitionContainer(
        targetState = stack.active.instance,
        routeKey = ::routeKey,
    ) { instance ->
        when (instance) {
            is HomeTabComponent.Child.Home -> HomeScreen(instance.component)
            is HomeTabComponent.Child.Detail -> DetailScreen(instance.component)
            is HomeTabComponent.Child.Player -> PlayerScreen(instance.component)
            is HomeTabComponent.Child.Info -> TmdbInfoScreen(instance.component)
            is HomeTabComponent.Child.Calendar -> CalendarScreen(instance.component)
        }
    }
}

/** Keeps each route's scrolled position while it waits in the back stack. */
private fun routeKey(child: HomeTabComponent.Child): String = when (child) {
    is HomeTabComponent.Child.Home -> "home"
    is HomeTabComponent.Child.Detail -> "detail"
    is HomeTabComponent.Child.Player -> "player"
    is HomeTabComponent.Child.Info -> "info"
    is HomeTabComponent.Child.Calendar -> "calendar"
}
