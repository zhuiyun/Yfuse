package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/** Android system-back bridge used by the shared Compose navigation shell. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * The back *gesture*, reported while the finger is still down.
 *
 * [PlatformBackHandler] only ever says "back happened", which is why going back in this app
 * was the one navigation with no feel to it: the page was simply replaced, and there was no
 * way to start the gesture, see what it would do, and change your mind. That is the half of
 * iOS's edge swipe that matters — it is not a shortcut for the back button, it is a way to
 * *peek*.
 *
 * Rather than hand-rolling an edge detector, this rides the platform's own predictive back.
 * The OS owns the edge region, the gesture arbitration against horizontally scrolling
 * content, the left/right edge distinction and the fling thresholds — all of which a custom
 * detector gets subtly wrong, and all of which are muscle memory the user already has.
 *
 * @param onProgress 0f at the start of the gesture, 1f at the point it would commit.
 * @param onCancel the gesture was abandoned; return to rest.
 * @param onBack the gesture (or an ordinary back press) committed.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
)
