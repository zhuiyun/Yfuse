package com.yfuse.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle

/** Fixed weights inside one semantic type role. Feature code should not invent new sizes. */
@Immutable
data class TextRoleStyles(
    val regular: TextStyle,
    val medium: TextStyle,
    val strong: TextStyle,
)

/**
 * The app's four-level type system.
 *
 * Use `AppTypography.body.medium`, for example, instead of passing a fresh number to [sc]
 * or [mr]. The older functions remain available while feature code migrates incrementally.
 */
object AppTypography {
    val display =
        TextRoleStyles(
            regular = sc(26f, 400),
            medium = sc(26f, 600),
            strong = sc(26f, 800),
        )

    val section =
        TextRoleStyles(
            regular = sc(18f, 400),
            medium = sc(18f, 600),
            strong = sc(18f, 700),
        )

    val body =
        TextRoleStyles(
            regular = sc(13f, 400),
            medium = sc(13f, 500),
            strong = sc(13f, 600),
        )

    val caption =
        TextRoleStyles(
            regular = mr(11f, 400),
            medium = mr(11f, 500),
            strong = mr(11f, 700),
        )

    /** Material components inherit the same four roles instead of a second default scale. */
    val material =
        Typography(
            displayLarge = display.strong,
            displayMedium = display.medium,
            displaySmall = display.regular,
            headlineLarge = display.strong,
            headlineMedium = display.medium,
            headlineSmall = display.regular,
            titleLarge = section.strong,
            titleMedium = section.medium,
            titleSmall = section.regular,
            bodyLarge = body.regular,
            bodyMedium = body.regular,
            bodySmall = caption.regular,
            labelLarge = body.strong,
            labelMedium = caption.medium,
            labelSmall = caption.regular,
        )
}
