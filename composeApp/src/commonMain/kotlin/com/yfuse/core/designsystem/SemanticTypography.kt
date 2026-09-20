package com.yfuse.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Fixed weights inside one semantic type role. Feature code should not invent new sizes. */
@Immutable
data class TextRoleStyles(
    val regular: TextStyle,
    val medium: TextStyle,
    val strong: TextStyle,
    /**
     * Long-form running copy — a synopsis, a note, a description spanning three or more
     * lines — where the role's default ~1.35× line height reads cramped. Falls back to
     * [regular] for roles that never carry paragraph-length text (display, section).
     */
    val reading: TextStyle = regular,
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
            // Median of the 10 `body.copy(lineHeight = …sp)` scatter points this replaces
            // (20/20.6/21/21.5sp across home/servers/watch/detail/designsystem call sites).
            reading = sc(13f, 400).copy(lineHeight = 20.8.sp),
        )

    val caption =
        TextRoleStyles(
            regular = mr(11f, 400),
            medium = mr(11f, 500),
            strong = mr(11f, 700),
            // Median of the 9 `caption.copy(lineHeight = …sp)` scatter points this replaces
            // (16.5–19.5sp across watch/servers/search/profile/detail call sites).
            reading = mr(11f, 400).copy(lineHeight = 17.sp),
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
