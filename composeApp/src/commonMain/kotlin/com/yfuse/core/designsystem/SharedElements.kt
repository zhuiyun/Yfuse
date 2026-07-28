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
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> SharedElementTransitionContainer(
    targetState: T,
    content: @Composable (T) -> Unit,
) {
    val accessibility = LocalAccessibilityOptions.current
    val duration = if (accessibility.reduceMotion) 0 else Motion.PUSH

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
            label = "shared-media-route",
        ) animatedContent@{ child ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@sharedTransition,
                LocalSharedAnimatedVisibilityScope provides this@animatedContent,
            ) {
                content(child)
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
