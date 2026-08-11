package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget

@Composable
fun PlayerScreen(component: PlayerComponent) {
    val state by component.store.states.collectAsState(component.store.state)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
            )

            state.error != null -> PlayerLoadError(
                message = state.error!!,
                onRetry = { component.store.accept(PlayerIntent.Retry) },
                onBack = component.onBack,
                modifier = Modifier.align(Alignment.Center),
            )

            state.items.isNotEmpty() -> PlayerLauncher(
                items = state.items,
                startIndex = state.startIndex,
                startPositionMs = state.startPositionMs,
                onLaunched = component.onBack,
            )
        }

        Box(
            Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart)
                .pressable(onClickLabel = "返回", onClick = component.onBack)
                .touchTarget()
                .size(38.dp)
                .glass(
                    shape = CircleShape,
                    fill = Color.Black.copy(alpha = 0.28f),
                    border = Color.White.copy(alpha = 0.32f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // The app's own mark, like every other 返回 in the app. This was the one
                // Material icon left, and it cost a whole icon pack on the dependency list.
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun PlayerLoadError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = 32.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("无法开始播放", color = Color.White)
        Text(
            message,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerLoadErrorAction(label = "返回", primary = false, onClick = onBack)
            PlayerLoadErrorAction(label = "重试", primary = true, onClick = onRetry)
        }
    }
}

@Composable
private fun PlayerLoadErrorAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Box(
        Modifier
            .height(44.dp)
            .widthIn(min = 104.dp)
            .pressable(onClickLabel = label, onClick = onClick)
            .glass(
                shape = CircleShape,
                fill = if (primary) accent.accent else Color.Black.copy(alpha = 0.28f),
                border = if (primary) accent.border else Color.White.copy(alpha = 0.32f),
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) accent.onAccent else Color.White)
    }
}
