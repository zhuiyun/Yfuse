package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

internal data class CinemaSelectionBounds(
    val key: Any,
    val bounds: Rect,
)

internal fun cinemaSelectionMoves(
    previous: CinemaSelectionBounds?,
    next: CinemaSelectionBounds?,
): Boolean = previous != null && next != null && previous.key != next.key

private class CinemaSelectionAnchor {
    var parent: LayoutCoordinates? = null
        set(value) {
            field = value
            selectedKey?.let { place(it, selectedCoordinates) }
        }
    var target by mutableStateOf<CinemaSelectionBounds?>(null)
    private var selectedKey: Any? = null
    private var selectedCoordinates: LayoutCoordinates? = null

    fun place(
        key: Any,
        coordinates: LayoutCoordinates?,
    ) {
        selectedKey = key
        selectedCoordinates = coordinates
        val root = parent ?: return
        if (!root.isAttached || coordinates?.isAttached != true) return
        target = CinemaSelectionBounds(key, root.localBoundingBoxOf(coordinates, clipBounds = false))
    }

    fun clear(key: Any) {
        if (selectedKey == key) {
            selectedKey = null
            selectedCoordinates = null
            target = null
        }
    }
}

private val LocalCinemaSelection = staticCompositionLocalOf<CinemaSelectionAnchor?> { null }

/** One plate follows the authoritative selection; scrolling the same item never trails the row. */
@Composable
internal fun CinemaSelectionGroup(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val moving = cinemaMotion()
    val anchor = remember { CinemaSelectionAnchor() }
    val position = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var previous by remember { mutableStateOf<CinemaSelectionBounds?>(null) }
    val target = anchor.target
    LaunchedEffect(target, moving) {
        val old = previous
        previous = target
        if (target != null) {
            if (moving && cinemaSelectionMoves(old, target)) {
                position.animateTo(target.bounds, Motion.settle())
            } else {
                position.snapTo(target.bounds)
            }
        }
    }
    Box(
        modifier
            .onGloballyPositioned {
                anchor.parent = it
            }.then(if (moving) Modifier.clipToBounds() else Modifier)
            .drawWithContent {
                drawContent()
                if (moving && target != null && position.value != Rect.Zero) {
                    val rect = position.value
                    drawRoundRect(color.copy(alpha = 0.07f), rect.topLeft, rect.size, CornerRadius(10.dp.toPx()))
                    drawRoundRect(
                        color.copy(alpha = 0.45f),
                        rect.topLeft,
                        rect.size,
                        CornerRadius(10.dp.toPx()),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            },
    ) {
        CompositionLocalProvider(LocalCinemaSelection provides anchor) { content() }
    }
}

@Composable
internal fun Modifier.cinemaSelectionTarget(
    key: Any,
    selected: Boolean,
): Modifier {
    val anchor = LocalCinemaSelection.current ?: return this
    val holder = remember(key) { arrayOfNulls<LayoutCoordinates>(1) }
    SideEffect {
        if (selected) {
            anchor.place(key, holder[0])
        } else {
            anchor.clear(key)
        }
    }
    DisposableEffect(anchor, key) {
        onDispose { anchor.clear(key) }
    }
    return onGloballyPositioned {
        holder[0] = it
        if (selected) anchor.place(key, it)
    }
}
