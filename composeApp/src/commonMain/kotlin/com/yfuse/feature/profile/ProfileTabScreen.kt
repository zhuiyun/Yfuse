package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children

@Composable
fun ProfileTabScreen(component: ProfileTabComponent) {
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is ProfileTabComponent.Child.Home -> ProfileScreen(instance.component)
        }
    }
}
