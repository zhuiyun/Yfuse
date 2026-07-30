package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
    val palette = LocalPalette.current
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            style = sc(13f, 400, lineHeight = 13f * 1.6f),
            color = palette.sub,
            textAlign = TextAlign.Center,
        )
        Text(
            retryLabel,
            style = sc(13f, 700),
            color = Brand.Primary,
            modifier = Modifier
                .pressable(onClick = onRetry)
                .solidGlass(
                    shape = GlassShapes.chip,
                    fill = Brand.Primary.copy(alpha = 0.08f),
                    border = Brand.Primary.copy(alpha = 0.28f),
                )
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Centred note with nothing to act on — no server configured, no results, empty library. */
@Composable
fun PageHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = sc(13f, 400, lineHeight = 13f * 1.6f),
        color = LocalPalette.current.sub,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(24.dp),
    )
}
