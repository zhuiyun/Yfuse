package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/**
 * AndroidX Navigation 3 host using only the library defaults.
 *
 * No transition is supplied here: [NavDisplay] owns its built-in forward, pop, and predictive
 * pop behavior exactly as documented by AndroidX. The caller continues to own the back stack,
 * which lets the existing Decompose components remain the source of navigation state.
 */
@Composable
fun <T : Any> OfficialNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    contentKey: (T) -> String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val currentTop by rememberUpdatedState(backStack.last())
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = onBack,
        entryProvider = { key ->
            NavEntry(
                key = key,
                contentKey = contentKey(key),
            ) { entryKey ->
                CompositionLocalProvider(
                    LocalRouteVisible provides (entryKey == currentTop),
                ) {
                    currentContent(entryKey)
                }
            }
        },
    )
}
