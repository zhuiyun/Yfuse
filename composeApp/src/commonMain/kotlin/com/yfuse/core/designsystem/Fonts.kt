package com.yfuse.core.designsystem

import androidx.compose.ui.text.font.FontFamily

/**
 * Manrope — the Latin/numeral face 设计说明文档 §8.3 pairs with Noto Sans SC.
 *
 * It was never actually bundled: [mr] resolved to [FontFamily.Default], so every year,
 * runtime, item count, bitrate and badge in the app rendered in the platform's UI face and
 * the four-level type scale's numeric tier existed only on paper. Manrope's figures are
 * the reason the spec picked it — they are wider-set and tabular-looking at caption sizes,
 * which is where nearly all of this app's numbers live.
 *
 * Chinese text is deliberately not bundled: the spec's Noto Sans SC is what Android
 * already resolves [FontFamily.Default] to for CJK glyphs, and shipping a copy would add
 * megabytes to reproduce what the platform provides.
 */
expect val NumericFontFamily: FontFamily
