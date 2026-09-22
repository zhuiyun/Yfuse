package com.yfuse.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The binary and pick-one controls, in one place.
 *
 * These grew up inside 设置 and inside the player panel, which is why the same switch was
 * drawn twice and the same segmented pill three times. They are form controls, not screen
 * parts: a switch row belongs beside [YfFormField] and [YfButton], and the light and dark
 * segmented pills belong beside each other so they cannot drift apart again.
 *
 * One switch and its label. [embedded] drops the glass plate for rows stacked inside a card.
 * The whole row is the target — a 28dp knob is not something to aim at — so the row owns the
 * gesture and the semantics, and the switch itself draws without an interaction of its own.
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    embedded: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (embedded) it else it.flatGlass(AppShapes.control, palette.card2, palette.border) }
            // The row was the one gesture in the app on a bare `toggleable(indication = null)`:
            // no press scale, no focus ring, no haptic. It answers like every other control now,
            // and carries the on/off state the toggle used to publish on its own.
            .pressable(
                role = Role.Switch,
                haptic = HapticSignal.Select,
                onClick = { onChange(!checked) },
            ).semantics { toggleableState = ToggleableState(checked) }
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingIconTile(icon, iconTint)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.body.medium,
                color = palette.text,
                maxLines = 2,
            )
            description?.takeIf(String::isNotBlank)?.let { copy ->
                Spacer(Modifier.height(3.dp))
                // Helper copy that depends on the switch ("已开启…" / "关闭后…") crossfades with it
                // instead of being swapped in the frame the thumb starts moving.
                val copyFade = if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.QUICK
                AnimatedContent(
                    targetState = copy,
                    transitionSpec = {
                        fadeIn(tween(copyFade, easing = Motion.Curve)) togetherWith
                            fadeOut(tween(copyFade, easing = Motion.Curve))
                    },
                    label = "switch-row-description",
                ) { text ->
                    Text(
                        text,
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                        maxLines = 3,
                    )
                }
            }
        }
        PillSwitch(checked)
    }
}

/**
 * The pick-one pill used on light settings surfaces. [expanded] spreads it to the full width
 * for a control that owns its own row rather than sitting at the end of a label.
 */
