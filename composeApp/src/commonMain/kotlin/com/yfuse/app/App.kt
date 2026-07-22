package com.yfuse.app

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.feature.home.HomeScreen
import com.yfuse.feature.login.LoginScreen
import com.yfuse.feature.server.ServerScreen

@Composable
fun App(root: RootComponent) {
    YfuseTheme {
        Children(stack = root.childStack) { child ->
            when (val instance = child.instance) {
                is RootComponent.Child.Server -> ServerScreen(instance.component)
                is RootComponent.Child.Login -> LoginScreen(instance.component)
                is RootComponent.Child.Home -> HomeScreen(instance.component)
            }
        }
    }
}
