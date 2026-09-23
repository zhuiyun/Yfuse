package com.yfuse.core.designsystem

import androidx.compose.runtime.compositionLocalOf

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
