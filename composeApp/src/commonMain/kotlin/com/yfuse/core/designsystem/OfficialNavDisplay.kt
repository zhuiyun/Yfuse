package com.yfuse.core.designsystem

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay

/** AndroidX Navigation 3 host with back animations disabled. */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    val sharedMediaController = remember { SharedMediaTransitionController() }
    val previousDepth = remember { intArrayOf(backStack.size) }
    if (backStack.size < previousDepth[0]) {
        // The user explicitly selected hard-cut returns. Suppress the shared overlay before
        // NavDisplay composes the smaller stack, including a very fast back during the push.
        sharedMediaController.suppressForPop()
    }
    SideEffect { previousDepth[0] = backStack.size }
    val activeSharedKey = sharedMediaController.activeKey
    LaunchedEffect(activeSharedKey) {
        val key = activeSharedKey ?: return@LaunchedEffect
        delay((Motion.EXPAND + Motion.QUICK).toLong())
        sharedMediaController.finish(key)
    }
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalSharedMediaTransitionController provides sharedMediaController,
        ) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = onBack,
                popTransitionSpec = {
                    noBackTransition()
                },
                predictivePopTransitionSpec = {
                    noBackTransition()
                },
                entryProvider = { key ->
                    NavEntry(
                        key = key,
                        contentKey = contentKey(key),
                    ) { entryKey ->
                        CompositionLocalProvider(
                            LocalRouteVisible provides
                                (parentRouteVisible && entryKey == currentTop),
                        ) {
                            currentContent(entryKey)
                        }
                    }
                },
            )
        }
    }
}

private fun noBackTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
    )
