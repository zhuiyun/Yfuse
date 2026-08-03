package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

private val LocalSharedAnimatedVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Keeps the outgoing and incoming Decompose children composed long enough for
 * shared artwork to travel between list, hero and detail layouts.
 *
 * [routeKey] must return a stable value per route (not per instance). It does two jobs:
 * it tells [AnimatedContent] when the content genuinely changed, and it scopes a
 * [rememberSaveableStateHolder] entry so each route keeps its `rememberSaveable` state
 * while it sits in the back stack. Without that holder every route is rebuilt from
 * scratch on the way back — `rememberLazyListState` is a `rememberSaveable`, so a list
 * scrolled halfway down snapped back to the top as soon as the user opened a detail
 * page and returned.
 *
 * Routes that can stack on themselves (detail → related detail) share one key and
 * therefore one saved scroll offset. Giving those a per-item key would keep a registry
 * entry alive for every item ever visited, which costs more than the wart is worth.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> SharedElementTransitionContainer(
    targetState: T,
    routeKey: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    val accessibility = LocalAccessibilityOptions.current
    val duration = if (accessibility.reduceMotion) 0 else Motion.PUSH
    val stateHolder = rememberSaveableStateHolder()

    SharedTransitionLayout sharedTransition@{
        AnimatedContent(
            targetState = targetState,
            transitionSpec = {
                (
                    fadeIn(tween(duration)) +
                        scaleIn(tween(duration), initialScale = 0.985f)
                    ) togetherWith (
                    fadeOut(tween(duration)) +
                        scaleOut(tween(duration), targetScale = 1.01f)
                    )
            },
            contentKey = routeKey,
            label = "shared-media-route",
        ) animatedContent@{ child ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@sharedTransition,
                LocalSharedAnimatedVisibilityScope provides this@animatedContent,
            ) {
                stateHolder.SaveableStateProvider(routeKey(child)) {
                    content(child)
                }
            }
        }
    }
}

/** Links matching media artwork across a [SharedElementTransitionContainer]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMediaElement(key: String?): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalSharedAnimatedVisibilityScope.current
    if (key == null || sharedTransitionScope == null || animatedVisibilityScope == null) return this

    return with(sharedTransitionScope) {
        this@sharedMediaElement.sharedElement(
            state = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}

/**
 * Keeps detail-page chrome above artwork that is temporarily rendered in the shared
 * transition overlay. Without this, the travelling image is drawn after the whole page
 * and briefly covers titles and actions until the route animation finishes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedMediaForeground(zIndex: Float = 1f): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current ?: return this
    return with(sharedTransitionScope) {
        this@sharedMediaForeground.renderInSharedTransitionScopeOverlay(
            zIndexInOverlay = zIndex,
        )
    }
}
