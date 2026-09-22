package com.yfuse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/** Two frame boundaries allow the initial composition to be displayed before synchronization starts. */
@Composable
fun BindBackgroundServices(root: RootComponent) {
    LaunchedEffect(root) {
        withFrameNanos { }
        withFrameNanos { }
        root.startBackgroundServices()
    }
}
