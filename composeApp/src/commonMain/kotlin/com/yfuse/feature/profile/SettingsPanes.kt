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
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.LocalPalette

@Composable
internal fun SettingsPanes(
    wide: Boolean,
    index: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val indexWidth = (maxWidth * 0.34f).coerceIn(240.dp, 360.dp)
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Box(Modifier.width(indexWidth).fillMaxHeight()) { index() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(LocalPalette.current.border))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}
