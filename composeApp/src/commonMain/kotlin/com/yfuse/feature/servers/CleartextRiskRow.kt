package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.network.ServiceEndpointValidation

/**
 * The deliberate acknowledgement a plain-HTTP server address needs before it is used.
 *
 * Shown only while the typed address is HTTP: the copy names what is at stake on this
 * particular network (a LAN neighbour versus the whole path across the public internet), and
 * the consent is stored with the server so it is asked once, not on every edit.
 */
@Composable
internal fun CleartextRiskRow(
    validation: ServiceEndpointValidation,
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!validation.requiresCleartextConfirmation && !validation.cleartextConfirmed) return
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Checkbox
                toggleableState = if (accepted) ToggleableState.On else ToggleableState.Off
            }.pressable { onAcceptedChange(!accepted) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .border(1.5.dp, if (accepted) accent.accent else palette.sub2, RoundedCornerShape(6.dp))
                .background(if (accepted) accent.accent else Color.Transparent, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (accepted) Text("✓", style = AppTypography.caption.strong, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "我了解明文 HTTP 的风险，仍要连接",
                style = AppTypography.body.medium,
                color = palette.text,
            )
            validation.cleartextWarning?.let { warning ->
                Text(warning, style = AppTypography.caption.regular, color = palette.sub2)
            }
        }
    }
}
