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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.ServerHealth
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OperationFeedback
import com.yfuse.core.designsystem.OperationTone
import com.yfuse.core.designsystem.OverlayButton
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.model.SavedServer
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ServerTransportDiagnosticsDialog(
    server: SavedServer,
    health: ServerHealth? = null,
    checking: Boolean = false,
    onProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val diagnostics = remember(server) { diagnoseServerTransport(server) }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "HTTPS 诊断",
            subtitle = server.serverName,
            onClose = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            diagnostics.forEach { item ->
                val color =
                    when (item.severity) {
                        TransportDiagnosticSeverity.Secure -> Semantic.Success
                        TransportDiagnosticSeverity.LocalCleartext -> Semantic.Warning
                        TransportDiagnosticSeverity.Blocked -> Semantic.Error
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .flatGlass(AppShapes.chip, palette.card2, palette.border)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${item.routeName} · ${item.summary}",
                            style = AppTypography.body.medium,
                            color = palette.text,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.address,
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (checking || health != null) {
            OperationFeedback(
                if (checking) "正在测试实际连接与延迟…" else health?.summary.orEmpty(),
                if (checking) {
                    OperationTone.Working
                } else if (health?.reachable ==
                    true
                ) {
                    OperationTone.Success
                } else {
                    OperationTone.Error
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        OverlayButton(
            label = if (checking) "正在测试连接与延迟…" else "同时测试连接与延迟",
            loading = checking,
            onClick = onProbe,
            modifier = Modifier.fillMaxWidth(),
            tone = OverlayButtonTone.Primary,
        )
    }
}
