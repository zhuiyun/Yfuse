package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yfuse.core.designsystem.OfficialNavDisplay

@Composable
fun ProfileTabScreen(component: ProfileTabComponent) {
    val stack by component.stack.subscribeAsState()
    OfficialNavDisplay(
        backStack = stack.items,
        onBack = {},
        contentKey = { "profile:${it.configuration}" },
    ) { child ->
        when (val instance = child.instance) {
            is ProfileTabComponent.Child.Home -> ProfileScreen(instance.component)
        }
    }
}
