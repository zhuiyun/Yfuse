// The `Font(resId, weight, style, variationSettings)` overload is @ExperimentalTextApi in
// Compose 1.7 — the variable-font path is the experimental part, not the resource one.
// Opting in per file rather than per call keeps the annotation next to the reason.
@file:OptIn(ExperimentalTextApi::class)

package com.yfuse.core.designsystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.yfuse.R

/**
 * The weights [mr] actually asks for, spelled out.
 *
 * One variable file serves all of them, so the alternative — a static TTF per weight —
 * would be several times the size for the same result. Registering each weight as its own
 * instance is what lets Compose pick an exact match instead of synthesising a bold, and
 * every value here sits inside Manrope's 200–800 `wght` axis.
 *
 * Variation settings apply from API 26, which is this app's `minSdk`.
 */
private val NumericWeights = listOf(400, 450, 500, 600, 650, 700, 800)

actual val NumericFontFamily: FontFamily =
    FontFamily(
        NumericWeights.map { weight ->
            Font(
                R.font.manrope_variable,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        },
    )
