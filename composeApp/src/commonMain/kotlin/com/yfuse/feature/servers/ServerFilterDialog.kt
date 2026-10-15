@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.yfuse.feature.servers

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.model.SavedServer
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun ServerFilterDialog(
    servers: List<SavedServer>,
    filter: ServerListFilter,
    onSort: (ServerSortOrder) -> Unit,
    onAccount: (String?) -> Unit,
    onLatency: (ServerLatencyFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val accounts =
        remember(servers) {
            servers
                .map { it.userName }
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
        }
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "排序与筛选",
            subtitle = "按在线、延迟、最近使用或账号整理服务器",
            onClose = onDismiss,
        )
        Text("排序", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            ServerSortOrder.entries.forEach { value ->
                OverlayOptionRow(
                    label = value.label,
                    selected = value == filter.sort,
                    onClick = { onSort(value) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("延迟体验", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            ServerLatencyFilter.entries.forEach { value ->
                OverlayOptionRow(
                    label = value.label,
                    selected = value == filter.latency,
                    onClick = { onLatency(value) },
                )
            }
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("账号", style = AppTypography.caption.strong, color = LocalPalette.current.sub2)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                OverlayOptionRow(
                    label = "全部账号",
                    selected = filter.account == null,
                    onClick = { onAccount(null) },
                )
                accounts.forEach { account ->
                    OverlayOptionRow(
                        label = account,
                        selected = filter.account == account,
                        onClick = { onAccount(account) },
                    )
                }
            }
        }
    }
}