@Composable
internal fun SettingSegmentControl(
    options: List<String>,
    selectedIndex: Int,
    expanded: Boolean,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val indicator = rememberSegmentIndicator(selectedIndex, palette.card2, accent.border)
    Row(
        Modifier
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .selectableGroup()
            .flatGlass(GlassShapes.chip, palette.card3, palette.border)
            .padding(2.dp)
            .then(indicator.container),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Box(
                Modifier
                    .then(if (expanded) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 30.dp)
                    .then(indicator.item(index))
                    .pressable(
                        pressedScale = 0.97f,
                        haptic = HapticSignal.Select,
                        role = Role.RadioButton,
                        focusShape = GlassShapes.chip,
                        onClickLabel = label,
                        onClick = { onSelect(index) },
                    ).semantics { selected = isSelected }
                    .padding(horizontal = if (expanded) 6.dp else 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (isSelected) AppTypography.caption.strong else AppTypography.caption.medium,
                    color = selectionColor(if (isSelected) accent.accent else palette.sub2),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The pick-one pill as it reads over film: always-dark accents, one always-full-width row.
 *
 * The player draws on top of the picture rather than on a page, so it resolves its accent
 * against a dark surface instead of the live palette.
 */
@Composable
internal fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val indicator = rememberSegmentIndicator(selectedIndex, accent.container, accent.border)
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .glass(
                shape = AppShapes.pill,
                fill = Color.White.copy(alpha = 0.07f),
                border = Color.White.copy(alpha = 0.12f),
            ).padding(3.dp)
            .then(indicator.container),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Text(
                label,
                style = if (active) AppTypography.caption.strong else AppTypography.caption.medium,
                color = selectionColor(if (active) accent.accent else Color.White.copy(alpha = 0.62f)),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .then(indicator.item(index))
                        .pressable(
                            haptic = HapticSignal.Select,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ).semantics { selected = active }
                        .touchTarget()
                        .padding(vertical = 9.dp),
            )
        }
    }
}

/** The switch itself: knob and track only. Its row owns the gesture and the semantics. */
@Composable
internal fun PillSwitch(checked: Boolean) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "switchKnob",
    )
    val track by animateColorAsState(
        targetValue = if (checked) accent.accent else palette.sub2.copy(alpha = if (palette.isDark) 0.30f else 0.28f),
        animationSpec = Motion.settle<Color>(reduceMotion),
        label = "switchTrack",
    )
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .lightOnChange(
                checked,
                if (checked) LightEffect.Converge else LightEffect.Node,
                fractionX = if (checked) 0.7f else 0.3f,
            ).clip(AppShapes.pill)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                // Placed rather than composed: the knob moves every frame of the spring, and
                // the dp overload would recompose the row for each of them.
                .offset { IntOffset((SwitchTravel.toPx() * progress).roundToInt(), 0) }
                .size(22.dp)
                .shadow(GlassLift.control, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private val SwitchTravel = 18.dp

/**
 * The continuous control, in the glass language.
 *
 * Material's `Slider` was the last stock control left on a settings page — M3 colours, M3
 * ripple and an M3 knob in the middle of a glass app. Track, fill and knob here are the
 * switch's own materials, and the knob rides the same [Motion.settle] spring rather than
 * chasing the finger on a tween, so an interrupted drag keeps its velocity.
 *
 * [steps] is Material's count: the number of stops *between* the two ends. Crossing one is a
 * selection, so it answers like one; a continuous slider has nothing to announce and stays
 * silent.
 */
@Composable
internal fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val haptics = LocalHaptics.current
    val light = rememberLightFeedback()
    val currentLight by rememberUpdatedState(light)
    val currentFraction by rememberUpdatedState(value)
    val span = (valueRange.endInclusive - valueRange.start).let { if (it > 0f) it else 1f }
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = Motion.settle<Float>(reduceMotion),
        label = "sliderKnob",
    )
    val track = palette.sub2.copy(alpha = if (palette.isDark) 0.30f else 0.28f)
    val latestChange by rememberUpdatedState(onValueChange)
    // Which stop the value last answered on, so one crossing is one tick rather than one per
    // frame of the drag that crosses it.
    var lastStep by remember { mutableIntStateOf(sliderStepIndex(fraction, steps)) }

    fun report(raw: Float) {
        val snapped = snapSliderFraction(raw.coerceIn(0f, 1f), steps)
        if (steps > 0) {
            val index = sliderStepIndex(snapped, steps)
            if (index != lastStep) {
                lastStep = index
                haptics.play(HapticSignal.Select)
            }
        }
        latestChange(valueRange.start + snapped * span)
        currentLight.emit(LightEffect.Trail, fractionX = snapped)
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .lightFeedback(light)
            .heightIn(min = MinTouchTarget)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
                setProgress { target ->
                    report((target - valueRange.start) / span)
                    true
                }
            }.pointerInput(steps, valueRange) {
                val thumb = SliderThumb.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { start -> report(sliderFractionAt(start.x, size.width, thumb)) },
                    onDragEnd = {
                        currentLight.emit(
                            LightEffect.Converge,
                            fractionX =
                                (
                                    (
                                        currentFraction -
                                            valueRange.start
                                    ) /
                                        span
                                ).coerceIn(0f, 1f),
                        )
                    },
                    onDragCancel = { currentLight.clear() },
                ) { change, _ ->
                    change.consume()
                    report(sliderFractionAt(change.position.x, size.width, thumb))
                }
            }.pointerInput(steps, valueRange) {
                val thumb = SliderThumb.toPx()
                detectTapGestures { tap -> report(sliderFractionAt(tap.x, size.width, thumb)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = maxWidth - SliderThumb
        Box(
            Modifier
                .fillMaxWidth()
                .height(SliderTrack)
                .clip(AppShapes.track)
                // Drawn rather than laid out, for the same reason the knob is placed rather
                // than composed: the fill changes with every frame of the spring.
                .drawBehind {
                    drawRect(track)
                    drawRect(accent.accent, size = Size(size.width * progress, size.height))
                },
        )
        Box(
            Modifier
                .offset { IntOffset((travel.toPx() * progress).roundToInt(), 0) }
                .size(SliderThumb)
                .shadow(GlassLift.control, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private val SliderThumb = 22.dp
private val SliderTrack = 5.dp

/** Where along the knob's travel [x] falls, with the knob's own width taken out of the span. */
private fun sliderFractionAt(
    x: Float,
    width: Int,
    thumbPx: Float,
): Float {
    val travel = (width - thumbPx).coerceAtLeast(1f)
    return ((x - thumbPx / 2f) / travel).coerceIn(0f, 1f)
}

/** The nearest of the [steps] + 2 stops, or the value untouched when the slider is continuous. */
private fun snapSliderFraction(
    fraction: Float,
    steps: Int,
): Float = if (steps <= 0) fraction else sliderStepIndex(fraction, steps) / (steps + 1).toFloat()

private fun sliderStepIndex(
    fraction: Float,
    steps: Int,
): Int = if (steps <= 0) 0 else (fraction * (steps + 1)).roundToInt()

/** The rounded gradient glyph plate a settings row wears in place of a bare icon. */
@Composable
internal fun SettingIconTile(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(AppShapes.thumb)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.16f), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}
