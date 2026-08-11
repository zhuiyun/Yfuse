package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/** AndroidX Navigation 3 host using the library's unmodified transition defaults. */
@Composable
fun <T : Any> OfficialNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    contentKey: (T) -> String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val parentRouteVisible = LocalRouteVisible.current
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
                    LocalRouteVisible provides (parentRouteVisible && entryKey == currentTop),
                ) {
                    currentContent(entryKey)
                }
            }
        },
    )
}
