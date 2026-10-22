package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.glass
import com.yfuse.core.widget.WidgetMetadata
import com.yfuse.core.widget.widgetWebLink
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun WidgetManagerGlyph(icon: ImageVector) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(40.dp).glass(AppShapes.thumb, palette.card2, palette.border),
        contentAlignment = Alignment.Center,
    ) {
        ThemeIcon(icon, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun WidgetPublisherIcon(
    metadata: WidgetMetadata,
    size: Dp = 40.dp,
) {
    val palette = LocalPalette.current
    val icon = widgetWebLink(metadata.icon)
    Box(
        Modifier.size(size).glass(AppShapes.thumb, palette.card2, palette.border),
        contentAlignment = Alignment.Center,
    ) {
        ThemeIcon(AppIcons.Grid, null, tint = LocalAccentColors.current.accent, modifier = Modifier.size(size * 0.55f))
        if (icon.isNotBlank()) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                modifier = Modifier.size(size).clip(AppShapes.thumb),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** The same publisher card appears before installation and in the installed component's details. */
@Composable
internal fun WidgetMetadataCard(
    metadata: WidgetMetadata,
    onError: (String) -> Unit,
    onChangeSource: (() -> Unit)? = null,
    changeSourceLabel: String = "更换来源",
    changeSourceEnabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val website = widgetWebLink(metadata.website)
    val plate = if (palette.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.82f)
    val underlay =
        if (LocalAccessibilityOptions.current.reduceTransparency) {
            plate.compositeOver(
                palette.background,
            )
        } else {
            plate
        }
    val edge = palette.text.copy(alpha = if (palette.isDark) 0.18f else 0.12f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(underlay, AppShapes.card)
            .glass(AppShapes.card, plate, edge)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(metadata.title, color = palette.text, style = AppTypography.body.strong)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("v${metadata.version}", "${metadata.modules.size} 个栏目").forEach { label ->
                        Text(
                            label,
                            Modifier
                                .clip(AppShapes.micro)
                                .background(palette.card2)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = palette.sub2,
                            style = AppTypography.caption.regular,
                        )
                    }
                }
            }
            if (onChangeSource != null) {
                WidgetIconButton(
                    AppIcons.Edit,
                    changeSourceLabel,
                    enabled = changeSourceEnabled,
                    onClick = onChangeSource,
                )
            }
        }
        if (metadata.description.isNotBlank()) {
            Text(metadata.description, color = palette.sub2, style = AppTypography.body.reading)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(0.5.dp)
                .background(edge),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WidgetPublisherIcon(metadata, 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("开发者", color = palette.sub2, style = AppTypography.caption.regular)
                Text(
                    metadata.author.ifBlank { "未提供作者" },
                    color = palette.sub,
                    style = AppTypography.caption.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (website.isNotBlank()) {
                WidgetTextButton({
                    try {
                        uriHandler.openUri(website)
                    } catch (_: Exception) {
                        onError("无法打开作者网站")
                    }
                }) {
                    Text("网站", style = AppTypography.caption.regular)
                    ThemeIcon(AppIcons.ChevronRight, null, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                }
            }
        }
    }
}
