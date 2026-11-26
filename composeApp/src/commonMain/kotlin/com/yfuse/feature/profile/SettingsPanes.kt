package com.yfuse.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalWindowPosture

@Composable
internal fun SettingsPanes(
    wide: Boolean,
    index: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val posture = LocalWindowPosture.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bookHingeFraction =
            posture.verticalHingeFraction
                ?.takeIf { posture.isBookMode }
                ?.coerceIn(0.32f, 0.58f)
        val hingeGap =
            if (posture.isBookMode) {
                posture.hingeThicknessDp.coerceAtLeast(0.dp)
            } else {
                0.dp
            }
        val indexWidth =
            if (bookHingeFraction != null) {
                ((maxWidth - hingeGap).coerceAtLeast(1.dp)) * bookHingeFraction
            } else {
                (maxWidth * 0.34f).coerceIn(240.dp, 360.dp)
            }
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Box(Modifier.width(indexWidth).fillMaxHeight()) { index() }
                if (hingeGap > 0.dp) {
                    Box(Modifier.width(hingeGap).fillMaxHeight().background(Color.Black))
                } else {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(LocalPalette.current.border))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}
