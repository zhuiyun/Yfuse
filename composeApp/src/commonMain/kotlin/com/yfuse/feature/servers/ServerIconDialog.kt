@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.yfuse.feature.servers

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.SERVER_ICON_EMOJI_MAX_CHARS
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.ServerIconTints
import com.yfuse.core.designsystem.YfFormField
import com.yfuse.core.designsystem.contentColorForSurface
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.serverTintColor
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 图标与颜色 — an emoji and a tint, which is as much identity as a server can be given
 * without asking it for artwork it does not have.
 *
 * The colour is applied to the badge and washed across the card, so the grid becomes
 * navigable by colour before it is read; the emoji is what makes one card recognisable at a
 * glance among a dozen whose names all start with the same two characters.
 */
@Composable
internal fun ServerIconDialog(
    server: SavedServer,
    onSave: (String?, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var emoji by remember(server.id) { mutableStateOf(server.iconEmoji.orEmpty()) }
    var tint by remember(server.id) { mutableStateOf(server.iconTint) }
    val preview = serverTintColor(server.id, tint)

    GlassDialog(onDismiss = onDismiss) {
        val palette = LocalPalette.current
        val accent = LocalAccentColors.current
        OverlayHeader(title = "图标与颜色", subtitle = server.serverName, onClose = onDismiss)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(AppShapes.thumb)
                    .background(
                        Brush.linearGradient(
                            listOf(preview, preview),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    emoji.ifBlank { server.serverName.take(1).uppercase() },
                    style = AppTypography.section.strong,
                    color = if (emoji.isBlank()) contentColorForSurface(preview) else Color.Unspecified,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("预览", style = AppTypography.caption.strong, color = palette.sub2)
                Spacer(Modifier.height(3.dp))
                Text(
                    "留空则继续用名称首字",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        YfFormField(
            value = emoji,
            onValueChange = { value ->
                // One glyph: the badge is 30dp on the card and a pasted sentence would
                // reflow the header row it sits in.
                emoji = value.trim().takeWhile { !it.isWhitespace() }.take(SERVER_ICON_EMOJI_MAX_CHARS)
            },
            label = "图标，粘贴一个表情",
        )

        Spacer(Modifier.height(14.dp))
        Text("颜色", style = AppTypography.caption.strong, color = palette.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerIconTints.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { swatch ->
                        val value = swatch.argbLong()
                        val selected = tint == value
                        val swatchIndex = ServerIconTints.indexOf(swatch) + 1
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .pressable(
                                    haptic = HapticSignal.Select,
                                    role = Role.RadioButton,
                                    label = "预设颜色 $swatchIndex",
                                    onClickLabel = "选择颜色",
                                    onClick = { tint = value },
                                ).semantics { this.selected = selected }
                                .clip(AppShapes.control)
                                .background(swatch)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = contentColorForSurface(swatch),
                                            shape = AppShapes.control,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = contentColorForSurface(swatch),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "自动配色",
            style = AppTypography.caption.medium,
            color = if (tint == null) accent.accent else palette.sub2,
            modifier =
                Modifier
                    .pressable(onClickLabel = "恢复自动配色", onClick = { tint = null })
                    .touchTarget()
                    .padding(vertical = 4.dp),
        )

        OverlayButtonRow(
            dismissLabel = "取消",
            confirmLabel = "保存",
            onDismiss = onDismiss,
            onConfirm = overlayAction { onSave(emoji.takeIf { it.isNotBlank() }, tint) },
        )
    }
}

/** Compose packs colour as a ULong; the registry stores plain 0xAARRGGBB. */
private fun Color.argbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL
