package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalMutedGlass
import com.yfuse.core.designsystem.mutedGlassPanel
import com.yfuse.core.designsystem.rememberOverlayTransition
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformPredictiveBackHandler
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.shadow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Long-form typed/searchable panels keep a stable right-edge drawer geometry. */
internal val PlayerPanelWidth = 340.dp

/** Compact floating settings popover. Search, chat, and episode lists keep their drawers. */
internal val PlayerPopupWidth = 320.dp

private val PlayerPopupCompactMinHeight = 124.dp

private val PlayerPopupCompactMaxHeight = 190.dp

private val PlayerPopupMinHeight = 176.dp

private val PlayerPopupMaxHeight = 308.dp

/** Rounded on the leading edge only; the panel is attached to the screen's right edge. */
internal val PlayerPanelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

private val PlayerPanelBorder = Color.White.copy(alpha = 0.24f)

/**
 * The shared shell: a dismiss catcher over the picture, and the drawer itself.
 *
 * [dim] is the one deliberate difference between them. A list of choices about the picture
 * should not dim the picture it is describing, while a panel being *typed* into — 搜索弹幕,
 * 房间聊天 — has taken the screen over and says so.
 */
@Composable
internal fun PlayerSidePanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val initialWidthPx = with(LocalDensity.current) { PlayerPanelWidth.toPx() }
    var widthPx by remember { mutableFloatStateOf(initialWidthPx) }
    var offsetPx by remember { mutableFloatStateOf(if (reduceMotion) 0f else initialWidthPx) }
    var directlyManipulating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun settleDrawer(dismiss: Boolean) {
        if (reduceMotion) {
            offsetPx = if (dismiss) widthPx else 0f
        } else {
            Animatable(offsetPx).animateTo(
                targetValue = if (dismiss) widthPx else 0f,
                animationSpec = Motion.drawer(),
            ) {
                offsetPx = value
            }
        }
        if (dismiss) onDismiss()
    }

    LaunchedEffect(reduceMotion) {
        if (!directlyManipulating) settleDrawer(dismiss = false)
    }

    PlatformPredictiveBackHandler(
        onProgress = { progress ->
            directlyManipulating = true
            offsetPx = widthPx * predictiveDrawerProgress(progress)
        },
        onBack = {
            directlyManipulating = false
            scope.launch { settleDrawer(dismiss = true) }
        },
        onCancel = {
            directlyManipulating = false
            scope.launch { settleDrawer(dismiss = false) }
        },
    )

    val openFraction = (1f - offsetPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (dim) {
                    Modifier.background(Color.Black.copy(alpha = 0.4f * openFraction))
                } else {
                    Modifier
                },
            ).noRippleClickable { scope.launch { settleDrawer(dismiss = true) } },
    )
    Column(
        modifier
            .fillMaxHeight()
            .width(PlayerPanelWidth)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationX = offsetPx
                alpha = openFraction
                scaleY = if (reduceMotion) 1f else 0.98f + 0.02f * openFraction
            }
            .draggable(
                state =
                    rememberDraggableState { delta ->
                        directlyManipulating = true
                        offsetPx = drawerDragOffset(offsetPx, delta, widthPx)
                    },
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val dismiss = drawerShouldDismiss(offsetPx, widthPx, velocity)
                    directlyManipulating = false
                    settleDrawer(dismiss)
                },
            ).shadow(Shadows.playerSheet, PlayerPanelShape)
            .mutedGlassPanel(PlayerPanelShape, samplePage = false, dark = true)
            // Taps inside the panel must not reach the catcher behind it.
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = verticalArrangement,
        content = { CompositionLocalProvider(LocalMutedGlass provides true) { content() } },
    )
}

internal fun drawerDragOffset(
    currentOffsetPx: Float,
    deltaPx: Float,
    widthPx: Float,
): Float {
    val candidate = currentOffsetPx + deltaPx
    return if (candidate < 0f) {
        candidate * DRAWER_WRONG_WAY_RESISTANCE
    } else {
        candidate.coerceAtMost(widthPx.coerceAtLeast(1f) * DRAWER_MAX_TRAVEL)
    }
}

internal fun drawerShouldDismiss(
    offsetPx: Float,
    widthPx: Float,
    velocityPxPerSecond: Float,
): Boolean =
    velocityPxPerSecond > DRAWER_DISMISS_VELOCITY ||
        (
            abs(velocityPxPerSecond) < DRAWER_DISMISS_VELOCITY &&
                offsetPx > widthPx * DRAWER_DISMISS_FRACTION
        )

internal fun predictiveDrawerProgress(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return 1f - (1f - clamped) * (1f - clamped)
}

private const val DRAWER_WRONG_WAY_RESISTANCE = 0.18f
private const val DRAWER_MAX_TRAVEL = 1.08f
private const val DRAWER_DISMISS_FRACTION = 0.42f
private const val DRAWER_DISMISS_VELOCITY = 850f

/**
 * The function-menu shell used by playback, tracks, picture, danmaku, cast, and advanced.
 *
 * It deliberately does not fill the right edge. These are short, reversible choices, so a
 * compact floating surface keeps the picture readable and makes the relationship to the
 * bottom controls clearer. Long-form search/chat panels continue to use [PlayerSidePanel].
 */
@Composable
internal fun PlayerPopupPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var leaving by remember { mutableStateOf(false) }
    val progress = rememberOverlayTransition(leaving, onDismiss)
    val requestDismiss = remember { { leaving = true } }
    PlatformPredictiveBackHandler(
        onProgress = { },
        onBack = requestDismiss,
        onCancel = { },
    )
    Box(
        Modifier
            .fillMaxSize()
            .noRippleClickable(requestDismiss),
    )
    Column(
        modifier
            .width(PlayerPopupWidth)
            .graphicsLayer {
                val entered = progress()
                alpha = entered.coerceIn(0f, 1f)
                scaleX = 0.94f + entered * 0.06f
                scaleY = scaleX
                translationY = 12.dp.toPx() * (1f - entered)
            }
            .heightIn(
                min = if (compact) PlayerPopupCompactMinHeight else PlayerPopupMinHeight,
                max = if (compact) PlayerPopupCompactMaxHeight else PlayerPopupMaxHeight,
            ).shadow(Shadows.playerSheet, AppShapes.sheet)
            .mutedGlassPanel(AppShapes.sheet, samplePage = false, dark = true)
            // Taps inside the popup must not reach the dismiss catcher behind it.
            .noRippleClickable { }
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Top,
        content = { CompositionLocalProvider(LocalMutedGlass provides true) { content() } },
    )
}
