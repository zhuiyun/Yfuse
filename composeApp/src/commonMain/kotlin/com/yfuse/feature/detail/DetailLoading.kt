package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette

@Composable
internal fun DetailSkeleton(heroHeight: Dp) {
    val palette = LocalPalette.current
    val fill = if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)
    Column(Modifier.fillMaxSize()) {
        // A loading placeholder can disappear before Compose's shared-transition overlay has
        // received its first bounds. Making that short-lived node a shared element leaves the
        // overlay trying to draw a detached node and crashes with "current bounds not set yet".
        // The real hero below remains shared once the detail has loaded.
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(fill),
        )
        Column(
            Modifier
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .width(96.dp)
                        .height(142.dp)
                        .clip(GlassShapes.poster)
                        .background(fill),
                )
                Column(
                    Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(18.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.46f)
                            .height(11.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(11.dp)
                            .clip(GlassShapes.thumb)
                            .background(fill),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(GlassShapes.card)
                    .background(fill),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(GlassShapes.chip)
                            .background(fill),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(GlassShapes.thumb)
                    .background(fill),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .height(12.dp)
                    .clip(GlassShapes.thumb)
                    .background(fill),
            )
        }
    }
}
