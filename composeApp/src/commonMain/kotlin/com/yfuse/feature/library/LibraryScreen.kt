package com.yfuse.feature.library

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.yfuse.feature.detail.DetailScreen

/** Renders the media-library navigation stack: Home -> Grid -> Detail. */
@Composable
fun LibraryScreen(component: LibraryComponent) {
    Children(stack = component.stack) { child ->
        when (val instance = child.instance) {
            is LibraryComponent.Child.Home -> LibraryHomeScreen(instance.component)
            is LibraryComponent.Child.Grid -> LibraryGridScreen(instance.component)
            is LibraryComponent.Child.Detail -> DetailScreen(instance.component)
        }
    }
}
