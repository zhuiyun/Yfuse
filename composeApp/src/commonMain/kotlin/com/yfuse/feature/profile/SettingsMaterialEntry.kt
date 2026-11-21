package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropRefraction
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.glassStrata
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.rememberBackdropState

@Composable
internal fun SettingsMaterialEntry(
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val source = rememberBackdropState()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .heightIn(min = 94.dp)
            .clip(AppShapes.card)
            .pressableTarget(AppShapes.card, role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .backdropSource(source)
                .background(palette.background)
                .then(
                    if (LocalAccessibilityOptions.current.reduceTransparency) {
                        Modifier
                    } else {
                        Modifier.glassStrata(
                            palette.isDark,
                        )
                    },
                ),
        )
        Box(Modifier.matchParentSize().flatGlass(AppShapes.card, palette.card2, palette.border))
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(64.dp)
                .backdropBlur(source, CircleShape, radius = 3.dp, refraction = BackdropRefraction(strength = 7.dp))
                .liquidGlass(CircleShape, palette.card2),
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 92.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemeText("外观与交互", color = palette.text, style = AppTypography.section.strong)
            ThemeText(subtitle, color = palette.body, style = AppTypography.caption.medium)
        }
    }
}
