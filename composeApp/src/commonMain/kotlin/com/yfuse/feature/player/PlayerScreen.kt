package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable

@Composable
fun PlayerScreen(component: PlayerComponent) {
    val state by component.store.states.collectAsState(component.store.state)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
            )

            state.error != null -> Text(
                state.error!!,
                color = Color.White,
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
                .size(38.dp)
                .pressable(onClick = component.onBack)
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
