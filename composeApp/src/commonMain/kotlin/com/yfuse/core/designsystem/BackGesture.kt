package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Edge from which the system back gesture started. */
enum class BackGestureEdge { Left, Right }

/**
 * Minimal state shared between AndroidX PredictiveBackHandler and the route host.
 *
 * There is intentionally no Animatable, scale, corner morph, scrim, spring or synthetic
 * completion animation here. AndroidX owns gesture timing; the app only mirrors progress so
 * the current route can move with the finger and expose the real previous route underneath.
 */
@Stable
class BackGestureState {
    var progress by mutableStateOf(0f)
        private set
    var edge by mutableStateOf(BackGestureEdge.Left)
        private set
    var active by mutableStateOf(false)
        private set

    private var committedGesture = false

    fun update(progress: Float, edge: BackGestureEdge) {
        this.progress = progress.coerceIn(0f, 1f)
        this.edge = edge
        active = true
        committedGesture = false
    }

    fun cancel() {
        progress = 0f
        active = false
        committedGesture = false
    }

    /** Marks only a gesture that actually produced progress, not a hardware/back-button pop. */
    fun commit() {
        committedGesture = active
        progress = 0f
        active = false
    }

    /** Consumed by the next route change so a completed swipe is not animated a second time. */
    internal fun consumeCommittedGesture(): Boolean {
        val result = committedGesture
        committedGesture = false
        return result
    }
}

@Composable
fun rememberBackGestureState(): BackGestureState = remember { BackGestureState() }

/**
 * A local full-screen layer that follows AndroidX back progress directly.
 *
 * Used only for UI layers that are intentionally not navigation-stack routes (for example
 * 查看全部 over an already composed page). It translates the layer and nothing else: no
 * scaling, fading, corner morph, scrim or synthetic completion animation.
 */
@Composable
fun SystemBackGestureSurface(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberBackGestureState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    PlatformBackGestureHandler(
        onProgress = state::update,
        onCancel = state::cancel,
        onBack = {
            state.commit()
            onBack()
        },
    )
    val gestureModifier = if (reduceMotion) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            val direction = if (state.edge == BackGestureEdge.Right) -1f else 1f
            translationX = direction * size.width * state.progress.coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize().then(gestureModifier)) {
        content()
    }
}

val LocalBackGesture = staticCompositionLocalOf<BackGestureState?> { null }
