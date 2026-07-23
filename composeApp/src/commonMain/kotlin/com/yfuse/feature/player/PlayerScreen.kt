package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states

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

        Surface(
            shape = CircleShape,
            color = Color(0x66000000),
            modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
        ) {
            Box(Modifier.clickable(onClick = component.onBack).padding(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
    }
}
