package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/*
 * Settings-page primitives, promoted out of 设置 so 我的/服务器/Trakt/设备接力 can share one
 * implementation instead of drifting copies. [SettingRow] absorbs what used to be a second,
 * account-page-only row (`AccountActionRow`): pass [supporting] for its stacked
 * title-plus-description reading with a trailing label, chevron and [loading] spinner; leave
 * it null for the original title-left/value-trailing reading.
 */

/** The card a settings section's rows sit in. */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .flatGlass(AppShapes.card, palette.card2, palette.border),
        content = content,
    )
}

/** A titled group of settings rows, with an optional trailing text action. */
@Composable
fun Section(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = AppTypography.body.strong.copy(letterSpacing = 0.5.sp),
                color = palette.sub2,
                modifier = Modifier.semantics { heading() },
            )
            if (action != null) {
                Text(
                    action,
                    style = AppTypography.caption.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .pressable(onClick = onAction)
                            .touchTarget()
                            .liquidGlass(
                                shape = AppShapes.chip,
                                fill = palette.card2,
                                border = palette.border,
                                over = palette.background,
                                sheen = 0.52f,
                            ).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        content()
    }
}

/** The hairline between two rows inside the same [SettingsCard]. */
@Composable
fun SettingsDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(
            palette.border.copy(alpha = if (palette.isDark) 0.24f else 0.48f),
        ),
    )
}

/**
 * One settings row, in either of two readings.
 *
 * The default reading is title on the left, [value] trailing — the one every 设置 page uses.
 * Passing [supporting] switches to the account-page reading: title above a one-line
 * description, with an optional [trailingLabel] and a [loading] spinner that can replace the
 * chevron while a request is in flight. Both readings draw their own chevron when [showChevron]
 * is true (by default, whenever the row is clickable) — callers no longer bake a "›" glyph into
 * [value] or [supporting] themselves.
 *
 * The title/value pair used to measure its own width with `BoxWithConstraints`, subcomposing on
 * every constraint change to decide whether the value fits beside the title or has to drop below
 * it. [SettingRowTitleValue] gets the same adaptive read from one plain, non-subcomposing
 * [Layout] pass instead.
 */
@Composable
fun SettingRow(
    title: String,
    value: String = "",
    embedded: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    supporting: String? = null,
    trailingLabel: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    showChevron: Boolean = onClick != null,
    dense: Boolean = supporting != null,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val alpha = if (enabled) 1f else 0.46f
    val titleColor = (if (destructive) palette.error else palette.text).copy(alpha = alpha)
    val chevronColor = (if (destructive) palette.error else palette.sub2).copy(alpha = alpha)
    // The account-page reading (title + one-line description + trailing label) never wore its
    // own glass plate or the wider settings-row padding — it always lived inside a card that
    // already drew one. [dense] defaults from [supporting] because every such call already
    // passes one, but a title-only account row (no description, e.g. "退出账号") still needs to
    // opt in explicitly rather than fall back to the title/value row's own spacing.
    val compact = dense

    val rowModifier =
        Modifier
            .fillMaxWidth()
            .let { if (embedded) it else it.flatGlass(AppShapes.control, palette.card2, palette.border) }
            .let {
                if (onClick != null) {
                    // A full-width row scaled to 0.97 read as its words sliding towards the middle;
                    // rows answer with a barely-there scale and their own ink instead.
                    it.pressable(
                        enabled = enabled && !loading,
                        pressedScale = PressFeedback.QUIET,
                        tintOnPress = true,
                        focusShape = if (embedded) AppShapes.chip else AppShapes.control,
                        onClickLabel = title,
                        onClick = onClick,
                    )
                } else {
                    it
                }
            }.heightIn(min = if (compact) 54.dp else MinTouchTarget)
            .touchTarget()
            .padding(
                horizontal = if (compact) 2.dp else 16.dp,
                vertical = if (compact) 7.dp else 13.dp,
            )

    Row(
        rowModifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingIconTile(icon, iconTint)
        if (compact) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = AppTypography.body.strong,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it,
                        style = AppTypography.caption.regular,
                        color = palette.sub2.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            InlineLoadingContent(loading = loading, slotSize = 17.dp, orbSize = 16.dp, color = chevronColor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trailingLabel?.let {
                        Text(
                            text = it,
                            style = AppTypography.caption.strong,
                            color = if (destructive) palette.error else accent.accent,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (showChevron) {
                        Icon(
                            imageVector = AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = chevronColor,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        } else {
            SettingRowTitleValue(
                title = title,
                value = value,
                titleColor = titleColor,
                valueColor = palette.sub2.copy(alpha = alpha),
                modifier = Modifier.weight(1f),
            )
            if (showChevron) {
                InlineLoadingContent(loading = loading, slotSize = 16.dp, color = chevronColor) {
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = chevronColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The title-left/value-trailing half of [SettingRow], as one [Layout] pass.
 *
 * Below 600dp of its own width — effectively every phone — or under large text, the value drops
 * below the title instead of squeezing beside it; [windowWidthTier] and `largeText` are the same
 * two signals the old `BoxWithConstraints` block read, just consulted inside a measure pass that
 * composes its two [Text] children once instead of subcomposing them per constraint change.
 */
@Composable
private fun SettingRowTitleValue(
    title: String,
    value: String,
    titleColor: Color,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Layout(
        modifier = modifier,
        content = {
            Text(
                title,
                style = AppTypography.body.medium,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = AppTypography.body.regular,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        },
    ) { measurables, constraints ->
        val (titleMeasurable, valueMeasurable) = measurables
        val stacked = largeText || windowWidthTier(constraints.maxWidth.toDp()) == WindowWidthTier.Compact
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (stacked) {
            val gap = 4.dp.roundToPx()
            val titlePlaceable = titleMeasurable.measure(loose)
            val valuePlaceable = valueMeasurable.measure(loose)
            val height = titlePlaceable.height + gap + valuePlaceable.height
            layout(width, height) {
                titlePlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(0, titlePlaceable.height + gap)
            }
        } else {
            val gap = 12.dp.roundToPx()
            val valuePlaceable = valueMeasurable.measure(loose)
            val titleMaxWidth = (width - valuePlaceable.width - gap).coerceAtLeast(0)
            val titlePlaceable = titleMeasurable.measure(loose.copy(maxWidth = titleMaxWidth))
            val height = maxOf(titlePlaceable.height, valuePlaceable.height)
            layout(width, height) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        }
    }
}
