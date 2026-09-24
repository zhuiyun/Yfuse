package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Whether a screen reader is driving the device (TalkBack's explore-by-touch).
 *
 * Content that moves or hides on its own — the hero reel, player controls on a timer — pulls
 * the page out from under a spoken cursor, and a touch-exploring finger never sends the press
 * that pauses those surfaces for everyone else. They hold still while this is true.
 */
@Composable
expect fun rememberScreenReaderActive(): Boolean
