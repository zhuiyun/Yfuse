package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.ServerSource

/**
 * 资源 in full — every server that holds this title, one row each.
 *
 * The rail on the page answers "who has it" in a glance and stops at two cards; this
 * answers "which copy do I want", which needs the numbers side by side and vertically
 * aligned. Same facts, laid out to be compared rather than skimmed: a rail makes you hold
 * the previous card's bitrate in your head while you scroll to the next one.
 *
 * Centred, because that is where overlays live outside the player. It is a long list, so
 * it scrolls inside a bounded panel rather than growing past the screen.
 */
@Composable
internal fun SourceListDialog(
    sources: List<ServerSource>,
    selectedServerId: String?,
    selectedItemId: String?,
    accent: Color,
    onSelect: (serverId: String, itemId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    // Order is the caller's, which ranks on what each server holds before the selected entry
    // is restated in terms of the chosen version. Filtering preserves it.
    val available =
        remember(sources) {
            sources.filter { it.reachable && it.source != null && it.itemId != null }
        }
    // The best copy, called out once. It is the first row by construction, but saying so
    // beats making the reader infer it from the ordering.
    val bestServerId =
        remember(available) {
            available
                .firstOrNull()
                ?.takeIf { available.size > 1 && it.source?.hasQualityEvidence() == true }
                ?.serverId
        }

    GlassDialog(liquidButtons = false, onDismiss = onDismiss) {
        OverlayHeader(
            title = "资源",
            subtitle = "${available.size} 个媒体库有这个片子 · 再点已选项即可播放",
            onClose = onDismiss,
        )
        // No height cap of its own: [GlassDialog] scrolls whatever it cannot fit, and it is
        // the only one that knows how much screen there actually is.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { entry ->
                val selected = entry.serverId == selectedServerId && entry.itemId == selectedItemId
                val select = { entry.itemId?.let { onSelect(entry.serverId, it) }; Unit }
                val animatedSelect = overlayAction(select)
                SourceRow(
                    entry = entry,
                    selected = selected,
                    accent = accent,
                    best = entry.serverId == bestServerId,
                    onSelect = if (selected) animatedSelect else select,
                )
            }
            if (available.isEmpty()) {
                Text(
                    "只有当前服务器有这个片子。",
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    entry: ServerSource,
    selected: Boolean,
    accent: Color,
    best: Boolean,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    val source = entry.source
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onSelect)
            .solidGlass(
                shape = GlassShapes.card,
                fill =
                    if (palette.isDark) {
                        Color.White.copy(alpha = 0.06f)
                    } else {
                        Color.White.copy(alpha = 0.82f)
                    },
                border = null,
            ).then(
                if (selected) {
                    Modifier.border(0.75.dp, accent.copy(alpha = 0.65f), GlassShapes.card)
                } else {
                    Modifier
                },
            ).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(serverTint(entry.serverId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.serverName.take(1).uppercase(),
                    style = AppTypography.caption.strong,
                    color = Color.White,
                )
            }
            Text(
                entry.serverName,
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (best) {
                Text(
                    "Best",
                    style = AppTypography.caption.strong,
                    color = Color(0xFF9A6B12),
                    modifier =
                        Modifier
                            .clip(GlassShapes.chip)
                            .background(Color(0xFFF5C86A).copy(alpha = 0.30f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                source?.size ?: "—",
                style = AppTypography.caption.strong,
                color = if (selected) accent else palette.body,
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source?.dolbyVision == true) {
                DolbyChip("VISION", if (selected) accent else palette.sub)
            } else {
                FactChip(source?.rangeLabel ?: "—")
            }
            source?.bitrate?.let { FactChip(it) }
            source?.frameRate?.let { FactChip(it) }
            Spacer(Modifier.weight(1f))
            CountFact(AppIcons.Volume, source?.audioTrackCount ?: 0)
            CountFact(AppIcons.Subtitle, source?.subtitleTrackCount ?: 0)
        }
    }
}

@Composable
private fun FactChip(label: String) {
    val palette = LocalPalette.current
    Text(
        label,
        style = AppTypography.caption.strong,
        color = palette.sub,
        maxLines = 1,
        modifier =
            Modifier
                .clip(GlassShapes.chip)
                .background(
                    if (palette.isDark) {
                        Color.White.copy(alpha = 0.08f)
                    } else {
                        Color(0xFF141A26).copy(alpha = 0.05f)
                    },
                ).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CountFact(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
) {
    val palette = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.sub2, modifier = Modifier.size(11.dp))
        Text(count.toString(), style = AppTypography.caption.strong, color = palette.sub2)
    }
}
