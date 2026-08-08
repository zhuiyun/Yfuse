package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two things a page shows instead of content: a message it can do nothing about, and
 * a failure it can retry.
 *
 * Five screens had written the failure state out by hand — 首页, 媒体库, 媒体库网格, 搜索
 * and 影视详情页 — and no two agreed. The label ran at 13sp in three of them and 12.5sp in
 * search; 首页's sat in a stock `TextButton` with no colour at all, so it fell through to
 * Material's default primary and ignored the palette entirely. The retry chip is also the
 * place the invisible-hairline problem showed up: `palette.border` is a 70% white on a
 * light theme whose detail pages are white, so the button's edge simply was not there.
 * Carrying the accent on both fill and border fixes that on either theme.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            style = sc(13f, 400, lineHeight = 13f * 1.6f),
            color = LocalPalette.current.sub,
            textAlign = TextAlign.Center,
        )
        AccentChipButton(label = retryLabel, onClick = onRetry)
    }
}

/**
 * Centred note with nothing to act on — no server configured, no results, empty library.
 *
 * [actionLabel] turns it into somewhere to go. An empty 我的收藏 or 稍后观看 is the state a
 * new user is in most often, and without the chip those pages are a dead end: the text
 * names what is missing and offers no way to fix it.
 */
@Composable
fun PageHint(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text,
            style = sc(13f, 400, lineHeight = 13f * 1.6f),
            color = LocalPalette.current.sub,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            AccentChipButton(label = actionLabel, onClick = onAction)
        }
    }
}

/** The accent-tinted chip both page states use to offer their one action. */
@Composable
private fun AccentChipButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = sc(13f, 700),
        color = Brand.Primary,
        modifier = Modifier
            .pressable(onClick = onClick)
            .solidGlass(
                shape = GlassShapes.chip,
                fill = Brand.Primary.copy(alpha = 0.08f),
                border = Brand.Primary.copy(alpha = 0.28f),
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * Fill for loading placeholders, matched to the palette.
 *
 * A skeleton is only worth drawing if it is quieter than the content it stands in for;
 * these two values are the ones 媒体库's rail skeleton already used.
 */
@Composable
fun skeletonFill(): Color =
    if (LocalPalette.current.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)

/** One rounded placeholder block. Sized by the caller so it matches what it replaces. */
@Composable
fun SkeletonBlock(modifier: Modifier, radius: Dp = 6.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(skeletonFill()))
}

/**
 * A poster tile placeholder: artwork, title line, caption line.
 *
 * Shared so the grid, the rails and the detail page all reserve the same shapes — a
 * spinner tells the user only that something is happening, while these hold the layout
 * still so nothing jumps when the real posters land.
 */
@Composable
fun SkeletonPosterTile(modifier: Modifier = Modifier, posterHeight: Dp = 150.dp) {
    Column(modifier) {
        SkeletonBlock(
            Modifier.fillMaxWidth().height(posterHeight),
            radius = Dimens.medium,
        )
        Spacer(Modifier.height(7.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(12.dp), radius = 4.dp)
        Spacer(Modifier.height(5.dp))
        SkeletonBlock(Modifier.width(42.dp).height(9.dp), radius = 4.dp)
    }
}

/** A shelf placeholder: heading, then a row of poster tiles. */
@Composable
fun SkeletonRail(
    modifier: Modifier = Modifier,
    posterWidth: Dp = 104.dp,
    posterHeight: Dp = 150.dp,
    count: Int = 3,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(Modifier.width(90.dp).height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(count) {
                SkeletonPosterTile(Modifier.width(posterWidth), posterHeight = posterHeight)
            }
        }
    }
}
