package com.yfuse.feature.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassButtonEmphasis
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.SettingsCard
import com.yfuse.core.designsystem.glassButtonAlpha
import com.yfuse.core.designsystem.glassButtonLift
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.resolveGlassButtonVisuals
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.waitingPulse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** A status summary with a distinct command, rather than a navigation row that suggests another page. */
@Composable
internal fun PersonalSyncCard(
    title: String,
    status: String,
    lastSyncedAtEpochMs: Long?,
    actionLabel: String,
    enabled: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    val palette = LocalPalette.current
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = AppTypography.body.strong, color = palette.text)
                Text(
                    status,
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    modifier = Modifier.liveStatus(),
                )
            }
            BoxWithConstraints {
                val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.15f
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SyncLastSuccess(lastSyncedAtEpochMs)
                        SyncActionButton(actionLabel, enabled, syncing, onSync)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) { SyncLastSuccess(lastSyncedAtEpochMs) }
                        SyncActionButton(actionLabel, enabled, syncing, onSync)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncLastSuccess(epochMs: Long?) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("最近成功", style = AppTypography.caption.regular, color = palette.sub2)
        Text(
            epochMs?.let {
                Instant
                    .ofEpochMilli(
                        it,
                    ).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            } ?: "暂无同步记录",
            style = AppTypography.caption.medium,
            color = palette.text,
        )
    }
}

@Composable
internal fun SyncActionButton(
    label: String,
    enabled: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val visuals = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, palette, LocalAccentColors.current)
    val currentLabel = if (syncing) "同步中" else label
    Row(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 116.dp)
            .graphicsLayer { alpha = glassButtonAlpha(enabled || syncing) }
            .pressable(
                enabled = enabled && !syncing,
                role = Role.Button,
                haptic = HapticSignal.Tap,
                focusShape = AppShapes.chip,
                onClickLabel = label,
                onClick = onSync,
            ).shadow(glassButtonLift(palette), AppShapes.chip)
            .liquidGlass(
                shape = AppShapes.chip,
                fill = visuals.fill,
                border = visuals.border,
                sheen = visuals.sheen,
            ).waitingPulse(active = syncing, shape = AppShapes.chip, color = visuals.content)
            .semantics { if (syncing) stateDescription = "正在同步，请稍候" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (syncing) {
            OrbProgress(size = 16.dp, color = visuals.content)
        } else {
            Icon(AppIcons.Refresh, contentDescription = null, tint = visuals.content, modifier = Modifier.size(16.dp))
        }
        Text(currentLabel, style = AppTypography.caption.strong, color = visuals.content)
    }
}
