package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the current Navigation3 entry is the visible route.
 *
 * Navigation3 may retain neighboring entries while it animates or previews a back gesture.
 * Screens use this signal to pause focus requests, carousels, and other visible-only work.
 *
 * Dynamic, not static: this flips on every push and pop, and a static local answers a change
 * by recomposing the provider's whole content with skipping off — the entire outgoing and
 * incoming page, in the first frame of the transition. Only the readers need to hear it.
 */
val LocalRouteVisible = compositionLocalOf { true }

/**
 * [LocalRouteVisible] as a state holder whose identity never changes, for code that only needs
 * the answer in an effect or while drawing. Reading the Boolean in composition recomposes the
 * reader on every push and pop — and every pressable control read it, so the first frame of a
 * transition recomposed each button on both pages. Null outside a navigation host.
 */
internal val LocalRouteVisibilityState = staticCompositionLocalOf<State<Boolean>?> { null }

/**
 * The route's visibility, to read in effects and draw phases. Outside a navigation host — a
 * preview, a test — it falls back to the plain local, read in composition.
 */
@Composable
internal fun rememberRouteVisibility(): State<Boolean> =
    LocalRouteVisibilityState.current ?: rememberUpdatedState(LocalRouteVisible.current)
