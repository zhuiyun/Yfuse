package com.yfuse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.SharedElementTransitionContainer
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun HomeTabScreen(component: HomeTabComponent) {
    val stack by component.stack.subscribeAsState()
    SharedElementTransitionContainer(targetState = stack.active.instance) { instance ->
        when (instance) {
            is HomeTabComponent.Child.Home -> HomeScreen(instance.component)
            is HomeTabComponent.Child.Detail -> DetailScreen(instance.component)
            is HomeTabComponent.Child.Player -> PlayerScreen(instance.component)
            is HomeTabComponent.Child.Info -> TmdbInfoScreen(instance.component)
        }
    }
}
