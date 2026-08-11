package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay

private enum class BackOverlayDestination { Underlay, Overlay }

/** Full-screen overlay hosted by AndroidX's default predictive navigation transition. */
@Composable
fun BackOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    ReportOverlayVisible()
    val currentContent by rememberUpdatedState(content)
    val dialogMetadata = remember {
        DialogSceneStrategy.dialog(
            DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        )
    }
    val sceneStrategies = remember {
        listOf(DialogSceneStrategy<BackOverlayDestination>())
    }
    NavDisplay(
        backStack = BackOverlayDestination.entries,
        onBack = onBack,
        sceneStrategies = sceneStrategies,
        modifier = modifier.fillMaxSize(),
        entryProvider = { destination ->
            NavEntry(
                key = destination,
                contentKey = destination.name,
                metadata = if (destination == BackOverlayDestination.Overlay) {
                    dialogMetadata
                } else {
                    emptyMap()
                },
            ) { entry ->
                CompositionLocalProvider(
                    LocalRouteVisible provides (entry == BackOverlayDestination.Overlay),
                ) {
                    when (entry) {
                        // The app page remains composed beneath the official OverlayScene.
                        BackOverlayDestination.Underlay -> Box(Modifier.fillMaxSize())
                        BackOverlayDestination.Overlay ->
                            Box(Modifier.fillMaxSize(), content = currentContent)
                    }
                }
            }
        },
    )
}
