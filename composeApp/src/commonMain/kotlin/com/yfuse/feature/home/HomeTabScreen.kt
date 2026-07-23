package com.yfuse.feature.home

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.yfuse.feature.detail.DetailScreen
import com.yfuse.feature.player.PlayerScreen

@Composable
fun HomeTabScreen(component: HomeTabComponent) {
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is HomeTabComponent.Child.Home -> HomeScreen(instance.component)
            is HomeTabComponent.Child.Detail -> DetailScreen(instance.component)
            is HomeTabComponent.Child.Player -> PlayerScreen(instance.component)
            is HomeTabComponent.Child.Info -> TmdbInfoScreen(instance.item, instance.onBack)
        }
    }
}
